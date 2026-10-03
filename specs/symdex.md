# symdex v0 — structural code intelligence over MCP

Status: symdex 0.5.0, sbt-symdex 0.5.2 (2026-10-02). The history is CHANGELOG.md. Boxes are checked as tests cover them;
what changed from the draft is in Decisions.

## Why

An agent working in a Scala codebase spends most of its tool calls
finding things: where a symbol is defined, who calls it, which `given` a
call site actually gets, which modules a change reaches. grep answers by
text, so it misses renamed imports, extension methods, givens, overloads
and inherited members, and it floods on common names. The compiler
already knows all of it, exactly, and writes it down. symdex reads what
the compiler wrote and answers those questions over MCP.

Not retrieval: rozum's `rag.search` ranks chunks for a concept or a
symptom. symdex answers "who calls this" with a list that is complete or
says it is not.

## Sources

- **SemanticDB** (`-Xsemanticdb`, sbt's `semanticdbEnabled`) — every
  occurrence of every symbol with its range, every symbol's kind,
  properties, parents, declarations and overridden symbols, and the
  synthetics: which given each call site resolved. v0 reads only this
  (Decisions).
- later: **TASTy** (`.tasty` beside every `.class`), read with
  `tasty-query`, for definition SPANS — which SemanticDB lacks and an
  exact `callers` needs.
- not needed: an sbt export of the project graph. Modules come from
  where the SemanticDB was written, and their dependencies from
  references (Decisions).
- v1: **SCIP** indexes for other languages (scip-java, scip-typescript,
  scip-python, rust-analyzer), ingested into the same symbol/occurrence
  model. symdex writes no per-language analyzer.

## Tools (v0)

Few and exact; every tool is context an agent pays for (Quill's 50+ is
the counter-example). Each answer names the generation it came from and
its age.

- [x] `symdex.definition(symbol | file:line:col)` — where it is defined,
      its signature and doc comment.
- [x] `symdex.references(symbol)` — every occurrence, grouped by file;
      `callers` is the same filtered to call sites.
- [x] `symdex.implementations(symbol)` — subclasses, overriding members,
      `given` instances of a typeclass.
- [x] `symdex.givens(file:line:col)` — which givens the compiler resolved
      at that call site (from SemanticDB synthetics).
- [x] `symdex.members(type)` — declared and inherited members, extensions
      in scope included.
- [x] `symdex.modules(path | project)` — which module owns a path, what it
      uses, what uses it, and what a change there reaches (the
      "affected" question). A dependency is a USE — a reference from
      one module's code into a symbol defined in another — not a
      declared `dependsOn` (Decisions).
- [x] `symdex.status` — generations, their age against the sources, what is
      stale.

Symbol names are SemanticDB's (`okay/Free#flatMap().`), with a fuzzy
lookup from a plain name that returns candidates rather than guessing.

## Context diet (v0.2)

Every answer is context the agent pays for on every later turn. The
"Claude Context Diet" note (a PostToolUse hook: tool outputs of 3 500+
tokens replaced by a model-written fact list plus an archive path; a
week's 1.85M tokens became 186K; exact reads and diffs never
compressed; Haiku 4.5 rejected for inventing file paths) is the source
of this section. symdex takes its shape and drops the model: its
answers are already structured, so the summary can be EXACT.

- [x] `outline file` — a file's definitions, nested, one line each with
      its line number: what a file is, without reading it.
- [x] `source query` — one definition's text, read by indentation from
      its first line to the end of its body (SemanticDB has no spans;
      TASTy would make it exact), capped by `maxLines`.
- [x] Every answer has a `budget` (default 8 000 chars, about 2 000
      tokens). Over it, a structural answer is COMPRESSED: its header,
      the files it names with a count each, and the first lines that
      fit — every one copied from the answer, so nothing is invented —
      and the whole answer is archived.
- [x] Exact reads (`source`, `definition`) are PAGED, never summarized:
      the hook's own rule for `cat`.
- [x] `more id from lines` pages any archived answer; a test pins that
      the pages put back together are the full answer, byte for byte.
- [x] `references in` narrows to a path or module before anything is
      cut.

Measured on okay: the same 200-line task, reading the file against
`outline` + `source`, and the widest answers, full against compressed.

| ask | before | after |
|---|---|---|
| FlowBulk.scala: what is in it, then `joinSorted` | Read: 6 862 chars | outline 266 + source 343 |
| Tables.scala: then `Plan.orderedBy` | Read: 18 565 | outline 2 842 + source 373 |
| Chunks.scala: what is in it | Read: 24 618 | outline 1 037 |
| references to `Source`, each line shown | 37 664 | 5 302 (86% less) |
| references to `Tables.of`, each line shown | 9 775 | 4 977 (50% less) |

Most answers never reach the budget: references grouped by file are
already small (`Chunks.fromIterator`, 46 references, 1 407 chars).
Compression is for the wide ones. Not adopted: a model in the loop. A
summary computed from a structured answer costs nothing, cannot invent
a path, and is the same every time.

### Schemas are paid every turn (from rozum's gateway)

rozum has no output-compressing hook. Its gateway does the other half,
for local models: `auto_context.rs` fits a conversation to the window
(oldest turns dropped, an extractive or model-written note about what
went, tool descriptions stripped as the last step), its token estimate
counts tool results and SCHEMAS (chars / 3.5; Claude Code's 33 tools
are ~5K tokens), and `codex_lean.rs` cuts a small model's tool set to
the coding surface, because context size broke its tool calls before
anything else did. What carries over to symdex:

- [x] Every answer's first line carries its own cost (`~N tokens`, the
      same chars / 3.5 estimate), so an agent sees what it pays.
