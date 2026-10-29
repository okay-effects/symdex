package symdex

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock

import java.nio.file.{Files, Path}

/**
 * symdex under handlers of its own (specs/symdex.md, v0.7): the tools over an index given — no disk, no compiler,
 * a virtual clock — and `Code`'s own handler over files in memory and TASTy given.
 */
class TestEffects extends munit.FunSuite:

  type Asked[X] = (Clock + Archive + Code)[X]
  type Below[X] = (Clock + Tasty + Disk + Code)[X]

  /** the fixtures this build compiles, read once from the real disk: the index the tests below are given */
  private val real = Symdex.Live(Path.of("."), include = f => f.module == "symdex:test")
  private val g = real.run(Code.current[Symdex.Asked])
  private val shapes = g.index.entries.find(_.file == "src/test/scala/fixture/Shapes.scala").get
  private val shapesLines = real.run(Code.lines[Symdex.Asked](shapes.source))

  private def fixed[A](p: A ! Asked, now: Long = 5000): A =
    run(Clock.virtual(now)(Archive.memory(Archive.Store())(
      Code.fixed(Path.of("/given"), g.index, lines = Map(shapes.source -> shapesLines))(p))))._2

  test("a tool over an index given: no disk, no compiler, the clock a virtual one"):
    val out = fixed(Tools.definition[Asked](Json.obj("query" -> Json.Str("fixture.Shape"))))
    assert(out.startsWith("[generation 1, "), out)
    assert(out.contains("built 5s ago"), out)
    assert(out.contains("src/test/scala/fixture/Shapes.scala:4:7"), out)
    assert(out.contains("/** A shape has an area. */"), out)
    val refs = fixed(Tools.references[Asked](Json.obj("query" -> Json.Str("Use.total"))))
    assert(refs.contains("2 references in 2 files"), refs)

  test("an answer over its budget is archived and paged back, all in memory"):
    val p = Tools.call[Asked]("outline", Json.obj("file" -> Json.Str("Shapes.scala"), "budget" -> Json.num(300)))
      .flatMap { out =>
        val id = """archived as `([0-9a-f]+)`""".r.findFirstMatchIn(out.fold(identity, identity)).map(_.group(1)).get
        Tools.call[Asked]("more", Json.obj("id" -> Json.Str(id))).map(out -> _)
      }
    val (out, page) = fixed(p)
    assert(out.exists(_.contains("[compressed:")), out)
    assert(page.exists(_.contains("lines 1-")), page)

  // ---- Code's handler over files in memory -----------------------------------------------------------------

  private val project = Path.of(".").toAbsolutePath.normalize
  private val mem = Path.of("/m")
  private def moved(p: Path): Path = mem.resolve(project.relativize(p.toAbsolutePath.normalize).toString)

  /** the fixtures' SemanticDB and sources, copied into memory under /m */
  private def fixtureDisk(): Disk.Memory =
    // the sources first: each write is a tick, and a SemanticDB older than its source would be stale
    Disk.Memory(g.index.entries.map(e => moved(e.source) -> Files.readAllBytes(e.source)) ++
      g.files.map(f => moved(f.path) -> Files.readAllBytes(f.path)))

  private final class Reads:
    var count = 0
    def apply(@annotation.unused dir: Path): Either[String, Tasty.Read] =
      count += 1
      Right(Tasty.Read(Map(("src/test/scala/fixture/Shapes.scala", 3, 6) -> Span(3, 4, false)), 1, 0))

  private def below[A](disk: Disk.Memory, kept: Kept, reads: Reads, now: Long = 0)(p: A ! Below): A =
    run(Clock.virtual(now)(Tasty.fixed(reads.apply)(Disk.memory(disk)(
      Code.compiled(mem, kept, recheckMillis = 0)(p)))))._2

  test("the generation over files in memory: built, kept, rebuilt after a compile, a vanished file gone"):
    val disk = fixtureDisk()
    val kept = Kept()
    val reads = Reads()
    val first = below(disk, kept, reads)(Code.current[Below])
    assertEquals(first.number, 1)
    assertEquals(first.index.entries.map(_.file).toSet, g.index.entries.map(_.file).toSet)
    assert(first.unreadable.isEmpty, first.unreadable)
    // nothing changed: the same generation
    assertEquals(below(disk, kept, reads)(Code.current[Below]).number, 1)
    // a compile rewrites a SemanticDB file: a new generation
    val db = moved(g.files.find(_.path.toString.endsWith("Shapes.scala.semanticdb")).get.path)
    disk.touch(db)
    assertEquals(below(disk, kept, reads)(Code.current[Below]).number, 2)
    // a source deleted, its SemanticDB with it: gone from the index
    disk.delete(db)
    val after = below(disk, kept, reads)(Code.current[Below])
    assertEquals(after.number, 3)
    assert(!after.index.entries.exists(_.file.endsWith("Shapes.scala")), after.index.entries.map(_.file))
    // a damaged file is reported, not fatal
    disk.put(db, "not a semanticdb".getBytes("UTF-8"))
    val damaged = below(disk, kept, reads)(Code.current[Below])
    assert(damaged.unreadable.exists(_.contains("Shapes.scala.semanticdb")), damaged.unreadable)
    assertEquals(reads.count, 0) // no tool asked for a span: no TASTy read

  test("TASTy read once, saved, and read back from the file by the next process under the same stamp"):
    val disk = fixtureDisk()
    val reads = Reads()
    val root = moved(g.files.head.root)
    val table = below(disk, Kept(), reads)(Code.current[Below].flatMap(_ => Code.table[Below](root)))
    assertEquals(reads.count, 1)
    assertEquals(SpanTable.of(table, "src/test/scala/fixture/Shapes.scala", Range(3, 6, 3, 11)),
      Some(Span(3, 4, false)))
    assert(disk.paths.exists(_.startsWith(mem.resolve(".symdex/tasty"))), disk.paths)
    // the same process asks again: kept
    // a new process (a new Kept), the same stamp: from the saved file, no TASTy read
    val again = Kept()
    below(disk, again, reads)(Code.current[Below].flatMap(_ => Code.table[Below](root)))
    assertEquals(reads.count, 1)
    val c = below(disk, again, reads)(Code.covered[Below])
    assertEquals((c.loaded, c.fromDisk), (1, 1))
    // a compile changes the stamp: read again
    disk.touch(moved(g.files.head.path))
    below(disk, Kept(), reads)(Code.current[Below].flatMap(_ => Code.table[Below](root)))
    assertEquals(reads.count, 2)

  test("a source edited after its compile is stale"):
    val disk = fixtureDisk()
    val kept = Kept()
    val reads = Reads()
    val first = below(disk, kept, reads)(Code.current[Below])
    assertEquals(below(disk, kept, reads)(Code.stale[Below](first)), Vector.empty)
    disk.touch(moved(shapes.source))
    assertEquals(below(disk, kept, reads)(Code.stale[Below](first)).map(_.file),
      Vector("src/test/scala/fixture/Shapes.scala"))
