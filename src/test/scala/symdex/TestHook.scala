package symdex

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import java.nio.file.{Files, Path}

class TestHook extends munit.FunSuite:
  private val dir = Files.createTempDirectory("symdex-hook")

  private def event(tool: String, input: (String, String), output: String): String =
    Json.print(Json.obj(
      "hook_event_name" -> Json.Str("PostToolUse"), "tool_name" -> Json.Str(tool),
      "tool_input" -> Json.obj(input._1 -> Json.Str(input._2)),
      "tool_response" -> Json.obj("type" -> Json.Str("text"), "text" -> Json.Str(output))))

  private val searchOut =
    (1 to 600).map(i => s"okay-stream/src/main/scala/F${i % 30}.scala:$i:  val x = Source.of($i) // some text").mkString("\n")

  private def replaced(out: Option[String]): String =
    val j = Descent.parse(out.getOrElse(fail("expected a replacement"))).getOrElse(fail(out.toString))
    j.field("hookSpecificOutput").flatMap(_.field("updatedToolOutput")).collect { case Json.Str(s) => s }
      .getOrElse(fail(out.toString))

  test("a large grep becomes a digest of itself, archived whole, with the symdex query"):
    val d = replaced(Hook.run(event("Bash", "command" -> "rg -n Source okay-stream", searchOut), dir))
    assert(d.contains("600 hits in 30 files"), d)
    assert(d.contains("okay-stream/src/main/scala/F0.scala  (20)"), d)
    assert(d.contains("references query=Source"), d)
    assert(d.length < searchOut.length * 3 / 10, d.length)
    val archived = """the whole output is (\S+\.txt)""".r.findFirstMatchIn(d).map(_.group(1)).get
    assertEquals(Files.readString(java.nio.file.Path.of(archived)), searchOut)

  test("the Grep tool too, by its pattern"):
    val d = replaced(Hook.run(event("Grep", "pattern" -> "\\bjoinSorted\\b", searchOut), dir))
    assert(d.contains("references query=joinSorted"), d)

  test("a test log keeps its head, tail and failures"):
    val log = ((1 to 900).map(i => s"[info] test $i passed") :+ "==> X TestFoo.bar failed: expected 1" :+ "[error] Failed: 1").mkString("\n")
    val d = replaced(Hook.run(event("Bash", "command" -> "sbt test", log), dir))
    assert(d.contains("==> X TestFoo.bar failed"), d)
    assert(d.contains("[error] Failed: 1"), d)
    assert(d.contains("[info] test 1 passed"), d)

  test("left alone: small, exact reads, diffs, credentials, other tools, garbage"):
    assertEquals(Hook.run(event("Bash", "command" -> "rg x", "a:1: b"), dir), None)
    assertEquals(Hook.run(event("Bash", "command" -> "cat big.scala", searchOut), dir), None)
    assertEquals(Hook.run(event("Bash", "command" -> "git diff HEAD~3", searchOut), dir), None)
    assertEquals(Hook.run(event("Bash", "command" -> "rg -n x | head -50", searchOut), dir), None)
    assertEquals(Hook.run(event("Bash", "command" -> "rg key", searchOut + "\nAKIAABCDEFGHIJKLMNOP"), dir), None)
    assertEquals(Hook.run(event("Read", "file_path" -> "x", searchOut), dir), None)
    assertEquals(Hook.run("not json at all", dir), None)

  test("which commands qualify"):
    assert(Hook.allowed("grep -rn foo src"))
    assert(Hook.allowed("cd x && rg -n foo"))
    assert(Hook.allowed("sbt -batch test"))
    assert(!Hook.allowed("cat file"))
    assert(!Hook.allowed("rg foo | sed -n 1,5p"))
    assert(!Hook.allowed("echo hi"))

  test("the archive on a disk keeps the 200 newest, each output written whole once"):
    val disk = Disk.Memory()
    val dir = Path.of("/m/.symdex/archive")
    val names = run(Disk.memory(disk)(Archive.files(dir)(
      each(1 to 205)(i => Archive.put[Archive + Disk](s"output $i")))))
    assertEquals(disk.paths.count(_.startsWith(dir)), 200)
    // the first five, the oldest, are gone; the last is there, whole
    assert(!disk.paths.contains(Path.of(names.head)), names.head)
    assertEquals(disk.bytes(Path.of(names.last)).map(String(_, "UTF-8")), Some("output 205"))
    // the same text again: the same file, not another
    val again = run(Disk.memory(disk)(Archive.files(dir)(Archive.put[Archive + Disk]("output 205"))))
    assertEquals(again, names.last)
