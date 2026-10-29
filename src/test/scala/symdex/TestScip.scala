package symdex

import okay.effects.json.Json
import okay.scala3.*
import symdex.Symdex.Asked
import java.nio.file.{Files, Path}

/**
 * A real SCIP index: scip-java 0.12.3 over the three Java files beside it
 * (src/test/resources/scip; how it was made is in bench/scip.md). Every
 * expectation was read off those files.
 */
class TestScip extends munit.FunSuite:
  private val dir = Path.of("src/test/resources/scip")
  private val live = Symdex.Live(dir)
  private def call(f: Json => String ! Asked, kv: (String, Json)*): String = live.run(f(Json.Obj(kv.toVector)))
  private def q(s: String) = "query" -> Json.Str(s)

  test("the symbol's descriptor is what is kept"):
    assertEquals(Scip.descriptor("semanticdb maven . . shop/Shape#area()."), "shop/Shape#area().")
    assertEquals(Scip.descriptor("scip-typescript npm my  pkg 1.0 src/`a.ts`/f()."), "src/`a.ts`/f().")
    assertEquals(Scip.descriptor("local 7"), "local7")

  test("documents, definitions, references"):
    val r = Scip.read(Files.readAllBytes(dir.resolve("index.scip")))
    assertEquals(r.documents.map(_.uri).sorted, Vector("src/shop/Shape.java", "src/shop/Square.java", "src/shop/Use.java"))
    val defs = r.documents.flatMap(_.occurrences).filter(_.definition).map(_.symbol).toSet
    assert(defs("shop/Shape#area()."), defs)

  test("the tools answer over SCIP: implementations, overriders, callers, source"):
    val impl = call(Tools.implementations[Asked], q("shop.Shape"))
    assert(impl.contains("shop/Square#"), impl)
    val area = call(Tools.implementations[Asked], q("shop/Shape#area()."))
    assert(area.contains("shop/Square#area()."), area)
    val callers = call(Tools.references[Asked], q("Shape.area"), "callers" -> Json.Bool(true))
    assert(callers.contains("shop.Use#total"), callers)
    val src = call(Tools.source[Asked], q("shop/Use#total()."))
    assert(src.contains("return sum;"), src)
    assert(!src.contains("main"), src)
