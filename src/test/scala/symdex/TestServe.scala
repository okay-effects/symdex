package symdex

import okay.effects.json.Json
import okay.mcp.Rpc

import java.nio.file.Path

/** symdex on the wire: okay-mcp's server over a scripted link, driven with lines, no process. */
class TestServe extends munit.FunSuite:

  private val live = Symdex.Live(Path.of("."), include = f => f.module == "symdex:test")

  private def line(m: Rpc): String = Rpc.encode(m)
  private val hello = line(Rpc.Request(Json.num(1), "initialize", Json.obj("protocolVersion" -> Json.Str("2025-06-18"),
    "capabilities" -> Json.Obj.empty,
    "clientInfo" -> Json.obj("name" -> Json.Str("client"), "version" -> Json.Str("1")))))
  private def call(id: Int, name: String, args: (String, Json)*): String =
    line(Rpc.Request(Json.num(id), "tools/call",
      Json.obj("name" -> Json.Str(name), "arguments" -> Json.Obj(args.toVector))))
  private def talk(specs: Vector[okay.mcp.ToolSpec], lines: String*): Vector[Rpc] =
    Symdex.Serve.scripted(live, specs, lines.toList).toVector.map(l => Rpc.decode(l).fold(f => f, m => m))
  private def answer(r: Rpc): Json = r match
    case Rpc.Answer(_, result) => result
    case other => fail(s"not an answer: $other")
  private def text(result: Json): String =
    result.field("content").collect { case Json.Arr(cs) => cs }.getOrElse(Vector.empty)
      .flatMap(_.field("text")).collect { case Json.Str(s) => s }.mkString

  test("initialize, then tools/list names the ten tools"):
    val out = talk(Tools.specs, hello, line(Rpc.Request(Json.num(2), "tools/list", Json.Obj.empty)))
    assertEquals(answer(out(0)).field("serverInfo").flatMap(_.field("name")), Some(Json.Str("symdex")))
    val names = answer(out(1)).field("tools").collect { case Json.Arr(ts) => ts }.getOrElse(Vector.empty)
      .flatMap(_.field("name")).collect { case Json.Str(n) => n }.toSet
    assertEquals(names,
      Set("definition", "references", "implementations", "givens", "members", "modules", "status", "source",
        "outline", "more"))

  test("a lean, narrowed server: only the tools asked for, terse schemas, fewer tokens"):
    val full = Tools.serving(None, lean = false)
    val lean = Tools.serving(Some(Set("definition", "references")), lean = true)
    assertEquals(lean.map(_.name).toSet, Set("definition", "references"))
    assert(!Json.print(lean.head.inputSchema).contains("description"), lean.head.inputSchema)
    val (f, l) = (Tools.schemaTokens(full), Tools.schemaTokens(lean))
    assert(l * 4 < f, s"lean $l vs full $f")
    // what is not served is absent: asking for it answers "no such tool"
    val out = talk(lean, hello, call(9, "status"))
    assertEquals(answer(out(1)).field("isError"), Some(Json.Bool(true)))
    assert(text(answer(out(1))).contains("no such tool"), text(answer(out(1))))

  test("every answer says what it costs"):
    val r = live.call("status", Json.Obj.empty).fold(identity, identity)
    assert("""~\d+ tokens\]""".r.findFirstIn(r.linesIterator.next()).isDefined, r)
    assert(r.contains("tool schemas: 10 tools"), r)

  test("tools/call answers; a missing argument is an isError answer"):
    val out = talk(Tools.specs, hello, call(3, "references", "query" -> Json.Str("Use.total")), call(4, "references"))
    assert(text(answer(out(1))).contains("2 references in 2 files"), text(answer(out(1))))
    assertEquals(answer(out(2)).field("isError"), Some(Json.Bool(true)))
    assert(text(answer(out(2))).contains("missing argument 'query'"), text(answer(out(2))))
