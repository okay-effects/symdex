# symdex — user guide

symdex answers structural questions about a codebase: where a symbol is
defined, every place it is used, who calls it, what implements it, which
`given` the compiler picked at a call site, what a module change reaches.
The answers come from the compiler's own output, so they are exact where
grep is approximate: a renamed import, an extension call, an overload or
an inherited member is found, and a comment, a string or another symbol
with the same name is not.

It is built for coding agents (it speaks MCP) and works as well from a
shell or the sbt shell.

What it is not: a search by meaning. "Where is the retry logic" is a
retrieval question; rozum's `rag.search` answers it. symdex takes over
once you know the symbol: "who calls `retryWith`, and what overrides it".

Every example below is real output, over the okay library.

## 1. Set up

### With the sbt plugin (Scala projects)

`project/plugins.sbt`:

```scala
resolvers += Resolver.url("symdex", url("https://sergey-scherbina.github.io/symdex"))(Resolver.ivyStylePatterns)
addSbtPlugin("io.github.sergey-scherbina" % "sbt-symdex" % "0.5.2")
```

Then, once:

```sh
sbt symdexIndex symdexMcp symdexHook
```

- `symdexIndex` compiles every project of this build, main and test, with
  SemanticDB on, and prints what symdex covers. A build pulled in by
  `ProjectRef` is not compiled by it — only what this build's projects
  need of it, as dependencies.
- `symdexMcp` adds the server to `.mcp.json`, where Claude Code and other
  MCP clients find it.
- `symdexHook` adds the output-digesting hook (section 5) to
  `.claude/settings.local.json`.

The plugin downloads symdex itself, once, into `~/.symdex/<version>`. Both
files are merged into, never overwritten. From then on, compiling is all
it takes: symdex sees a compile from the SemanticDB it rewrites.

### Without sbt

