//> using scala 3.7.4
//> using toolkit default

// THE ARMS (README.md): `tool` — symdex's `equivalent` alone; `alone` — okay-agent with symdex's structural tools;
// `with` — okay-agent with `equivalent` too. `run.scala <arm> [ids: 1,2,5-9] [--dir /tmp/eqbench]`. Each verdict
// against the truth, each counterexample run by verify.scala, every row appended to history.tsv.

val here = os.Path(sys.props.getOrElse("verify.dir", os.pwd.toString))
val symdex = sys.env.getOrElse("SYMDEX", (os.home / "work/my/symdex/target/symdex/bin/symdex").toString)
val url = sys.env.getOrElse("OKAY_AGENT_URL", "http://127.0.0.1:8090/v1")
val model = sys.env.getOrElse("OKAY_AGENT_MODEL", "mlx-community/Qwen3.6-35B-A3B-4bit")
val structural = "definition,references,implementations,givens,members,modules,status,source,outline,more,query"

final case class Case(id: Int, file: String, definition: String, truth: String, kind: String)
final case class Verdict(safe: Option[Boolean], input: String, seconds: Double, note: String)

def cases: Vector[Case] = os.read.lines(here / "cases.tsv").drop(1).map(_.split("\t", -1))
  .map(c => Case(c(0).toInt, c(1), c(2), c(3), c(4))).toVector

def ids(spec: String): Set[Int] = spec.split(",").flatMap { p =>
  p.split("-") match
    case Array(a, b) => a.toInt to b.toInt
    case Array(a) => Seq(a.toInt)
}.toSet

/** the definition's number of parameters, read from its file: top-level commas between its parentheses */
def arity(c: Case): Int =
  val name = c.definition.split('.').last
  val text = os.read(here / "after" / s"${c.file}.scala")
  val from = text.indexOf(s"def $name(") + s"def $name(".length
  var depth = 0
  var i = from
  var commas = 0
  while depth >= 0 do
    text(i) match
      case '(' | '[' => depth += 1
      case ')' | ']' => depth -= 1
      case ',' if depth == 0 => commas += 1
      case _ => ()
    i += 1
  if i - 1 == from then 0 else commas + 1

/** arguments as a call takes them: a tuple of several written around them unwrapped */
def unwrap(in: String, n: Int): String =
  val t = in.trim.stripSuffix(".")
  if n < 2 || !t.startsWith("(") then t
  else
    var depth = 0
    val close = t.indices.find { i =>
      if t(i) == '(' then depth += 1 else if t(i) == ')' then depth -= 1
      depth == 0
    }
    if close.contains(t.length - 1) then t.substring(1, t.length - 1) else t

def tool(dir: os.Path, c: Case): Verdict =
  val t0 = System.nanoTime
  val r = os.proc(symdex, "equivalent", "--root", dir, s"query=${c.definition}", "format=json")
    .call(check = false, stderr = os.Pipe)
  val s = (System.nanoTime - t0) / 1e9
  val out = r.out.text().trim
  ujson.read(out) match
    case j if j.obj.get("equivalent").contains(ujson.True) => Verdict(Some(true), "", s, "")
    case j if j.obj.contains("input") => Verdict(Some(false), j("input").str, s, "")
    case _ => Verdict(None, "", s, out.take(200))

def question(c: Case): String =
  val name = c.definition.split('.').last
  s"The working tree changes `def $name` in `src/main/scala/app/${c.file}.scala` (see `git diff -- " +
    s"src/main/scala/app/${c.file}.scala`). Is the change behaviour-preserving: the same result, or the same " +
    "exception, for every input (null is not an input)? Do not edit any file. End your answer with one line: " +
    "`VERDICT: safe`, or " +
    "`VERDICT: unsafe INPUT: <ONE input: its arguments, as Scala expressions, comma-separated>`."

val VerdictLine = """(?s).*VERDICT:\s*\**\s*(safe|unsafe)\b(.*)""".r
val InputPart = """(?s).*?INPUT:\s*`?([^`\n]*)`?.*""".r

def agent(dir: os.Path, c: Case, withEquivalent: Boolean): Verdict =
  val tools = if withEquivalent then structural + ",equivalent" else structural
  os.write.over(dir / ".mcp.json", ujson.Obj("mcpServers" -> ujson.Obj("symdex" -> ujson.Obj(
    "command" -> symdex, "args" -> ujson.Arr("serve", "--root", dir.toString, "--tools", tools, "--lean")))).render(2))
  val t0 = System.nanoTime
  val r = os.proc("okay-agent", "--url", url, "--model", model, "--turns", "30", "--yes", question(c))
    .call(cwd = dir, check = false, stderr = os.Pipe, timeout = 900000, env = Map("OKAY_AGENT_NO_INSTALL" -> "1"))
  val s = (System.nanoTime - t0) / 1e9
  val out = r.out.text()
  val journal = r.err.text().linesIterator.find(_.startsWith("journal: ")).map(_.stripPrefix("journal: "))
  out match
    case VerdictLine(v, rest) =>
      val in = rest match
        case InputPart(i) => i.trim
        case _ => ""
      Verdict(Some(v == "safe"), in, s, journal.getOrElse(""))
    case _ => Verdict(None, "", s, s"no verdict (exit ${r.exitCode}) ${journal.getOrElse("")}")

