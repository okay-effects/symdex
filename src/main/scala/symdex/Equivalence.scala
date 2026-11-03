package symdex

/**
 * EQUIVALENCE, by running (specs/symdex.md, v0.9): a definition now against itself at a git revision, or against
 * another definition — both called from a generated program, on every input up to a small size and on random ones,
 * their inputs generated from the parameters' types (the compiler derives the generators: `Check.scala`, a resource).
 * Pure: what to run is decided here, the running is `Code.execute`'s.
 */
object Equivalence:
  /** the generators and the comparison, put beside every program */
  lazy val checkSource: String =
    val in = getClass.getResourceAsStream("/symdex/Check.scala.txt")
    try String(in.readAllBytes(), "UTF-8") finally in.close()

  /** a definition that can be called from a program: a method of an object (or a top-level one), of one parameter
   * list of at most three parameters, not overloaded */
  final case class Target(symbol: String, pkg: String, owner: String, name: String, arity: Int):
    /** its path as Scala writes it: `fixture.Use.total` */
    def path: String = (if pkg.isEmpty then "" else pkg + ".") + (if owner.isEmpty then name else s"$owner.$name")

  /** symbols that read or write the world: a definition referring to one is not a function of its parameters */
  val impure: Vector[String] = Vector("java/io/", "java/nio/", "java/net/", "scala/io/", "scala/Console",
    "scala/Predef.println", "scala/Predef.print", "scala/Predef.readLine", "scala/util/Random", "java/util/Random",
    "java/lang/System#", "java/lang/System.", "scala/concurrent/", "java/util/concurrent/", "java/time/")

  def target(ix: Index, s: String): Either[String, Target] =
    ix.infos.get(s) match
      case None => Left(s"$s: not a definition in the index")
      case Some(i) if i.kind != Info.Method => Left(s"$s is a ${Info.describe(i)}, not a method")
      case Some(i) =>
        // `pkg/Obj.name().`: a package by `/`, objects by `.`, a class's member by `#` (needs an instance: refused)
        val body = s.stripSuffix(".")
        if !body.endsWith("()") then Left(s"$s is overloaded or has a type in its name: name the one to compare")
        else
          val withoutParams = body.stripSuffix("()")
          val slash = withoutParams.lastIndexOf('/')
          val pkg = if slash < 0 then "" else withoutParams.substring(0, slash).replace('/', '.')
          val rest = withoutParams.substring(slash + 1)
          if rest.contains('#') then Left(s"$s is a member of a class: it needs an instance (methods of objects only)")
          else
            val parts = rest.split('.').toVector.filter(_.nonEmpty)
            val owner = parts.init.filterNot(_ == "package").mkString(".")
            if i.params.size > 3 then Left(s"$s has ${i.params.size} parameters: at most 3 are compared")
            else Right(Target(s, pkg, owner, parts.last, i.params.size))

  /**
   * A definition's text in a file's text: the line with `def <name>` inside `object <owner>` and the lines after it
   * that are indented deeper (or blank) — Scala 3's indentation, and braces bodies written indented. None when it is
   * not there, or there are several (overloads).
   */
  def definitionIn(text: String, owner: String, name: String): Either[String, String] =
    val lines = text.linesIterator.toVector
    def indent(l: String) = l.takeWhile(_ == ' ').length
    val ownerName = owner.split('.').lastOption.getOrElse("")
    val from =
      if ownerName.isEmpty then 0
      else lines.indexWhere(l => l.trim.matches(s"""(.*\\s)?object\\s+$ownerName\\b.*"""))
    if from < 0 then Left(s"no object $ownerName in that version")
    else
      val Def = s"""(\\s*)(.*\\s)?def\\s+$name\\b.*""".r
      val starts: Vector[Int] = lines.indices.drop(from).filter(i => Def.matches(lines(i))).toVector
      starts match
        case Vector() => Left(s"no def $name in that version")
        case Vector(start) =>
          val base = indent(lines(start))
          // the body: deeper lines (or blank), and the lines at the def's own indentation that close its brackets
          def inBody(l: String) = l.trim.isEmpty || indent(l) > base
          def closing(l: String) = indent(l) == base && l.trim.headOption.exists(c => c == '}' || c == ')' || c == ']')
          val body = lines.drop(start + 1).foldLeft((Vector.empty[String], true)) { case ((got, open), l) =>
            if open && (inBody(l) || closing(l)) then (got :+ l, true) else (got, false)
          }._1
          val all = (lines(start) +: body).reverse.dropWhile(_.trim.isEmpty).reverse
          Right(all.map(l => if l.trim.isEmpty then "" else "  " + l.drop(base)).mkString("\n"))
        case _ => Left(s"def $name is overloaded in that version: compare one by another definition")

  /** a file's package clauses and imports, as a program beside it needs them */
  def header(text: String): Vector[String] =
    text.linesIterator.map(_.trim).filter(l => l.startsWith("package ") || l.startsWith("import ")).toVector

  /** the program: the old definition (if from a revision) in an object of its own in the same package, its
   * owner's members imported (a helper it calls is the current one), and the comparison printed as JSON */
  def harness(t: Target, header: Vector[String], old: Either[String, Target], size: Int, trials: Int): String =
    val (oldObject, oldPath) = old match
      case Left(defText) =>
        val imp = if t.owner.isEmpty then "" else s"  import ${t.path.stripSuffix("." + t.name)}.*\n"
        (s"object SymdexOld:\n$imp$defText\n", s"SymdexOld.${t.name}")
      case Right(other) => ("", other.path)
    val call = t.arity match
      case 0 => s"compare0(${t.path}, $oldPath)"
      case n => s"compare$n(${t.path}, $oldPath, $size, $trials)"
    (header :+ "" :+ oldObject :+ s"@main def symdexEquivalent(): Unit = println(okay.symdex.check.Check.$call)")
      .mkString("\n") + "\n"

  /** the comparison's answer, read from the program's output (its last line) */
  final case class Verdict(equivalent: Boolean, input: String, now: String, before: String, found: String,
                           small: Int, random: Int, size: Int)

  def verdict(out: String): Either[String, Verdict] =
    out.linesIterator.toVector.reverse.find(_.startsWith("{")).toRight("no answer from the program")
      .flatMap(l => okay.effects.json.Descent.parse(l).left.map(e => s"the program's answer does not read: $e"))
      .map { j =>
        import okay.effects.json.Json
        def s(k: String) = j.field(k).collect { case Json.Str(v) => v }.getOrElse("")
        def n(k: String) = j.field(k).collect { case Json.Num(v) => v.toString.toDouble.toInt }.getOrElse(0)
        Verdict(j.field("equivalent").contains(Json.Bool(true)), s("input"), s("new"), s("old"), s("found"),
          n("small"), n("random"), n("size"))
      }
