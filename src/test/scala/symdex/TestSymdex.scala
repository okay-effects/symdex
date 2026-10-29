package symdex

import okay.effects.json.Json
import okay.scala3.*
import symdex.Symdex.Asked
import java.nio.file.Path

/**
 * The tools against fixtures this build compiles itself
 * (src/test/scala/fixture, SemanticDB on in build.sbt). Every expected
 * answer below was read off the fixture source by hand.
 */
class TestSymdex extends munit.FunSuite:

  private val live = Symdex.Live(Path.of("."), include = f => f.module == "symdex:test")
  private def call(f: Json => String ! Asked, kv: (String, Json)*): String = live.run(f(Json.Obj(kv.toVector)))
  private def q(s: String) = "query" -> Json.Str(s)
  private val yes = Json.Bool(true)

  test("the fixtures are indexed"):
    val g = live.run(Code.current[Asked])
    assert(g.index.entries.exists(_.file == "src/test/scala/fixture/Shapes.scala"), g.index.entries.map(_.file))
    assert(g.unreadable.isEmpty, g.unreadable)

  test("symbol names parse"):
    assertEquals(Symbols.names("fixture/Shape#area()."), Vector("fixture", "Shape", "area"))
    assertEquals(Symbols.names("fixture/Square#`<init>`().(side)"), Vector("fixture", "Square", "<init>", "side"))
    assertEquals(Symbols.names("fixture/Show#[A]"), Vector("fixture", "Show", "A"))
    assertEquals(Symbols.names("scala/Predef.println(+1)."), Vector("scala", "Predef", "println"))

  test("definition: by qualified name, with its doc comment"):
    val out = call(Tools.definition[Asked], q("fixture.Shape"))
    assert(out.contains("fixture/Shape#  (trait)"), out)
    assert(out.contains("src/test/scala/fixture/Shapes.scala:4:7"), out)
    assert(out.contains("/** A shape has an area. */"), out)

  test("definition: a name with several meanings lists every candidate"):
    val out = call(Tools.definition[Asked], q("area"))
    for s <- Seq("fixture/Shape#area().", "fixture/Square#area().", "fixture/Circle#area().", "fixture/Triangle#area().") do
      assert(out.contains(s), out)

  test("definition: by position"):
    // line 40 is `render(1) + render(shapes.head) + total(shapes) + ...`; col 32 is inside `total`
    val out = call(Tools.definition[Asked], q("src/test/scala/fixture/Shapes.scala:40:42"))
    assert(out.contains("fixture/Use.total()."), out)

  test("references: across files, and grouped by caller"):
    val plain = call(Tools.references[Asked], q("Use.total"))
    assert(plain.contains("2 references in 2 files"), plain)
    assert(plain.contains("src/test/scala/fixture/Other.scala: 6"), plain)
    val callers = call(Tools.references[Asked], q("Use.total"), "callers" -> yes)
    assert(callers.contains("fixture.Use.report"), callers)
    assert(callers.contains("fixture.other.Other.big"), callers)

  test("a qualified name may skip owners, in order"):
    // `Use.total` is exact; `fixture.total` skips the object
    val out = call(Tools.definition[Asked], q("fixture.total"))
    assert(out.contains("fixture/Use.total()."), out)

  test("references: an ambiguous name asks, unless all"):
    val asks = call(Tools.references[Asked], q("area"))
    assert(asks.contains("symbols match"), asks)
    val all = call(Tools.references[Asked], q("area"), "all" -> yes)
    assert(all.contains("references in"), all)

  test("implementations: transitive subtypes, overriders, given instances"):
    val shape = call(Tools.implementations[Asked], q("fixture.Shape"))
    for s <- Seq("fixture/Square#", "fixture/Circle#", "fixture/Polygon#", "fixture/Triangle#") do
      assert(shape.contains(s), shape)
    assert(shape.contains("in fixture.Use.unit  src/test/scala/fixture/Shapes.scala:36"), shape)
    val area = call(Tools.implementations[Asked], q("fixture/Shape#area()."))
    assert(area.contains("fixture/Triangle#area()."), area)
    assert(area.contains("in fixture.Use.unit"), area)
    val show = call(Tools.implementations[Asked], q("fixture.Show"))
    assert(show.contains("fixture/Show.given_Show_Int."), show)
    assert(show.contains("fixture/Show.showShape."), show)

  test("givens at a column: only what starts on that line"):
    // `render(shapes.head)` at line 40 col 17: its given, not the whole line's enclosing calls
    val out = call(Tools.givens[Asked], "at" -> Json.Str("fixture/Shapes.scala:40:17"))
    assert(out.contains("given fixture/Show.showShape."), out)
    assert(!out.contains("given_Show_Int"), out)

  test("givens: which instance a call site resolved"):
    val out = call(Tools.givens[Asked], "at" -> Json.Str("fixture/Shapes.scala:40"))
    assert(out.contains("given fixture/Show.given_Show_Int."), out)
    assert(out.contains("given fixture/Show.showShape."), out)

  test("members: declared and inherited"):
    val out = call(Tools.members[Asked], q("fixture.Triangle"))
    assert(out.contains("sides  (method)"), out)
    assert(out.contains("inherited from fixture/Polygon#"), out)
    assert(out.contains("label  (method)"), out)
    assert(!out.contains("copy"), out)

  test("outline: what a file is, nested, without its text"):
    val out = call(Tools.outline[Asked], "file" -> Json.Str("Shapes.scala"))
    assert(out.contains("src/test/scala/fixture/Shapes.scala"), out)
    assert(out.contains("trait Shape"), out)
    assert(out.contains("  def total"), out)
    assert(out.contains("given showShape"), out)
    assert(!out.contains("shapes.map"), out)

  test("source: one body, by indentation, not the file"):
    val tri = call(Tools.source[Asked], q("fixture.Triangle"))
    assert(tri.contains("final class Triangle"), tri)
    assert(tri.contains("def area: Double = b * h / 2"), tri)
    assert(!tri.contains("trait Show"), tri)
    assert(tri.contains("(3 lines)"), tri)
    val one = call(Tools.source[Asked], q("Use.total"))
    assert(one.contains("(1 lines)"), one)
    val capped = call(Tools.source[Asked], q("fixture.Use"), "maxLines" -> Json.num(2))
    assert(capped.contains("more lines (raise `maxLines`)"), capped)

  private def wire(tool: String, kv: (String, Json)*): String =
    live.call(tool, Json.Obj(kv.toVector)).fold(identity, identity)

  test("over budget, an answer is compressed from itself and archived whole"):
    val full = wire("outline", "file" -> Json.Str("Shapes.scala"))
    val out = wire("outline", "file" -> Json.Str("Shapes.scala"), "budget" -> Json.num(300))
    assert(out.contains("[compressed:"), out)
    assert(out.contains("first lines:"), out)
    val id = """archived as `([0-9a-f]+)`""".r.findFirstMatchIn(out).map(_.group(1)).getOrElse(fail(out))
    // every line `more` gives back is a line of the full answer: nothing invented
    val page = wire("more", "id" -> Json.Str(id), "from" -> Json.num(1), "lines" -> Json.num(500))
    // (the first line differs only by the ~tokens the live answer adds to its header)
    val body = page.linesIterator.drop(1).toVector
    assertEquals(body.drop(1), full.linesIterator.toVector.drop(1))
    assert(full.linesIterator.next().startsWith(body.head.stripSuffix("]")), (full, body.head))

  test("an exact read is paged, never summarized"):
    val out = wire("source", q("fixture.Use"), "budget" -> Json.num(150))
    assert(out.contains("[paged:"), out)
    assert(!out.contains("[compressed:"), out)

  test("references `in` narrows to a path or module"):
    val out = call(Tools.references[Asked], q("Use.total"), "in" -> Json.Str("Other.scala"))
    assert(out.contains("1 references in 1 files"), out)

  test("callers are exact where TASTy has spans: code after a method is not credited to it"):
    // `shapes.head.doubled` (line 40) sits in `report`; `doubled`'s only use
    val out = call(Tools.references[Asked], q("fixture.doubled"), "callers" -> yes)
    assert(out.contains("fixture.Use.report"), out)
    // Polygon#label's `sides` reference is inside label, not after it
    val sides = call(Tools.references[Asked], q("Polygon.sides"), "callers" -> yes)
    assert(sides.contains("fixture.Polygon#label"), sides)

  test("members list extension methods on the type and its parents"):
    val out = call(Tools.members[Asked], q("fixture.Triangle"))
    assert(out.contains("extension methods:"), out)
    assert(out.contains("doubled  (on fixture.Shape#)"), out)

  test("source of a multi-line body comes from TASTy's span"):
    val out = call(Tools.source[Asked], q("Use.report"))
    assert(out.contains("(3 lines)"), out)
    assert(out.contains("render(1)"), out)

  test("modules and status"):
    val m = call(Tools.modules[Asked])
    assert(m.contains("symdex:test"), m)
    val s = call(Tools.status[Asked])
    assert(s.contains("documents indexed"), s)
    assert(s.contains("TASTy: "), s)

  test("an unchanged disk is the same generation"):
    val a = Symdex.Live(Path.of("."), include = f => f.module == "symdex:test", recheckMillis = 0)
    assertEquals(a.run(Code.current[Asked]).number, 1)
    assertEquals(a.run(Code.current[Asked]).number, 1)

