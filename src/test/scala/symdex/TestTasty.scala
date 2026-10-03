package symdex

import java.nio.file.Path
import scala.util.chaining.*

class TestTasty extends munit.FunSuite:
  test("TASTy spans join SemanticDB's definition positions"):
    val r = Tasty.read(Path.of("target/scala-3.9.0/test-classes"))
    assertEquals(r.skipped, 0)
    // trait Shape: name at line 4 col 7 (1-based), body to line 5
    assertEquals(r.spans.get(("src/test/scala/fixture/Shapes.scala", 3, 6)), Some(Span(3, 4, false)))
    // `extension (s: Shape) def doubled`
    assert(r.spans.get(("src/test/scala/fixture/Shapes.scala", 28, 25)).exists(_.extension), r.spans)

  test("the class directory beside each SemanticDB layout"):
    assertEquals(Tasty.classDirOf(Path.of("/p/target/scala-3.9.0/meta/META-INF/semanticdb")),
      Path.of("/p/target/scala-3.9.0/classes"))
    assertEquals(Tasty.classDirOf(Path.of("/p/target/scala-3.9.0/test-meta/META-INF/semanticdb")),
      Path.of("/p/target/scala-3.9.0/test-classes"))
    assertEquals(Tasty.classDirOf(Path.of("/p/target/scala-2.13/classes/META-INF/semanticdb")),
      Path.of("/p/target/scala-2.13/classes"))

class TestSavedSpans extends munit.FunSuite:
  private val classes = Path.of("target/scala-3.9.0/test-classes").toAbsolutePath.normalize

  private def tmp(): Path = java.nio.file.Files.createTempDirectory("symdex-spans")

  test("spans read from TASTy are saved, and the next reader with the same stamp reads the file"):
    val file = SavedSpans.fileFor(tmp(), classes)
    val first = TastyDir(classes, "k1", Some(file))
    val fromTasty = first.force()
    assert(!first.fromDisk)
    assert(java.nio.file.Files.exists(file))
    assert(fromTasty.spans.nonEmpty)
    val second = TastyDir(classes, "k1", Some(file))
    assertEquals(second.force(), fromTasty)
    assert(second.fromDisk)

  test("another stamp or another directory is not read from the file, and the file is rewritten"):
    val file = SavedSpans.fileFor(tmp(), classes)
    val r = TastyDir(classes, "k1", Some(file)).force()
    assertEquals(SavedSpans.load(file, classes, "k2"), None)
    assertEquals(SavedSpans.load(file, classes.resolveSibling("classes"), "k1"), None)
    val changed = TastyDir(classes, "k2", Some(file))
    assertEquals(changed.force(), r)
    assert(!changed.fromDisk)
    assert(TastyDir(classes, "k2", Some(file)).tap(_.force()).fromDisk)

  test("a truncated file is ignored and replaced; an unwritable place fails nothing"):
    val file = SavedSpans.fileFor(tmp(), classes)
    val r = TastyDir(classes, "k1", Some(file)).force()
    val bytes = java.nio.file.Files.readAllBytes(file)
    java.nio.file.Files.write(file, bytes.take(bytes.length / 2))
    val again = TastyDir(classes, "k1", Some(file))
    assertEquals(again.force(), r)
    assert(!again.fromDisk)
    assert(TastyDir(classes, "k1", Some(file)).tap(_.force()).fromDisk)
    // the directory to save in is a regular file
    val blocked = java.nio.file.Files.createTempFile("symdex-spans", ".block")
    val nowhere = TastyDir(classes, "k1", Some(blocked.resolve("x.spans")))
    assertEquals(nowhere.force(), r)
    assertEquals(nowhere.failed, None)

  test("a stamp changes when a file is rewritten, grows or is removed"):
    val root = Path.of("/r")
    def f(n: Int, mtime: Long, size: Long) = DbFile(root.resolve(s"$n.semanticdb"), root, "m", false, mtime, size)
    val files = Vector(f(1, 100, 10), f(2, 200, 20))
    assertEquals(Workspace.stamp(files), Workspace.stamp(files))
    assertNotEquals(Workspace.stamp(files.updated(0, f(1, 300, 10))), Workspace.stamp(files))
    assertNotEquals(Workspace.stamp(files.updated(1, f(2, 200, 21))), Workspace.stamp(files))
    assertNotEquals(Workspace.stamp(files.take(1).updated(0, f(1, 200, 30))), Workspace.stamp(files))