- [x] `status` prices the tool list itself: what `tools/list` costs in
      context on every turn, full and lean.
- [x] `serve --tools a,b,…` serves only those (okay-mcp's
      `Serving.only`: absent, not refused) and `--lean` sends terse
      schemas (first sentence, no per-argument prose); also
      `SYMDEX_TOOLS` / `SYMDEX_LEAN=1`.

| server | schema tokens per turn |
|---|---|
| all 10 tools | ~1 517 |
| all 10, `--lean` | ~790 |
| definition, references, source, outline | ~745 |
| the same four, `--lean` | ~335 |

Not carried over: dropping conversation turns is the client's (or a
gateway's) job, not a tool server's.

## Freshness

- [x] An index is a GENERATION: built whole, immutable, swapped in
      atomically under `.symdex/`. A query never sees a half-built index.
- [x] Building is incremental per `.semanticdb` file (its mtime and
      size); an unchanged file is not re-read.
- [x] Compiling is the trigger: before answering, the workspace looks
      at the SemanticDB on disk (at most every 2 s) and builds a new
      generation when it changed. No plugin, no hook (Decisions).
- [x] Every answer carries the generation's age and whether any source
      file is newer than it — a stale answer says so, never silently.

## Storage

v0 keeps the index in memory and persists nothing: on the okay slice
measured below, building it from SemanticDB takes about 0.4 s, which is
less than a JVM start. The okay-store-against-SQLite question stays
open and is decided by measurement when a repository's build makes a
restart cost something; until then a store is a cache with nothing to
save.

## The measure

