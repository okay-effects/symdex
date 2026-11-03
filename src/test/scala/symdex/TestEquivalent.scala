package symdex

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock

import java.nio.file.{Files, Path}

/** `equivalent` (specs/symdex.md, v0.9): the fixtures' refactorings run for real (scala-cli, the tests' classpath) */
class TestEquivalent extends munit.FunSuite:
  type Asked[X] = (Clock + Archive + Code)[X]

  private val real = Symdex.Live(Path.of("."), include = f => f.module == "symdex:test")
  private val g = real.run(Code.current[Symdex.Asked])
  private val refactor = g.index.entries.find(_.file == "src/test/scala/fixture/Refactor.scala").get
  // the fixtures' own classes (they need only the Scala library, which scala-cli adds)
  private val built = Built(Vector(Tasty.classDirOf(refactor.db.root).toAbsolutePath), "3.9.0")

  /** a program run as Code.compiled runs it: its files in a directory, scala-cli against the build */
  private def execute(p: Program): Either[String, String] =
    val dir = Files.createTempDirectory("symdex-equivalent")
    p.files.foreach((n, t) => Files.writeString(dir.resolve(n), t))
    Exec.process(List("scala-cli", "run", "--server=false", "-S", p.built.scalaVersion, "--classpath",
      p.built.classpath.mkString(java.io.File.pathSeparator)) ++ p.files.map(_._1), dir, p.timeoutMs) match
      case Left(why) => Left(why)
      case Right(r) if r.code == 0 => Right(r.out)
      case Right(r) => Left(r.err)

  private def ask(query: String, extra: (String, Json)*)(using revisions: Map[(Path, String), String] = Map.empty,
                                                         cp: Option[Built] = Some(built)): String =
    run(Clock.virtual(5000)(Archive.memory(Archive.Store())(Code.fixed(Path.of("/given"), g.index, built = cp,
      revisions = revisions, execute = execute)(Tools.equivalent[Asked](
        Json.obj((("query" -> Json.Str(query)) +: extra)*))))))._2

  test("a refactoring that keeps behaviour: no difference found, and what was covered"):
    val out = ask("Refactor.totalFold", "against" -> Json.Str("Refactor.total"))
    assert(out.contains("no difference from Refactor.total found"), out)
    assert(out.contains("1000 random"), out)

  test("a refactoring that breaks it: the counterexample, both results"):
    val out = ask("Refactor.totalFirst", "against" -> Json.Str("Refactor.total"))
    assert(out.contains("NOT equivalent to Refactor.total — on Some(List("), out)
    val strict = ask("Refactor.firstBigStrict", "against" -> Json.Str("Refactor.firstBig"))
    assert(strict.contains("NOT equivalent"), strict)
    assert(strict.contains("Line(\"\", 0)"), strict)                         // a case class, generated

  test("against a git revision: the old definition read from that version's file"):
    val source = refactor.source
    val text = Files.readString(source)
    given Map[(Path, String), String] = Map(
      (source, "HEAD") -> text.replace("xs.map(_.sum).getOrElse(0)", "xs.fold(0)(_.sum)"),
      (source, "HEAD~1") -> text.replace("xs.map(_.sum).getOrElse(0)", "xs.flatMap(_.headOption).getOrElse(0)"))
    assert(ask("Refactor.total").contains("no difference from HEAD found"))
    assert(ask("Refactor.total", "against" -> Json.Str("HEAD~1")).contains("NOT equivalent to HEAD~1"))

  test("refused, saying why: not pure, a class's member, no classpath; json as data"):
    assert(ask("Refactor.greet", "against" -> Json.Str("Refactor.greet")).contains("not a pure function"))
    assert(ask("Square.area").contains("member of a class"))
    assert(ask("Refactor.total")(using Map.empty, None).contains("no classpath"))
    val j = ask("Refactor.totalFold", "against" -> Json.Str("Refactor.total"), "format" -> Json.Str("json"))
    assert(okay.effects.json.Descent.parse(j).toOption.flatMap(_.field("equivalent")).contains(Json.Bool(true)), j)

  test("the old definition found in a file's text, its header, the harness"):
    val text = "package a\nimport b.c\nobject O:\n  def f(x: Int): Int =\n    x + 1\n\n  def g = 2\n"
    assertEquals(Equivalence.definitionIn(text, "O", "f"), Right("  def f(x: Int): Int =\n    x + 1"))
    assert(Equivalence.definitionIn(text, "O", "h").isLeft)
    assertEquals(Equivalence.header(text), Vector("package a", "import b.c"))
