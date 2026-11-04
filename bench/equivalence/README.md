# The equivalence benchmark (symdex v0.9, Measure 1)

Does an agent tell a behaviour-preserving change from a silent break better with `equivalent` than without it?

## The corpus

`before/` and `after/`: one Scala file per case, an object with one function, the same file in both — the change is
the case. Functions in the style of this machine's code (several taken from symdex and okay-guard: `quote`,
`relative`, `indent`), pure, of parameter types `equivalent` generates. `cases.tsv`: per case its file, object and
definition, the truth (`safe` / `unsafe`), the kind of change, and for an unsafe one a witness — arguments on which
the two differ.

Kinds: safe refactorings that look risky (a fold for a sum, `foldRight` for `foldLeft` where the operation is
associative and commutative, De Morgan, `Option.when`, pigeonhole bounds) and breaks that look safe (`None` vs empty,
`head` vs `headOption`, a for-comprehension that drops a `None` branch, `foldLeft` vs `foldRight` on a
non-commutative operation, `Set` vs `List` order, strictness, overflow, the order of checks, map priority, stable
sorting, rounding, punctuation the generators never produce).

The truth by construction: a safe change argued in `cases.tsv`'s note; an unsafe one's witness RUN — `verify.scala`
compiles both versions side by side (packages `old` / `now`) and checks that each witness tells them apart (an
exception compared by its class). The same program judges an agent's counterexample.

## The project

`setup.scala` (scala-cli) makes `/tmp/eqbench` (or a directory given): an sbt project with sbt-symdex, `before/`
committed, `after/` in the working tree — every case's change uncommitted at once, each asked about separately —
compiled with SemanticDB and indexed (`sbt symdexIndex`, which writes the classpath `equivalent` needs).

## The arms

`run.scala <arm> [cases]`, each case a fresh `okay-agent --yes` in the project (Qwen3.6-35B-A3B through okay-mlx's
gateway), the same question:

> The working tree changes `def <name>` in `<file>` (`git diff -- <file>`). Is the change behaviour-preserving: the
> same result, or the same exception, for every input? Do not edit files. End with one line: `VERDICT: safe`, or
> `VERDICT: unsafe INPUT: <the arguments, as Scala expressions, comma-separated>`.

| Arm | What |
|---|---|
| `tool` | `symdex equivalent query=<Object.name>` alone, no model — the tool's own accuracy (its ceiling and its misses) |
| `alone` | okay-agent with symdex's structural tools but not `equivalent` (`.mcp.json`: `serve --tools …`); it has `bash`, so it may run code itself |
| `with` | okay-agent with symdex including `equivalent` |

(`with` + okay-guard's rule — a commit only after `equivalent` passed — belongs to Measure 4, where agents commit.)

## Metrics

- accuracy (the verdict against the truth);
- **false "safe"** — an unsafe change called safe: the dangerous error;
- false "unsafe" — a safe change called unsafe (any input given is then wrong by construction);
- counterexamples valid — an unsafe verdict whose INPUT `verify.scala` confirms;
- time per case, turns.

`history.tsv` keeps every run, appended to.

## Results

2026-11-04, Qwen3.6-35B-A3B-4bit on okay-agent 0.1.0 (okay-mlx gateway), one run per case, 900 s at most:

| arm | right | false safe | false unsafe | no verdict | counterexamples valid | median |
|---|---|---|---|---|---|---|
| tool, before the fixes | 39 / 43 | 2 / 24 | 0 / 19 | 2 | 21 / 21 | 2 s |
| tool, after (bd5c8b6) | 43 / 43 | 0 | 0 | 0 | 24 / 24 | 2 s |
| alone | 41 / 43 | 0 | 2 | 0 | 20 / 26 | 72 s |
| with | 41 / 43 | 0 | 0 | 2 | 20 / 23 | 79 s |

- The tool arm found symdex's own defects first: a braces body cut at its closing `}` (no answer on two cases);
  strings over `a`–`c` only and BigDecimals of two places (two false "safe": `@` and `0.005`). Fixed; the corpus
  drove the generators' widening, so the 43 / 43 is partly fitted to it — the alphabet is general (the separators
  code splits and escapes by), not the cases' inputs.
- **No false "safe" in any arm**: on functions of one line, the model with `bash` checks by hand and is careful. The
  corpus is too easy to show the tool's main value; the difference is in the false "unsafe": alone it claimed two
  safe changes broken (Escape: `"\\\""`, which run gives the same; Codes: `null`) — with the tool, none.
- Counterexamples: of alone's six not confirmed, three listed several inputs (the format, not the logic), one did not
  parse, one was wrong (`List("b", "a", "b")` for `Set` vs `List` order: a Set of three keeps the order), one was on
  a safe case; with the tool all three not confirmed listed several inputs — every single one given ran true.
- The two "no verdict" with the tool: Escape — `equivalent` answered "no difference" three times, the model did not
  trust it and wrote its own checks with `bash` until the time ran out; Words — it never called `equivalent`, and
  fought a scala-cli installation instead. Trust and reach, not the tool's answers.

Next (a harder corpus, Measure 4's spirit): functions of 10–40 lines, breaks that only a combination of edge values
shows (several parameters at their edges at once, nested Option/Either, maps keyed by case classes); and the prompt
saying what `equivalent`'s "no difference" covers, so the model stops re-checking it by hand.