class TestScan extends munit.FunSuite:
  private val base = Path.of("/w/okay")
  private def mod(rel: String) = Scan.moduleOf(base, base.resolve(rel))

  test("module names: cross platforms, the build root, tests"):
    assertEquals(mod("okay-stream/.jvm/target/scala-3.9.0/meta/META-INF/semanticdb"), ("okay-stream", false))
    assertEquals(mod("okay-stream/.js/target/scala-3.9.0/meta/META-INF/semanticdb"), ("okay-stream (js)", false))
    assertEquals(mod(".jvm/target/scala-3.9.0/test-meta/META-INF/semanticdb"), ("okay:test", true))
    assertEquals(mod(".native/target/scala-3.9.0/meta/META-INF/semanticdb"), ("okay (native)", false))
    assertEquals(mod("okay-spark/target/scala-3.9.0/meta/META-INF/semanticdb"), ("okay-spark", false))

class TestCli extends munit.FunSuite:
  test("--root DIR and --root=DIR both set the root"):
    assertEquals(Symdex.rootOf(List("status", "--root", "/x")), (Path.of("/x"), List("status")))
    assertEquals(Symdex.rootOf(List("status", "--root=/x")), (Path.of("/x"), List("status")))
    assertEquals(Symdex.rootOf(List("--root=/y", "references", "query=a")), (Path.of("/y"), List("references", "query=a")))

class TestUses extends munit.FunSuite:
  private def entry(module: String, file: String, occs: Occurrence*): Entry =
    val p = Path.of("/w", file)
    Entry(DbFile(p, Path.of("/w"), module, false, 0, 0), p, file, Document(file, Vector.empty, occs.toVector, Vector.empty))
  private def at(line: Int) = Range(line, 0, line, 4)

  test("a package clause is not a dependency: every module declares the same package"):
    val a = entry("bench", "a.scala", Occurrence(at(0), "p/", true))
    val b = entry("core", "b.scala", Occurrence(at(0), "p/", true), Occurrence(at(2), "p/Core#", true))
    // `import p.*` in c: a reference to the package
    val c = entry("stream", "c.scala", Occurrence(at(0), "p/", true), Occurrence(at(1), "p/", false),
      Occurrence(at(3), "p/Core#", false))
    val ix = Index(Path.of("/w"), Vector(a, b, c))
    assertEquals(ix.uses.getOrElse("stream", Map.empty), Map("core" -> 1))
