# Changelog

Two things are versioned: **symdex** (the server and the command line,
released as a zip on GitHub) and **sbt-symdex** (the sbt plugin, served from
GitHub Pages). A plugin release names the symdex release it downloads;
the plugin's own version may run ahead of it.

## sbt-symdex 0.6.0 — 2026-10-29

- Downloads symdex 0.6.0 (on effects).

## symdex 0.6.0 — 2026-10-29

- The zip keeps the scripts executable (`bin/symdex`, `bin/symdex-hook`,
  `rwxr-xr-x`): unzipped by hand, it runs as it is. 0.5.0 and the first
  0.6.0 zip lost the mode, and only the plugin, which sets it, ran them.
- On effects (specs/symdex.md, v0.7). Every tool is a program over `Code`
  (the codebase as compiled), `Archive` and `Clock`; `Code`'s handler is a
  program over `Disk`, `Tasty` and `Clock`. Nothing reaches the disk, the
  compiler's output or the clock but through them. The released libraries
  replace the okay submodule: okay-scala, okay-simple's JSON, okay-mcp's
  server. Every answer is the same as before on symdex's own code; new
  tests run the tools against an index given and the index's building
  against files in memory. A source's lines are read again when it changed
  (they were kept for the server's lifetime).
- TASTy spans are saved in `.symdex/tasty`, one file per class directory,
  and reused by the next process while that module's SemanticDB is
  unchanged. A cold command-line `callers` goes from 2.2 s to 0.8 s,
  `members` from 6.0 s to 1.0 s (specs/symdex.md, v0.6). `status` says
  how many directories came from there.

## sbt-symdex 0.5.2 — 2026-10-02

- Fix: `symdexIndex` failed at run time ("References to undefined settings
  at runtime", `streams`): the dynamic task read `streams` inside its inner
  task. It reads everything it needs outside it now.
- Downloads symdex 0.5.0.

## sbt-symdex 0.5.1 — 2026-10-02 — withdrawn

- `symdexIndex` compiles only the projects of the build it runs in. It took
  every project of a build referenced by `ProjectRef` as well: on symdex
  itself, 730 compiles of okay's JVM, JS and Native projects before the
  first of symdex's.
- Published without being run, and broken at run time; removed from the
  repository the same hour. Use 0.5.2.

## symdex 0.5.0 and sbt-symdex 0.5.0 — 2026-10-02

- The release zip carries `bin/symdex-hook` beside `bin/symdex`.
- sbt-symdex fetches symdex itself: the GitHub release of `symdexVersion`,
  downloaded once into `~/.symdex/<version>`, or the checkout `symdexHome`
  (`SYMDEX_HOME`) names.
- `symdexIndex`: compile with tests, then print `status`.
- `symdex <tool> key=value…` as an sbt shell command.
- `symdexMcp` and `symdexHook` MERGE into `.mcp.json` and
  `.claude/settings.local.json` (an existing entry is kept, a file that does
  not parse is left alone).
- The plugin is served as an Ivy repository from GitHub Pages (`docs/` on
  master): a build needs one resolver line, no Maven Central.

## symdex 0.4.0 — 2026-10-02

- **TASTy** (tasty-query 1.9.0): exact body spans, so `callers` names the
  right enclosing definition and `source` returns exactly one body;
  extension methods listed by `members`. Read per module on first use; a
  server warms every module in parallel at start.
- **SCIP**: any `*.scip` under the root is read into the same model, its
  enclosing ranges as spans.
- **Java** works through the SemanticDB scip-java's javac plugin writes.
- **The hook** (`symdex hook`, `bin/symdex-hook`): a Claude Code
  PostToolUse hook replacing large grep/test outputs by an exact digest,
  the whole output archived.
- **Distribution**: `sbt stage` and `sbt dist` (a zip that runs without
  sbt); the first sbt-symdex (SemanticDB on, `symdexMcp`).
- Fix: `--root=DIR` was silently ignored and the working directory indexed
  instead (found by an agent benchmark); an unknown option is now an error.
- Fix: a package was counted as a module dependency: every module declaring
  `package okay` "defined" it, so every module appeared to use whichever
  declared it first.
- First agent benchmark: 6 against 14 tool calls, 76K against 103K tokens,
  the same answers (specs/symdex.md, Results).

## 0.3 — 2026-10-02

- Every answer's first line carries its cost (`~N tokens`).
- `status` prices the tool schemas, full and lean.
- `serve --tools a,b` and `--lean` (`SYMDEX_TOOLS`, `SYMDEX_LEAN`).

## 0.2 — 2026-10-02

- `outline`, `source` and `more`.
- Answers over a budget are compressed from themselves and archived whole;
  exact reads are paged. `references in=…`.

## 0.1 — 2026-10-01

- The first seven tools over SemanticDB — `definition`, `references`,
  `implementations`, `givens`, `members`, `modules`, `status` — served over
  okay's MCP server; an index rebuilt whenever a compile rewrites the
  SemanticDB. (Started as "loupe", renamed the same day.)
