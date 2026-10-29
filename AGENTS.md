# Working in this repository (agents)

- **On the released effects libraries** (spec v0.7): okay-scala for symdex's
  effects, okay-simple's JSON value, okay-mcp for the server, from their
  maven repositories. The data model is effects, implementations are
  handlers, a program runs in one line: a tool is a program over `Code`,
  `Archive` and `Clock`, and nothing in it reaches the disk, the compiler's
  output or the clock but through them. What is general (an MCP feature, a
  codec) goes into those libraries; symdex holds only what is symdex's: the
  indexers, the schema, the tools.
- **Spec first** (`specs/`): a feature's spec is written and committed
  before its code; behaviour boxes are checked off as tests cover them;
  refuted alternatives are recorded in Decisions/Results.
- **No warnings** (`-Werror` in build.sbt), **no casts without necessity**,
  **no unbounded stack recursion** — okay's code rules hold here too.
- **Measure what is claimed.** symdex's worth is measured on real agent
  tasks against grep (specs/symdex.md, "The measure"); a tool that does not
  move that number does not ship.
- Gate: `sbt test`.
