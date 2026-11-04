package symdex

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock

import java.nio.file.Path

/** `type` (specs/symdex.md, v0.9): Scala 3's presentation compiler on the fixtures, for real */
class TestType extends munit.FunSuite:
  type Asked[X] = (Clock + Archive + Code)[X]
  override val munitTimeout = scala.concurrent.duration.Duration(3, "min")

  private val real = Symdex.Live(Path.of("."), include = f => f.module == "symdex:test")
  private val g = real.run(Code.current[Symdex.Asked])
  private val refactor = g.index.entries.find(_.file == "src/test/scala/fixture/Refactor.scala").get
  // the fixtures' classes and the Scala library this JVM runs on
  private def jarOf(c: Class[?]) = Path.of(c.getProtectionDomain.getCodeSource.getLocation.toURI)
  private val library = Vector(jarOf(classOf[scala.Option[?]]), jarOf(classOf[scala.deriving.Mirror])).distinct
  private val built = Built(Tasty.classDirOf(refactor.db.root).toAbsolutePath +: library, "3.9.0")
  private val lines = g.index.entries.map(e => e.source -> java.nio.file.Files.readAllLines(e.source)
    .toArray(Array.empty[String]).toVector).toMap

  private def ask(args: (String, Json)*): String =
    run(Clock.virtual(5000)(Archive.memory(Archive.Store())(Code.fixed(Path.of("/given"), g.index, built = Some(built),
      lines = lines, hover = (f, t, o) => Pc.hover(built.classpath, f, t, o),
      problems = (f, t) => Pc.diagnose(built.classpath, f, t))(Tools.typeOf[Asked](Json.obj(args*))))))._2

  test("expr: its type in the file of its first name"):
    val out = ask("expr" -> Json.Str("Refactor.total(None)"))
    assert(out.contains("Refactor.total(Nil): Int") || out.contains(": Int"), out)

  test("expr: a wrong one, the compiler's error"):
    val out = ask("expr" -> Json.Str("Refactor.total(\"x\")"))
    assert(out.contains("does not compile") && out.contains("Found"), out)

  test("code: a definition checked — compiles with its signature, or the errors"):
    val ok = ask("code" -> Json.Str("def twice(xs: Option[List[Int]]): Int = Refactor.total(xs) * 2"),
      "in" -> Json.Str("Refactor.total"))
    assert(ok.contains("compiles") && ok.contains("def twice(xs: Option[List[Int]]): Int"), ok)
    val bad = ask("code" -> Json.Str("def twice(xs: Option[List[Int]]): String = Refactor.total(xs)"),
      "in" -> Json.Str("Refactor.total"))
    assert(bad.contains("does not compile"), bad)

  test("at: the signature at a place"):
    val l = lines(refactor.source).indexWhere(_.contains("def total(")) + 1
    val c = lines(refactor.source)(l - 1).indexOf("total") + 2
    val out = ask("at" -> Json.Str(s"src/test/scala/fixture/Refactor.scala:$l:$c"))
    assert(out.contains("def total("), out)