Unzip a [release](https://github.com/sergey-scherbina/symdex/releases) and
run it (Java 17 or newer):

```sh
symdex/bin/symdex serve --root /path/to/project
```

The project must have been compiled with SemanticDB on. In sbt that is
`semanticdbEnabled := true` in the build, or for one session:

```sh
sbt 'set every semanticdbEnabled := true' Test/compile
```

### Java

javac writes SemanticDB through scip-java's compiler plugin; symdex reads
it like Scala's:

```sh
PLUG=$(cs fetch com.sourcegraph:semanticdb-javac:0.12.3 | head -1)
javac -d out/classes -classpath "$PLUG" \
  "-Xplugin:semanticdb -sourceroot:$PWD -targetroot:$PWD/out/meta" src/**/*.java
```

### Other languages: SCIP

Run the language's SCIP indexer (scip-typescript, scip-python,
rust-analyzer's `scip` command, scip-clang) and leave its `index.scip`
anywhere under the root. symdex reads every `*.scip` it finds.

### Registering by hand

```json
{"mcpServers": {"symdex": {"command": "/path/to/symdex/bin/symdex",
  "args": ["serve", "--root", "."]}}}
```

## Languages

symdex reads what compilers and indexers write; it has no parser of its own.
What a language gets depends on what its toolchain writes.

| language | read from | status | what it has |
|---|---|---|---|
| Scala 3 | SemanticDB + TASTy (the compiler) | tested: symdex, okay (~500 files) | everything: exact bodies, exact callers, `givens`, extension methods |
| Java | SemanticDB (scip-java's javac plugin) | tested: a small project | definitions, references, implementations, overrides; callers and bodies approximate (no TASTy) |
| any SCIP language | `index.scip` (its own indexer) | tested with scip-java's SCIP | whatever the indexer writes; bodies exact from SCIP's enclosing ranges |
| TypeScript/JavaScript, Python, Rust, C/C++, Kotlin, Ruby, C#… | scip-typescript, scip-python, rust-analyzer `scip`, scip-clang, scip-java… | should work, NOT tested | as good as the indexer |
| Scala 2, other SemanticDB writers | SemanticDB (semanticdb-scalac) | should work, NOT tested | as Java: no TASTy, so approximate callers and bodies |

`givens` needs SemanticDB's *synthetics*, which Scala 3 writes; extension
methods and exact bodies for Scala need TASTy. A language with neither
SemanticDB nor a SCIP indexer is not supported.

## Limits and scale

- **Measured** on okay's slice: 512 documents, 54 549 symbols, 287 908
  occurrences; the index builds in about 0.4 s and lives in memory; TASTy
  for 2 460 classes reads in a few seconds, in parallel, per module on
  first use, and is saved in `.symdex/tasty`. A one-off command-line call
  costs about 1 s (JVM, scan, parse); once TASTy is saved, `callers`,
  `source` and `members` cost the same.
- **Not measured**: a repository ten times larger. The index is held in
  memory and rebuilt whole (unchanged files are not re-read); its size and
  build time grow with the occurrence count. Only TASTy's reading is
  persisted, so a server's first answer after start pays the build.
- **Coverage is what was compiled.** A module never compiled with SemanticDB
  is invisible, and "every reference" means every one in the index.
  `status` names what is covered and what is stale.
- **Prose is not code.** Docs, comments and strings are not indexed — grep
  them.
- **Callers** are exact where TASTy (or SCIP) gives spans; elsewhere the
  nearest definition above the reference is named.

## 2. Asking

A query is one of:

| form | example |
|---|---|
| a name | `joinSorted` |
| a qualified name | `Bulk.joinSorted`, `okay.Bulk` (owners may be skipped: `Tables.orderedBy` finds `Tables.Plan.orderedBy`) |
| a SemanticDB symbol | `okay/Bulk#joinSorted().` |
| a position | `Tables.scala:188` or `Tables.scala:188:60` (a path or its tail) |

A name that means several different symbols is answered with the list of
candidates, each with its kind and place; pass one of them back. Overloads
and a class with its companion count as one name and are answered together.

From a shell: `symdex <tool> key=value … --root DIR`. In the sbt shell, with
the plugin: `symdex <tool> key=value …`. Over MCP the same arguments are a
JSON object.

## 3. The tools

### definition — where it is, with its doc and signature

```text
$ symdex definition query=Fiber.isDone
[generation 1, 512 files, built 0s ago, ~72 tokens]
okay/Fiber.isDone().  (method)
  okay-async/src/main/scala/Async.scala:151:9  [okay-async]
        /** `f.answered` (fiber-is-done): whether the fiber has its answer */
        def isDone: Boolean = f.answered
```

Instead of: `grep -rn "def isDone"`, which also finds ForkJoinTask's.

### references — every use; callers groups them

```text
$ symdex references query=Bulk.joinSorted callers=true
[generation 1, 512 files, built 2s ago, ~71 tokens]
okay/Bulk#joinSorted().: 2 references in 2 files
  okay.BulkParallel.apply  okay-stream/src/main/scala-jvm-native/BulkParallel.scala:53
  okay.Tables.Heap#compile  okay-stream/src/main/scala/Tables.scala:188
```

`grep -rnw joinSorted` over the same files gives 53 lines, for six
different methods of that name. Arguments: `callers=true` (group by the
enclosing definition, exact from TASTy), `context=true` (each line's
source), `in=PATH` (only files or modules whose name contains it),
`all=true` (when the name is ambiguous, all candidates), `limit`.

### implementations — subtypes, overriders, anonymous and given instances

```text
$ symdex implementations query=Bulk.joinSorted
okay/Bulk#joinSorted().: 1 implementations, 0 given instances, 2 anonymous
  okay/cluster/FlowBulk#joinSorted().  (method)  okay-cluster/src/main/scala/okay/cluster/FlowBulk.scala:92:16
  anonymous (`new T { ... }`):
    in okay.BulkParallel.apply  okay-stream/src/main/scala-jvm-native/BulkParallel.scala:52:20
    in okay.Bulk.local  okay-stream/src/main/scala/Bulk.scala:144:18
```

For a type: its subclasses and objects, transitively; for a member: what
overrides it; for a type class: its `given` instances. Anonymous classes
are named by the definition they sit in.

### givens — what the compiler inserted at a line

```text
$ symdex givens at=TestJoinStrategy.scala:28:31
  okay-stream/src/test/scala-cross/TestJoinStrategy.scala:28:31  `Tables.of(l).sortByKey.join(Tables.of(r).sortByKey).collect`
    given okay/Row.In.left().  (final implicit given method)  src/main/scala/Row.scala:48:11
  okay-stream/src/test/scala-cross/TestJoinStrategy.scala:28:31  `Tables.of(l).sortByKey.join`
    given okay/Row.In.left().  (final implicit given method)  src/main/scala/Row.scala:48:11
  okay-stream/src/test/scala-cross/TestJoinStrategy.scala:28:31  `Tables.of(l).sortByKey`
    given okay/Row.In.left().  (final implicit given method)  src/main/scala/Row.scala:48:11
    scala/math/Ordering.Int.  (symbol)  (outside the index: a library)
```

Every call starting at that column, outermost first. Which `Ordering` that
`sortByKey` received: `Ordering.Int`; `Row.In.left` is the proof that the
effect row holds `Tables`. Also implicit
conversions and inferred `apply`s. No text search can answer this.

### members — declared, inherited, and extension methods

```text
$ symdex members query=okay.Fiber
okay/Fiber#  (trait)
    onComplete  (abstract method)  okay-async/src/main/scala/Async.scala:122:7
    cancel  (abstract method)  okay-async/src/main/scala/Async.scala:126:7
    answered  (abstract method)  okay-async/src/main/scala/Async.scala:136:7
    …
  extension methods:
    isDone  (on okay.Fiber#)  okay-async/src/main/scala/Async.scala:151:9
```

Inherited members appear under the parent they come from, unless the type
overrides them. Compiler-generated members (a case class's `copy`) only
with `all=true`.

### outline — what a file is, without reading it

```text
$ symdex outline file=okay-cluster/src/main/scala/okay/cluster/FlowBulk.scala
okay-cluster/src/main/scala/okay/cluster/FlowBulk.scala  (134 lines, 22 definitions; `source` for one body)
   36  class FlowBulk
   36    def parts
   …
   92    def joinSorted
```

266 characters instead of the file's 6 862.

### source — one definition's text

```text
$ symdex source query=Plan.orderedBy
okay/Tables.Plan.orderedBy().  okay-stream/src/main/scala/Tables.scala:154:9  (5 lines)
  154      def orderedBy[K, X](p: Plan[(K, X)]): Option[Ordering[K]] = p match
  155        case SortByKey(_, o) => Some(o)
  156        case OrderedByKey(_, o) => Some(o)
  157        case Where(q, _) => orderedBy(q)
  158        case _ => None
```

The body's extent comes from TASTy (exact); `maxLines` caps it (default 80).

### modules — who uses whom, and what a change reaches

`modules` alone lists every module and what it uses; `modules query=okay-stream`
says what it uses, what uses it, and every module a change there can reach.
A dependency here is a USE — code in one module naming a symbol defined in
another — read from references, not from the build's `dependsOn`.

### status — what the index covers

Its generation and age, how many files, modules, symbols and occurrences,
how much TASTy has been read, what the tool schemas cost per turn, and
which sources were edited after they were compiled.

### more — the rest of a long answer

See the next section.

## 4. Budgets: what an answer costs

Everything an agent reads stays in its context for the rest of the
session, so symdex keeps answers small and says what they cost: the first
line of each ends with `~N tokens`.

- **Budget.** Every tool takes `budget` (characters, default 8 000, about
  2 000 tokens). An answer over it is *compressed*: its header, the files
  it names with a count each, and its first lines — all copied from the
  answer, nothing invented — plus an archive id. `more id=<id> from=<line>`
  pages the whole answer.
- **Exact reads are paged, never summarized.** `source` and `definition`
  over budget are cut at a line, with the `more` call that continues them.
- **Schemas.** Every tool's description is sent to the model on every turn,
  called or not. `serve --tools definition,references,source,outline --lean`
  serves four tools with one-sentence descriptions: about 335 tokens, against
  about 1 517 for all ten (`status` shows both). Also `SYMDEX_TOOLS` and
  `SYMDEX_LEAN=1`; in the plugin, `symdexTools` and `symdexLean`.

## 5. The hook: large grep and test outputs

An agent will still grep. `bin/symdex-hook` is a Claude Code PostToolUse
hook for Bash and Grep: when a search or a test run prints 3 500 tokens or
more, the output the model sees is replaced by a digest of it — for a
search, the files with a count each and the first hits; for a log, its
head, its tail and every error/fail/exception/warn line. The whole output
is saved in `.symdex/archive/<id>.txt` (at most 200 kept) and the digest
says where, so the agent can read any range of it. A grep for an
identifier also gets the symdex call that answers it exactly.

```text
rg -n answered   (over okay)     167 409 characters in, 4 745 out
```

Left alone, always: outputs under 3 500 tokens, digests that would not save
30%, exact reads (`cat`, `sed -n`, `head`, `git diff`, `git show`, anything
piped into `head` or `sed`), and any output that looks like it carries a
credential (it would otherwise be written to disk). Any failure leaves the
output as it was. No model writes the digest.

Installed by `sbt symdexHook`, or in `.claude/settings.json` /
`.claude/settings.local.json`:

```json
{"hooks": {"PostToolUse": [{"matcher": "Bash|Grep",
  "hooks": [{"type": "command", "command": "/Users/you/.symdex/0.5.0/symdex/bin/symdex-hook"}]}]}}
```

## 6. Freshness and coverage

- **Freshness.** Before answering, symdex looks at the SemanticDB on disk
  (at most every 2 seconds). When a compile has rewritten it, a new index
  is built — only changed files are re-read — and swapped in whole. A
  source edited since its last compile is listed by `status`; answers about
  it may be off until the next compile.
- **Coverage.** An index answers for what was compiled with SemanticDB, and
  "every reference" means every reference *in the index*. A module never
  compiled that way is invisible. `status` says what is covered;
  `symdexIndex` compiles everything.
- **TASTy** (body spans, extension methods) is read per module when a tool
  first needs it; a server reads all of it in the background at start.
  What was read is saved in `.symdex/tasty`, one file per class directory,
  and reused by the next process while that module's SemanticDB is
  unchanged (the same files by count, latest mtime and total size). A
  class whose TASTy cannot be read falls back to indentation for its bodies,
  and `status` counts it.

## 7. Reference

### Command line

```text
symdex serve [--root DIR] [--tools a,b] [--lean]   MCP server on stdin/stdout
symdex <tool> [--root DIR] key=value…              one call, the answer on stdout
symdex files [--root DIR]                          the source files indexed
symdex hook                                        the PostToolUse hook (stdin → stdout)
```

`--root DIR` and `--root=DIR` are both accepted; an unknown option is an
error, never ignored.

### Environment

| variable | meaning |
|---|---|
| `SYMDEX_ROOT` | the root when `--root` is not given (default: the working directory) |
| `SYMDEX_TOOLS` | `serve`: the tools to serve, comma-separated |
| `SYMDEX_LEAN` | `serve`: `1` for terse schemas |
| `SYMDEX_JAVA_OPTS` | extra JVM options for the staged launcher |
| `SYMDEX_HOME` | the plugin: a symdex checkout or unpacked release to use instead of downloading |
| `CLAUDE_PROJECT_DIR` | the hook: where `.symdex/archive` goes (set by Claude Code) |

### sbt plugin

| key | default | |
|---|---|---|
| `symdexIndex` | | compile this build's projects, main and test; print `status` |
| `symdexMcp` | | add the server to `.mcp.json` |
| `symdexHook` | | add the hook to `symdexHookFile` |
| `symdex <tool> …` | | a command: one call over this build |
| `symdexVersion` | the plugin's | the release to download |
| `symdexHome` | `SYMDEX_HOME` | use this symdex instead |
| `symdexTools` | all | tools `symdexMcp` registers |
| `symdexLean` | `false` | terse schemas |
| `symdexHookFile` | `.claude/settings.local.json` | where the hook is registered |

## 8. Troubleshooting

- **"nothing named X in the index" for something that exists.** Run
  `status`. Zero files: the root is wrong (check `--root`) or nothing was
  compiled with SemanticDB. Files but not that module: it was not compiled
  with SemanticDB; `symdexIndex`.
- **A reference grep finds and symdex does not.** Usually the module is
  not in the index (above). Or the match is not that symbol: a comment, a
  string, another symbol with the same name.
- **An answer about a file looks shifted.** The file was edited after its
  last compile; `status` lists it. Compile.
- **`callers` names a nearby definition instead of the right one.** That
  module's TASTy was not readable; `status` counts unreadable classes, and
  their bodies fall back to indentation.
- **sbt runs out of memory while compiling a big build with SemanticDB.**
  Give it more heap (`-Xmx6g` in `.jvmopts`); SemanticDB adds little, but
  `set every` re-evaluates the whole build.
- **The hook changes nothing.** Outputs under ~12 000 characters never
  reach it, and exact reads, diffs and pipes into `head`/`sed` are left
  alone on purpose.

## 9. How it works

1. **SemanticDB** (written by the Scala 3 compiler, or by javac with the
   plugin) is read with symdex's own protobuf decoder: occurrences give
   definitions and references; symbol information gives kinds, properties,
   parents, declarations and overridden symbols; *synthetics* record which
   given each call site resolved.
2. **TASTy** (the typed trees beside every class file) is read with
   tasty-query for what SemanticDB lacks: where each definition's body
   ends, and which methods are extensions. The two are joined by source
   path and the position of the definition's name.
3. **SCIP** indexes are read into the same model: SCIP's symbol descriptors
   use SemanticDB's syntax, and its enclosing ranges are body spans.
4. The index is built in memory (0.4 s for okay's 512 documents and 288 000
   occurrences), rebuilt when a compile changes the input, and served by
   okay's MCP server.

Design decisions and their measurements are in [specs/symdex.md](specs/symdex.md).

## 10. Literature

- **SemanticDB specification** — scalameta. The format: documents,
  occurrences, symbol information, synthetics, and the symbol syntax
  symdex queries in. https://scalameta.org/docs/semanticdb/specification.html
- **TASTy** — Scala 3's typed abstract syntax trees, the compiler's
  serialized output beside each class.
  https://docs.scala-lang.org/scala3/guides/tasty-overview.html; read with
  **tasty-query** (Scala Center), https://github.com/scalacenter/tasty-query
- **SCIP** — Sourcegraph's code-intelligence index format and its
  indexers. https://github.com/sourcegraph/scip; the rationale against
  its predecessor: Sourcegraph, "SCIP — a better code indexing format than
  LSIF" (2022).
- **LSIF** — the Language Server Index Format, Microsoft: an LSP-shaped
  precomputed index. https://microsoft.github.io/language-server-protocol/specifications/lsif/0.6.0/specification/
- **Kythe** (Google) and **Glean** (Meta) — cross-language code indexing
  at company scale; the same idea, served to people and tools.
  https://kythe.io, https://glean.software
- **Quill** — a Java code-intelligence MCP server from bytecode into
  SQLite; the starting point for symdex. https://github.com/treblereel/quill
- **Model Context Protocol** — the wire symdex speaks.
  https://modelcontextprotocol.io/specification
- J. Yang et al., **"SWE-agent: Agent-Computer Interfaces Enable Automated
  Software Engineering"** (NeurIPS 2024) — small, purpose-built tools with
  compact, informative output make agents measurably better: the argument
  for few tools and budgets.
- N. F. Liu et al., **"Lost in the Middle: How Language Models Use Long
  Contexts"** (TACL 2024) — models use information in the middle of a long
  context poorly: one reason a digest beats a wall of grep output.
- **"Claude Context Diet"** — the PostToolUse-hook note (large outputs to
  a fact list plus an archive; exact reads never touched) behind symdex's
  budgets and hook, which keep its rules and drop the model.
- **Claude Code hooks reference** — `updatedToolOutput` and the
  PostToolUse input. https://code.claude.com/docs/en/hooks