symdex ships only what moves this number. Take real tasks from okay's own
history (e.g. "every caller of `Bulk.joinSorted`", "which modules does a
change to `Tables.scala` reach", "which `given Ordering` does this
`sortByKey` get"), run each with grep/Read only and with symdex, and
record tool calls, tokens and correctness (missed or false hits against
a hand-checked answer).

- [x] A task set of 10 with hand-checked answers: `bench/tasks.md`.
- [x] A results table per release in this spec's Results (v0 below).
- [x] The same tasks run by an AGENT, grep-only against symdex-only,
      counting tool calls and tokens (v0.4 Results; one run per arm).

## v0.4 (2026-10-02)

- [x] Exact enclosing definitions and bodies: TASTy (tasty-query 1.9.0
      — 1.6.1 stops at TASTy 28.7, Scala 3.7) read per class directory,
      joined to SemanticDB by source path and the name's position. On
      okay: 62 638 spans from 2 460 classes, none unreadable although
      Spark's and Flink's jars are not on symdex's classpath. Loaded per
      module on first use (`callers` reads only the modules its
      references are in); a server warms all of them in parallel.
- [x] Extension methods among `members`: TASTy flags them, SemanticDB's
      first parameter says on what. (`Fiber.isDone` under `okay.Fiber`.)
- [x] Other languages through SCIP: any `*.scip` under the root, read
      into the same model — descriptors as symbols (SCIP's descriptor
      syntax is SemanticDB's), kinds mapped, `is_implementation` as
      parents or overrides, enclosing ranges as spans. Java needs no
      SCIP at all: scip-java's javac plugin writes SemanticDB.
- [x] No sbt per call: `sbt stage` lays out jars and a script, which
      `bin/symdex` re-stages only when sources change (0.7 s a call).
- [x] `sbt-symdex`: one `addSbtPlugin` line turns SemanticDB on for the
      build; `symdexMcp` writes the server into `.mcp.json`.
- [x] `bin/symdex-hook`, a Claude Code PostToolUse hook for Bash and
      Grep: the context-diet rules, a digest computed from the output
      instead of written by a model (below).
- [x] Generations persisted under `.symdex/`, if a repository's build
      time ever makes a restart cost something (see Storage): measured
      in v0.6, and only TASTy's part is worth it.

## v0.5 (2026-10-02): the plugin and the distribution

- [x] sbt-symdex fetches symdex itself: the GitHub release of
      `symdexVersion`, unpacked once into `~/.symdex/<version>`, unless
      `symdexHome` names a checkout. A project needs no symdex checkout.
- [x] `symdexIndex`: this build's projects compiled, main and test, then
      `status` — the coverage question in one command.
- [x] `symdex <tool> key=value…` as an sbt shell command (a forked JVM:
      symdex is Scala 3, an sbt 1 plugin runs on 2.12).
- [x] `symdexMcp` / `symdexHook` merge into `.mcp.json` /
      `.claude/settings.local.json`; an existing entry is kept, a file
      that does not parse is left alone with the entry printed.
- [x] Published without Maven Central: the plugin as an Ivy repository on
      GitHub Pages, served from `docs/` on master (RELEASING.md).

Decisions:

- NOT run inside sbt: an MCP server speaks on the stdin/stdout of a process
  its client starts, and must outlive any sbt session. The plugin says how
  to start it; it does not start it.
- NO compile hook: symdex sees a compile from the SemanticDB it rewrites;
  a hook would add nothing to freshness. What a project lacked was
  COVERAGE (test SemanticDB exists only after `Test/compile`), hence
  `symdexIndex`.
- `symdexIndex` compiles THIS build's projects, not `inAnyProject`: run on
  symdex itself, the latter compiled every project of okay (a `ProjectRef`
  build) — 730 compiles across JVM, JS and Native (0.5.1).
- The hook goes to `.claude/settings.local.json`, not `settings.json`: its
  command is this machine's path.
- A broken plugin version is removed, never overwritten (resolvers cache by
  version): 0.5.1 was published unrun and withdrawn within the hour; the
  release checklist now runs every version first.

### The hook

`updatedToolOutput` (Claude Code's hooks reference: it replaces the
text output of Bash, Grep and other text tools) carries a digest; the
whole output goes to `.symdex/archive/<sha>.txt`, at most 200 kept. A
search's digest is its files with a count each and its first hits; a
log's is its head, tail and every error/fail/exception/warn line. Only
outputs of 3 500+ tokens, only when the digest saves 30%, only
searches/listings/test runs (an exact read, a diff, a pipe into `head`
or `sed` is left alone), never an output that looks like a credential
(it would be written to disk). A grep for an identifier adds the symdex
call that answers it exactly. A shell wrapper passes small outputs by
size before any JVM starts.

| command (over symdex's okay checkout) | output | digest |
|---|---|---|
| `rg -n joinSorted okay` | 14 676 chars | 4 902 |
| `rg -n Source okay/okay-stream/src` | 48 852 | 5 069 |
| `rg -n answered okay` | 167 409 | 4 745 |

## v0.6 (2026-10-03): saved TASTy spans

Storage's premise (a build of 0.4 s, nothing worth saving) was measured
before TASTy. A one-shot `symdex <tool>` (the CLI, the agent arm of
v0.4, the hook's suggestions) pays every start again, and on symdex's
own checkout (534 documents, 56 class directories) the start splits as
follows, in a fresh JVM, the machine under load (load average ~25):

| phase | cost |
|---|---|
| JVM + classes (`--help`) | 0.24 s |
| scan for SemanticDB | 0.14–0.29 s |
| parse every `.semanticdb` | 0.40–0.58 s |
| TASTy, one class directory (a `context` per directory: JDK + Scala library) | ~1 s |
| TASTy, all 56, sequential | 22–25 s |

| cold CLI call (median of 5) | before |
|---|---|
| `references query=Bulk.joinSorted` | 1.1 s |
| `references … callers=true` (TASTy for the modules referenced) | 2.2 s |
| `source query=Bulk.joinSorted` | 2.2 s |
| `members query=FlowBulk` (every module's TASTy, in parallel) | 6.0 s |

So what is worth saving is TASTy's reading, not SemanticDB's.

- [x] A class directory's spans, once read from TASTy, are written to
      `.symdex/tasty/`, one file per directory; a later process whose
      SemanticDB for that directory is unchanged reads the file instead
      of TASTy.
- [x] Unchanged means the same `.semanticdb` files by count, latest
      mtime and total size: a compile rewrites at least one, a deleted
      source removes one. Changed, the directory is read from TASTy
      again and its file rewritten. The in-memory check uses the same
      key.
- [x] A file that cannot be read (truncated, another format version,
      another directory's) is ignored and replaced; one that cannot be
      written is skipped. Neither fails a query.
- [x] Measured: the same cold CLI calls after (TASTy saved by one
      earlier call; the machine under heavier load, ~50).

| cold CLI call (median of 5) | before | after |
|---|---|---|
| `references query=Bulk.joinSorted` | 1.1 s | 0.96 s |
| `references … callers=true` | 2.2 s | 0.81 s |
| `source query=Bulk.joinSorted` | 2.2 s | 0.96 s |
| `members query=FlowBulk` | 6.0 s | 1.0 s |

The saved files: 56, 2.1 MB. The first call that reads a directory pays
TASTy once (`members` filling all 56: 6.8 s); after a compile touches a
module, its next call re-reads that one directory (1.3 s, then 0.86 s).
Every cold call is now the fixed ~1 s; whether that second is worth a
client-of-a-server CLI is the next measurement, not this one.

Refuted, measured:
- Saving the parsed documents. A prototype (a string table, then every
  document and span; 19.6 MB for this checkout) read back in 0.13–0.66 s
  against 0.40–0.58 s for parsing the protobuf: no gain, and a format to
  keep in step with `Document`.
- A store (okay's, SQLite) for it: each directory's spans are read whole
  or not at all; a file per directory is the whole need.
- Not now: the CLI as a client of a running server, which would also
  save the JVM, the scan and the parse (~1 s). Decided by measuring
  after this, if that second matters.

## What goes into okay

- MCP server features the tools need (structured results, pagination).
- The store, if okay's wins the storage measurement.
- Nothing about TASTy or SemanticDB — that is symdex's.

## Decisions

- TASTy over bytecode: bytecode erases what Scala users ask about
  (givens, extensions, opaque types, inline).
- SCIP for other languages instead of writing analyzers.
- Separate repository, okay as a submodule: symdex is a product on okay,
  like okay-chat; it must not grow okay's build.
- v0 reads SemanticDB ONLY. The draft paired it with TASTy; on the
  fixtures and on okay, SemanticDB alone answered all seven tools:
  occurrences give definitions and references, SymbolInformation gives
  kinds, properties (`given`), parents, declarations and overridden
  symbols, and Scala 3's Synthetics record which given each call site
  resolved (`render(1)` → `given_Show_Int`). TASTy is kept for what
  SemanticDB lacks — definition spans — and tasty-query was removed
  until that lane, rather than carried unused.
- The SemanticDB decoder is ~250 lines of our own protobuf reading
  rather than scalameta's semanticdb + scalapb: the schema is stable,
  only a dozen fields are read, unknown fields are skipped, and the
  fixtures pin it.
- Freshness without a hook: a compile rewrites the `.semanticdb` files,
  so their mtimes are the signal. The draft's sbt plugin is not needed
  for freshness; enabling SemanticDB is one setting
  (`semanticdbEnabled := true`, or `set every semanticdbEnabled := true`
  for one session).
- sbt 1.13 writes SemanticDB under `target/scala-<v>/meta` and
  `test-meta`, not beside the classes; both layouts are scanned. A
  cross-built source (JVM/JS/Native) is ONE document — the JVM copy is
  kept — and the build root's own module is named after the root
  (okay's core is `okay`, its JS twin `okay (js)`).
- Module dependencies are USES, read from references: what a change
  reaches is decided by code that actually names the changed symbols,
  not by `dependsOn`, which over-approximates (and is not in SemanticDB).
- Anonymous classes (`new Bulk[Chunks] { ... }`) are document-local
  symbols; they are kept per document, located by their members'
  definitions and named by the definition they sit in. Three of okay's
  six Bulk implementations are anonymous.
- A qualified name matches owners as a suffix first, then in order with
  gaps (`Tables.orderedBy` finds `okay/Tables.Plan.orderedBy().`).

## Results

### v0.4 (2026-10-02): agents, grep against symdex

The ten tasks of `bench/tasks.md`, without their answers, given to two
fresh agents of the same model over the same okay checkout. Arm A: grep,
rg, find and file reads only. Arm B: symdex's command line, file reads
allowed for checking, no grep. Graded against the hand-checked answers.
One run per arm, so read the numbers as a first measurement, not a mean.

| | grep (A) | symdex (B) |
|---|---|---|
| tool calls | 14 | 6 |
| tokens (whole agent, its fixed overhead included) | 103 000 | 76 000 |
| wall time | 119 s | 114 s |
| correct | 9 of 10, plus one reference symdex could not see | 9 of 10 |

Where they differed:
- task 6 (`Fiber#answered`): A found a second reference, in okay-resilience's
  tests, which is not in the indexed slice. An index answers for what was
  compiled with SemanticDB; `status` says what that is, and an agent
  should read it as the boundary of "every".
- task 10 (what a change to okay-stream reaches): A answered from build.sbt
  (declared dependencies), B from references (uses), as designed. B also
  flagged that every module seemed to use `compare`: a DEFECT, fixed the
  same hour — a `package okay` clause is a SemanticDB definition, and the
  first file declaring it (in compare) had become the package's home.

The first arm-B run answered nothing: it passed `--root=DIR`, which symdex
silently ignored, indexing the empty working directory. Fixed (both
spellings, and an unknown option is refused). Both defects were found
only by an agent using the tool, which is the argument for running it.

What the run shows: on the same answers, the symdex agent made fewer than
half the tool calls and used about a quarter fewer tokens. A strong model
with grep is accurate here — it reads around the noise — and pays for it
in calls and context; the gap should widen with weaker models and larger
answers, which this run does not measure.

### v0 (2026-10-01): answers, against grep, on okay

The index: okay's core, okay-stream, okay-cluster, okay-spark,
okay-flink and what they depend on, main and test, compiled with
SemanticDB in symdex's own okay submodule — 660 `.semanticdb` files,
512 documents after folding cross-builds, 47 modules, 54 549 symbols,
287 908 occurrences, built in 0.41 s. grep is `grep -nw <name>` over
exactly those 512 files. The tasks and their hand-checked answers are
`bench/tasks.md`.

| task | grep | symdex | |
|---|---|---|---|
| callers of `Bulk#joinSorted` | 53 lines; six different `joinSorted` methods | 2 refs, callers named | exact; grep leaves 51 lines to rule out |
| implementations of `Bulk` | hand regex: 17 lines, misses `SparkBulk` (`extends okay.Bulk`), 7 are `Bulk.Format` | 3 classes, 3 anonymous, 2 givens | all six; grep missed one |
| overriders of `Bulk#joinSorted` | among the 53 lines above | 1 named, 2 anonymous | exact |
| uses of `Tables.Plan.orderedBy` | 3 lines | 3 refs | equal: a unique name |
| uses of `Fiber.isDone` | 10 lines (ForkJoinTask's, prose) | 5 refs | grep 2x |
| uses of `Fiber#answered` | 236 lines (a common word) | 1 ref | grep unusable |
| which `Ordering` `sortByKey` gets, TestJoinStrategy:28 | not answerable | `Ordering.Int`, and `Row.In.left` for the row | only symdex |
| `FlowBulk`'s members, inherited too | two files to read and diff | 21 declared, 3 inherited defaults | |
| definitions named `sortByKey` | 4 `def` lines | 4, with doc comments | equal |
| what a change to okay-stream reaches | build.sbt by hand (declared) | 27 modules (used) | different questions; see Decisions |

What this does and does not show: on names that are unique grep is as
good; on names that are common (`answered`, `isDone`, `joinSorted`) or
on questions about types (implementations, givens) it is not, and its
misses are silent. It does NOT yet show that an agent finishes tasks
in fewer calls or tokens — that is the open box above.