/** the working tree as the bench left it: `after/` over the sources, anything new removed; recompiled if it changed */
def restore(dir: os.Path): Unit =
  val first = os.proc("git", "rev-list", "--max-parents=0", "HEAD").call(cwd = dir).out.trim()
  if os.proc("git", "rev-parse", "HEAD").call(cwd = dir).out.trim() != first then
    println("  (the agent committed: HEAD put back)")
  // the index as setup left it (nothing staged: `git diff` shows the change), anything new outside the ignored removed
  os.proc("git", "reset", "-q", first).call(cwd = dir)
  os.proc("git", "clean", "-fdq", "-e", ".mcp.json").call(cwd = dir)
  val src = dir / "src" / "main" / "scala" / "app"
  val changed = os.list(here / "after").exists(f => !os.exists(src / f.last) || os.read(src / f.last) != os.read(f)) ||
    os.list(src).exists(f => !os.exists(here / "after" / f.last))
  if changed then
    println("  (the agent changed the sources: restored, reindexed)")
    os.list(src).filter(f => !os.exists(here / "after" / f.last)).foreach(os.remove(_))
    for f <- os.list(here / "after") do os.copy.over(f, src / f.last)
    os.proc("sbt", "-batch", "symdexIndex").call(cwd = dir, stdout = os.Pipe, stderr = os.Pipe)

@main def run(args: String*): Unit =
  val arm = args.headOption.getOrElse(sys.error("an arm: tool, alone, with"))
  val dir = os.Path(args.sliding(2).collectFirst { case Seq("--dir", d) => d }.getOrElse("/tmp/eqbench"), os.pwd)
  val only = args.drop(1).filterNot(_.startsWith("--")).filterNot(_ == dir.toString).headOption.map(ids)
  val chosen = cases.filter(c => only.forall(_(c.id)))
  val stamp = java.time.LocalDateTime.now.withNano(0).toString
  val history = here / "history.tsv"
  if !os.exists(history) then
    os.write(history, "when\tarm\tmodel\tid\ttruth\tverdict\tinput\tcounterexample\tseconds\tnote\n")
  // each case judged and written as it ends: a run stopped halfway keeps what it did
  val results = chosen.map { c =>
    val v = arm match
      case "tool" => tool(dir, c)
      case "alone" => try agent(dir, c, false) finally restore(dir)
      case "with" => try agent(dir, c, true) finally restore(dir)
      case other => sys.error(s"no arm $other")
    val shown = v.safe.fold("none")(if _ then "safe" else s"unsafe ${v.input}")
    val mark = if v.safe.contains(c.truth == "safe") then "ok" else "WRONG"
    println(f"${c.id}%2d ${c.definition}%-22s truth ${c.truth}%-6s → $shown%-40s $mark%5s ${v.seconds}%.0f s ${v.note}")
    val judged = if v.safe.contains(false) && v.input.nonEmpty then judge(c.id, first(unwrap(v.input, arity(c)), arity(c))) else "-"
    os.write.append(history, Seq(stamp, arm, if arm == "tool" then "-" else model, c.id, c.truth,
      v.safe.fold("none")(if _ then "safe" else "unsafe"), v.input, judged, f"${v.seconds}%.1f", v.note)
      .mkString("\t") + "\n")
    (c, v, judged)
  }
  val unsafe = results.filter(_._1.truth == "unsafe")
  val safe = results.filter(_._1.truth == "safe")
  val right = results.count((c, v, _) => v.safe.contains(c.truth == "safe"))
  val falseSafe = unsafe.count(_._2.safe.contains(true))
  val falseUnsafe = safe.count(_._2.safe.contains(false))
  val valid = results.count(_._3 == "differ")
  val unsafeSaid = results.count(_._2.safe.contains(false))
  println(s"\n$arm: right ${right} of ${results.size}; false safe $falseSafe of ${unsafe.size} unsafe; " +
    s"false unsafe $falseUnsafe of ${safe.size} safe; no verdict ${results.count(_._2.safe.isEmpty)}; " +
    s"counterexamples valid $valid of $unsafeSaid unsafe verdicts; " +
    f"median ${results.map(_._2.seconds).sorted.apply(results.size / 2)}%.0f s")

/** one counterexample run on both versions (verify.scala): differ, same, or invalid */
def judge(id: Int, input: String): String =
  val answers = os.temp(s"$id\t$input")
  os.proc("scala-cli", "run", "--server=false", here / "verify.scala", "--", "--answers", answers)
    .call(cwd = here, stderr = os.Pipe, check = false).out.lines().map(_.split("\t", -1))
    .collectFirst { case p if p.length > 1 => p(1) }.getOrElse("invalid")

/** the first of several inputs an answer lists (`(a, b), (c, d)`, or `x; y`): a counterexample is one input */
def first(in: String, arity: Int): String =
  val t = in.trim
  // one parameter: its first top-level comma ends the first input (`Some(""), Some("a")`)
  def topComma(s: String) =
    var depth = 0
    s.indices.find { i =>
      s(i) match
        case '(' | '[' | '{' => depth += 1
        case ')' | ']' | '}' => depth -= 1
        case _ => ()
      depth == 0 && s(i) == ','
    }
  if arity == 1 && !t.startsWith("\"") then return topComma(t).fold(t)(i => t.take(i).trim)
  if t.startsWith("(") then
    var depth = 0
    val close = t.indices.find { i =>
      if t(i) == '(' then depth += 1 else if t(i) == ')' then depth -= 1
      depth == 0
    }
    close.filter(_ < t.length - 1).filter(i => t.drop(i + 1).trim.startsWith(","))
      .fold(t)(i => t.substring(1, i))
  else t.split(";").head.trim
