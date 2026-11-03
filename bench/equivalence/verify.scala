//> using scala 3.7.4
//> using toolkit default

// THE TRUTH, RUN: both versions of every case compiled side by side (packages `old` and `now`), each input evaluated
// on both, results compared as `==` sees them (strings quoted, maps and sets by their elements, sequences of any kind
// alike), an exception by its class. With no arguments, cases.tsv's witnesses; with `--answers <file>`, lines
// `<id>\t<input>` (an agent's counterexamples). Prints `<id>\t<differ|same|invalid>\t<old>\t<now>`.

val here = os.Path(sys.props.getOrElse("verify.dir", os.pwd.toString))

def cases: Map[String, (String, String)] =
  os.read.lines(here / "cases.tsv").drop(1).map(_.split("\t", -1)).map(c => c(0) -> (c(2), c(5))).toMap

val show = """
def s(x: Any): String = x match
  case x: String => "\"" + x.flatMap { case '"' => "\\\""; case '\\' => "\\\\"; case '\n' => "\\n"; case c => c.toString } + "\""
  case c: Char => "'" + c + "'"
  case m: collection.Map[?, ?] => m.map((k, v) => s(k) + " -> " + s(v)).toList.sorted.mkString("Map(", ", ", ")")
  case m: collection.Set[?] => m.toList.map(s).sorted.mkString("Set(", ", ", ")")
  case m: collection.Seq[?] => m.map(s).mkString("Seq(", ", ", ")")
  case p: Product if p.productArity > 0 => p.productPrefix + p.productIterator.map(s).mkString("(", ", ", ")")
  case other => String.valueOf(other)
def o(f: => Any): String = try "= " + s(f) catch case e: Throwable => "throws " + e.getClass.getName
"""

def program(inputs: Seq[(String, String)], defs: Map[String, (String, String)]): String =
  val lines = inputs.map { (id, in) =>
    val d = defs(id)._1
    s"""  println("$id\\t" + o({ import old.*; old.$d($in) }) + "\\t" + o({ import now.*; now.$d($in) }))"""
  }
  show + "@main def verify(): Unit =\n" + lines.mkString("\n") + "\n"

def run(inputs: Seq[(String, String)], defs: Map[String, (String, String)]): Either[String, Seq[String]] =
  val dir = os.temp.dir(prefix = "eqverify")
  for (from, pkg) <- List("before" -> "old", "after" -> "now"); f <- os.list(here / from) do
    os.write(dir / pkg / f.last, os.read(f).replaceFirst("package app", s"package $pkg"), createFolders = true)
  os.write(dir / "Main.scala", program(inputs, defs))
  val r = os.proc("scala-cli", "run", "--server=false", "-S", "3.7.4", dir).call(check = false, stderr = os.Pipe)
  if r.exitCode != 0 then
    Left(r.err.text().replaceAll("\u001b\\[[0-9;]*m", "").linesIterator.filter(_.contains("Error")).take(3)
      .mkString(" | "))
  else Right(r.out.lines())

def judge(line: String): String =
  val p = line.split("\t", -1)
  s"${p(0)}\t${if p(1) != p(2) then "differ" else "same"}\t${p(1)}\t${p(2)}"

@main def main(args: String*): Unit =
  val defs = cases
  val inputs: Seq[(String, String)] = args.toList match
    case "--answers" :: file :: _ =>
      os.read.lines(os.Path(file, os.pwd)).filter(_.contains("\t")).map(_.split("\t", 2)).map(a => a(0) -> a(1))
    case _ => defs.toSeq.filter(_._2._2 != "-").map((id, d) => id -> d._2).sortBy(_._1.toInt)
  run(inputs, defs) match
    case Right(out) => out.filter(_.count(_ == '\t') == 2).map(judge).foreach(println)
    case Left(_) => // one input that does not compile spoils the rest: each on its own
      inputs.foreach(i => run(Seq(i), defs) match
        case Right(out) => out.filter(_.count(_ == '\t') == 2).map(judge).foreach(println)
        case Left(why) => println(s"${i._1}\tinvalid\t$why\t"))
