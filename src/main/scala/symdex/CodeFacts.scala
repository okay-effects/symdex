package symdex

import okay.datalog.*

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * THE INDEX AS DATALOG FACTS (specs/symdex.md, v0.8): the relations a code-analysis program asks — definitions,
 * kinds, references with their sites, who calls whom (a reference inside a definition), subtyping, overriding,
 * givens, modules, tests — and the prelude of rules over them, goal-directed by seeds. Pure: a generation's index and
 * its definitions' spans in, facts out; evaluation is okay-datalog's.
 */
object CodeFacts:

  /** the rules always present; transitive relations start from `seed(X)`, so a query computes what its goal reaches */
  val prelude: Program = Parse.program("""
    calls(A, B) :- calls_at(A, B, _, _).
    callers(X, Y) :- seed(X), calls(Y, X).
    callers(X, Z) :- callers(X, Y), calls(Z, Y).
    implementers(X, Y) :- seed(X), overrides(Y, X).
    implementers(X, Y) :- seed(X), extends(Y, X).
    implementers(X, Z) :- implementers(X, Y), overrides(Z, Y).
    implementers(X, Z) :- implementers(X, Y), extends(Z, Y).
    up(X, B) :- seed(X), overrides(X, B).
    up(X, C) :- up(X, B), overrides(B, C).
    affects(X, Y) :- callers(X, Y).
    affects(X, Y) :- implementers(X, Y).
    affects(X, Y) :- up(X, B), calls(Y, B).
    affects(X, Z) :- affects(X, Y), calls(Z, Y).
    tests_for(X, T) :- affects(X, T), test(T).
    reaches(M, N) :- seed(M), module_uses(N, M).
    reaches(M, P) :- reaches(M, N), module_uses(P, N).
  """).fold(e => throw IllegalStateException(s"the prelude: $e"), identity)

  /** the prelude's relations whose first argument is a seed */
  val seeded: Set[String] = Set("callers", "implementers", "up", "affects", "tests_for", "reaches")

  /** the relations the index gives, as the tool's description lists them */
  val relations: String =
    "defined(S), kind(S,K), named(S,N), defined_in(S,F), in_module(S,M), ref_at(S,F,L), calls_at(A,B,F,L), " +
      "calls(A,B), extends(A,B), overrides(A,B), given_for(T,G), module_uses(M,N), test(S)"

  private def isTest(l: Loc): Boolean =
    l.file.contains("/test/") || l.module.toLowerCase.contains("test")

  /** a generation's facts; `tableOf` gives a SemanticDB root's definition spans (exact `calls_at` where known) */
  def facts(ix: Index, tableOf: Path => SpanTable): Vector[Fact] =
    val b = Vector.newBuilder[Fact]
    def f(p: String, args: String*): Unit = b += Fact(p, args.toVector)
    for (s, locs) <- ix.definitions; l <- locs.headOption do
      f("defined", s)
      f("named", s, Symbols.name(s))
      f("defined_in", s, l.file)
      f("in_module", s, l.module)
      if isTest(l) then f("test", s)
    for (s, i) <- ix.infos do
      f("kind", s, i.kindName)
      i.parents.foreach(p => f("extends", s, p))
      i.overridden.foreach(o => f("overrides", s, o))
    for (t, gs) <- ix.givensOf; g <- gs do f("given_for", t, g)
    for (m, row) <- ix.uses; n <- row.keys do f("module_uses", m, n)
    for (s, locs) <- ix.references; l <- locs do
      f("ref_at", s, l.file, l.line.toString)
      val table = ix.entryFor(l.file).fold(SpanTable.empty)(e => tableOf(e.db.root))
      ix.enclosing(l, table).filter(_ != s).foreach(a => f("calls_at", a, s, l.file, l.line.toString))
    b.result()

  /** the last generation's facts, kept: a query after a query on the same index builds nothing */
  private val kept = AtomicReference[(Int, Path, Vector[Fact])]((-1, Path.of(""), Vector.empty))

  def cached(g: Generation, root: Path, tableOf: Path => SpanTable): Vector[Fact] =
    val (n, r, fs) = kept.get
    if n == g.number && r == root then fs
    else
      val built = facts(g.index, tableOf)
      kept.set((g.number, root, built))
      built

  /**
   * a goal or rules with `@Name.path` resolved: one symbol becomes a constant; several, in a goal, become a variable
   * (`At1`, …) ranging over them (`alternatives`: the variable and its symbols — in the first argument they are the
   * seeds too); several in rules are an error naming them
   */
  final case class Resolved(text: String, alternatives: Vector[(String, Vector[String])]):
    def seeds: Vector[String] = alternatives.headOption.filter(_._1 == "At1").fold(Vector.empty)(_._2)

  private val At = """@([A-Za-z_$][\w$]*(?:[.#][\w$]+)*)""".r

  def resolveNames(ix: Index, text: String, goal: Boolean): Either[String, Resolved] =
    val firstArg = if goal then text.indexOf('(') + 1 else -1
    var alternatives = Vector.empty[(String, Vector[String])]
    var error: Option[String] = None
    val out = At.replaceAllIn(text, m =>
      val name = m.group(1)
      val syms = ix.resolve(name)
      val atFirst = goal && text.substring(firstArg, m.start).trim.isEmpty
      val replaced =
        if syms.isEmpty then { error = error.orElse(Some(s"nothing named '$name' in the index")); "x" }
        else if syms.size == 1 then Term.show(syms.head)
        else if goal then
          // At1 is reserved for the first argument (the seeds); the others from At2
          val v = if atFirst && alternatives.isEmpty then "At1" else s"At${alternatives.size + 2}"
          alternatives :+= (v -> syms)
          v
        else
          error = error.orElse(Some(s"'$name' means ${syms.size} symbols; name one: ${syms.take(8).mkString(", ")}"))
          "x"
      scala.util.matching.Regex.quoteReplacement(replaced))
    error.toLeft(Resolved(out, alternatives))
