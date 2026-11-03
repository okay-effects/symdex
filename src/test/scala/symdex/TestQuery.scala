package symdex

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock

import java.nio.file.Path

/** `query`: Datalog over the index (specs/symdex.md, v0.8), on the fixtures' index given — no disk, no compiler */
class TestQuery extends munit.FunSuite:

  type Asked[X] = (Clock + Archive + Code)[X]

  private val real = Symdex.Live(Path.of("."), include = f => f.module == "symdex:test")
  private val g = real.run(Code.current[Symdex.Asked])

  private def ask(goal: String, extra: (String, Json)*): String =
    val args = Json.obj((("goal" -> Json.Str(goal)) +: extra)*)
    run(Clock.virtual(5000)(Archive.memory(Archive.Store())(
      Code.fixed(Path.of("/given"), g.index)(Tools.query[Asked](args)))))._2

  test("the relations from the index: definitions, references with sites, calls inside definitions"):
    val fs = CodeFacts.facts(g.index, _ => SpanTable.empty)
    val calls = fs.filter(_.pred == "calls_at").map(f => (f.args(0), f.args(1)))
    assert(calls.contains("fixture/other/Other.big()." -> "fixture/Use.total()."), calls.take(20))
    assert(fs.exists(f => f.pred == "extends" && f.args == Vector("fixture/Square#", "fixture/Shape#")))
    assert(fs.exists(f => f.pred == "overrides" && f.args == Vector("fixture/Square#area().", "fixture/Shape#area().")))

  test("callers, transitively"):
    val out = ask("callers(@Use.total, Y)")
    assert(out.contains("fixture/Use.report()."), out)
    assert(out.contains("fixture/other/Other.big()."), out)
    assert(out.contains("src/test/scala/fixture/Other.scala"), out)   // where each is defined

  test("what a change affects: implementers (dispatch) and callers of the abstract member"):
    val out = ask("affects(@Shape.area, Y)")
    for s <- Vector("fixture/Square#area().", "fixture/Circle#area().", "fixture/Triangle#area().",
        "fixture/Use.total().", "fixture/other/Other.big().") do assert(out.contains(s), s"$s in\n$out")

  test("why: a proof down to the reference sites"):
    val out = ask("callers(@Use.total, @Other.big)", "why" -> Json.Bool(true))
    assert(out.contains("1 answers"), out)
    assert(out.contains("why callers("), out)
    assert(out.contains("calls_at(\"fixture/other/Other.big().\", \"fixture/Use.total().\""), out)
    assert(out.contains("Other.scala"), out)

  test("rules of the caller's, over the same relations"):
    val out = ask("leaf(S)", "rules" -> Json.Str(
      "leaf(S) :- defined(S), kind(S, method), not called(S). called(S) :- calls(_, S)."))
    assert(out.contains("fixture/Use.report()."), out)   // nothing calls report
    assert(!out.contains("S = fixture/Use.total()."), out)

  test("a name meaning several symbols seeds them all; errors are answers"):
    val several = ask("implementers(@area, Y)")
    assert(several.contains("At1: one of"), several)
    val second = ask("callers(@Use.total, @area)")      // several symbols in another argument: a variable too
    assert(second.contains("At3: one of") || second.contains("At2: one of"), second)
    assert(ask("callers(@NoSuchThing, Y)").contains("nothing named 'NoSuchThing'"))
    assert(ask("callers(X Y)").contains("query: the goal"))
    assert(ask("p(X)", "rules" -> Json.Str("p(X) :- defined(X), not p(X).")).contains("not stratifiable"))

  test("format json: the answers as data for a program, no header, never compressed; an error as data too"):
    val out = ask("callers(@Use.total, Y)", "format" -> Json.Str("json"), "budget" -> Json.num(40))
    val j = okay.effects.json.Descent.parse(out).toOption.get
    assertEquals(j.field("count"), Some(Json.num(2)))
    assert(out.contains("\"Y\":\"fixture/Use.report().\"") || out.contains("\"Y\": \"fixture/Use.report().\""), out)
    val bad = ask("callers(X Y)", "format" -> Json.Str("json"))
    assert(okay.effects.json.Descent.parse(bad).toOption.flatMap(_.field("error")).nonEmpty, bad)
