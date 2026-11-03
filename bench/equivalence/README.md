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
