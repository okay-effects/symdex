package symdex

import java.nio.file.Path

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
  private val read = Tasty.read(classes)

  test("spans as bytes and back, under the same directory and stamp only"):
    val bytes = SavedSpans.encode(classes, "k1", read)
    assertEquals(SavedSpans.decode(bytes, classes, "k1"), Some(read))
    assertEquals(SavedSpans.decode(bytes, classes, "k2"), None)
    assertEquals(SavedSpans.decode(bytes, classes.resolveSibling("classes"), "k1"), None)
    // damaged: cut short, or not spans at all
    assertEquals(SavedSpans.decode(bytes.take(bytes.length / 2), classes, "k1"), None)
    assertEquals(SavedSpans.decode("not spans".getBytes("UTF-8"), classes, "k1"), None)

  test("a file that cannot be written is not written, and nothing fails"):
    val blocked = java.nio.file.Files.createTempFile("symdex-blocked", "")
    val wrote = okay.scala3.run(Disk.real(Disk.write[Disk](blocked.resolve("x.spans"), Array[Byte](1, 2))))
    assert(!wrote)

  test("a stamp changes with a compile: a file rewritten, added or removed"):
    def f(n: Int, mtime: Long, size: Long) = DbFile(Path.of(s"/x/$n.semanticdb"), Path.of("/x"), "m", false, mtime, size)
    val files = Vector(f(1, 100, 10), f(2, 200, 20))
    assertEquals(Compiled.stamp(files), Compiled.stamp(files))
    assertNotEquals(Compiled.stamp(files.updated(0, f(1, 300, 10))), Compiled.stamp(files))
    assertNotEquals(Compiled.stamp(files.updated(1, f(2, 200, 21))), Compiled.stamp(files))
    assertNotEquals(Compiled.stamp(files.take(1).updated(0, f(1, 200, 30))), Compiled.stamp(files))
