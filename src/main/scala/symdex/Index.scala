package symdex

import java.nio.file.Path
import scala.collection.mutable

/** A place in a source file: which file (as shown), which module, which range. */
final case class Loc(file: String, source: Path, module: String, range: Range):
  def line: Int = range.startLine + 1
  def col: Int = range.startChar + 1
  def show: String = s"$file:$line:$col"

/** One indexed document: the SemanticDB it came from and the source it describes. */
final case class Entry(db: DbFile, source: Path, file: String, doc: Document)

/**
 * A SemanticDB root's definition spans (from TASTy, or a SCIP index's own ranges): by the source path as the
 * compiler wrote it, then the position of the definition's name — exactly SemanticDB's definition occurrence.
 */
type SpanTable = Map[String, Map[(Int, Int), Span]]

object SpanTable:
  val empty: SpanTable = Map.empty
  def from(spans: Map[(String, Int, Int), Span]): SpanTable =
    spans.toVector.groupMap(_._1._1)((k, v) => (k._2, k._3) -> v).view.mapValues(_.toMap).toMap

  /**
   * The span of the definition whose name is at `r` in `uri`. TASTy writes a source path relative to its build's
   * root, as SemanticDB writes its uri: usually the same string; when two builds disagree on the root, the longer
   * path ends with the shorter.
   */
  def of(t: SpanTable, uri: String, r: Range): Option[Span] =
    val path = if t.contains(uri) then Some(uri) else t.keys.find(p => p.endsWith("/" + uri) || uri.endsWith("/" + p))
    path.flatMap(p => t(p).get((r.startLine, r.startChar)))

/**
 * Everything the tools ask, precomputed from one set of documents. An Index is immutable data — a new build is a
 * new Index, swapped in whole — and reads nothing: a definition's span comes from `Code` (its SemanticDB root's
 * table), handed in where it is needed.
 */
final class Index(val root: Path, val entries: Vector[Entry]):

  private val entryOf: Map[String, Entry] = entries.map(e => e.file -> e).toMap

  /** the document an indexed file was read from */
  def entryFor(file: String): Option[Entry] = entryOf.get(file)

  /** the full extent of the definition named at `l`, in its root's table */
  def spanAt(l: Loc, table: SpanTable): Option[Span] =
    entryOf.get(l.file).flatMap(e => SpanTable.of(table, e.doc.uri, l.range))

  /** what every symbol is, from the document that defines it */
  val infos: Map[String, Info] =
    val m = mutable.HashMap.empty[String, Info]
    for e <- entries; i <- e.doc.infos if !Symbols.isLocal(i.symbol) do
      if !m.contains(i.symbol) then m(i.symbol) = i
    m.toMap

  private def locs(definition: Boolean): Map[String, Vector[Loc]] =
    val m = mutable.HashMap.empty[String, mutable.ArrayBuffer[Loc]]
    for e <- entries; o <- e.doc.occurrences
      if o.definition == definition && o.symbol.nonEmpty && !Symbols.isLocal(o.symbol) do
      m.getOrElseUpdate(o.symbol, mutable.ArrayBuffer.empty) += Loc(e.file, e.source, e.db.module, o.range)
    m.view.mapValues(_.toVector.distinct).toMap

  val definitions: Map[String, Vector[Loc]] = locs(definition = true)
  val references: Map[String, Vector[Loc]] = locs(definition = false)

  private val nameless = Set(Info.Parameter, Info.TypeParameter, Info.SelfParameter, Info.Local)

  /** plain name → symbols, for everything a person would look up by name */
  val byName: Map[String, Vector[String]] =
    val m = mutable.HashMap.empty[String, mutable.LinkedHashSet[String]]
    def add(s: String): Unit =
      if !Symbols.isLocal(s) && !infos.get(s).exists(i => nameless(i.kind)) then
        m.getOrElseUpdate(Symbols.name(s), mutable.LinkedHashSet.empty) += s
    definitions.keysIterator.foreach(add)
    infos.keysIterator.foreach(add)
    m.view.mapValues(_.toVector).toMap

  /** parent → direct subtypes */
  val children: Map[String, Vector[String]] =
    infos.values.toVector.flatMap(i => i.parents.map(_ -> i.symbol))
      .groupMap(_._1)(_._2).view.mapValues(_.distinct.sorted).toMap

  /** member → the members that override it directly */
  val overriders: Map[String, Vector[String]] =
    infos.values.toVector.flatMap(i => i.overridden.map(_ -> i.symbol))
      .groupMap(_._1)(_._2).view.mapValues(_.distinct.sorted).toMap

  /** type → the given/implicit definitions whose type it heads (`given Ordering[K]` under Ordering) */
  val givensOf: Map[String, Vector[String]] =
    infos.values.toVector
      .filter(i => i.isGiven && i.result.nonEmpty && i.kind != Info.Parameter)
      .groupMap(_.result)(_.symbol).view.mapValues(_.distinct.sorted).toMap

  /**
   * Anonymous implementations — `new Bulk { ... }` — and the members
   * they override. SemanticDB gives them document-local symbols, so they
   * are kept per document: where they are (the first of their members'
   * definitions, an anonymous class itself has no name to occur) and the
   * named definition they sit in.
   */
  final case class Anonymous(loc: Loc, within: Option[String])

  private lazy val anonymous: (Map[String, Vector[Anonymous]], Map[String, Vector[Anonymous]]) =
    val types = mutable.HashMap.empty[String, mutable.ArrayBuffer[Anonymous]]
    val members = mutable.HashMap.empty[String, mutable.ArrayBuffer[Anonymous]]
    for e <- entries do
      val locals = e.doc.infos.filter(i => Symbols.isLocal(i.symbol))
      if locals.exists(i => i.parents.nonEmpty || i.overridden.nonEmpty) then
        val at: Map[String, Range] = e.doc.occurrences.iterator
          .filter(o => o.definition && Symbols.isLocal(o.symbol)).map(o => o.symbol -> o.range).toMap
        def anon(r: Range): Anonymous =
          val loc = Loc(e.file, e.source, e.db.module, r)
          Anonymous(loc, enclosing(loc, SpanTable.empty))
        for i <- locals do
          if i.kind == Info.Class && i.parents.nonEmpty then
            val r = (at.get(i.symbol).toVector ++ i.declarations.flatMap(at.get))
              .minByOption(r => (r.startLine, r.startChar))
            r.foreach(r => i.parents.foreach(p => types.getOrElseUpdate(p, mutable.ArrayBuffer.empty) += anon(r)))
          if i.overridden.nonEmpty then
            at.get(i.symbol).foreach(r =>
              i.overridden.foreach(o => members.getOrElseUpdate(o, mutable.ArrayBuffer.empty) += anon(r)))
    (types.view.mapValues(_.toVector).toMap, members.view.mapValues(_.toVector).toMap)

  /** the anonymous classes extending this type, and anonymous members overriding this member */
  def anonymousOf(s: String): Vector[Anonymous] =
    anonymous._1.getOrElse(s, Vector.empty) ++ anonymous._2.getOrElse(s, Vector.empty)

  /** per source file: the non-local definitions in order, for "which definition encloses this line" */
  private val outlines: Map[String, Vector[(Range, String)]] =
    val enclosing = Set(Info.Method, Info.Field, Info.Object, Info.Class, Info.Trait,
      Info.Interface, Info.Constructor, Info.Macro, Info.PackageObject)
    entries.map { e =>
      e.file -> e.doc.occurrences
        .filter(o => o.definition && infos.get(o.symbol).exists(i => enclosing(i.kind)))
        .map(o => o.range -> o.symbol)
        .sortBy((r, _) => (r.startLine, r.startChar))
    }.toMap

  /**
   * The definition a reference sits in: the nearest non-local definition
   * that STARTS before it in the same file. An approximation —
   * SemanticDB records a definition's name, not its body, so code after
   * a method's end is credited to that method until the next definition.
   * TASTy carries the spans that would make it exact (specs/symdex.md).
   */
  def enclosing(l: Loc, table: SpanTable): Option[String] =
    val defs = outlines.getOrElse(l.file, Vector.empty)
    val e = entryOf.get(l.file)
    // exact, from TASTy: the innermost definition whose span holds the line
    val exact = e.toVector.flatMap(en =>
        defs.flatMap((r, s) => SpanTable.of(table, en.doc.uri, r).map(sp => (r, s, sp))))
      .filter((r, _, sp) => sp.startLine <= l.range.startLine && l.range.startLine <= sp.endLine &&
        !(r.startLine == l.range.startLine && r.startChar == l.range.startChar))
      .maxByOption((r, _, sp) => (sp.startLine, r.startChar))
      .map(_._2)
    exact.orElse(
      defs.takeWhile((r, _) => r.startLine < l.range.startLine ||
          (r.startLine == l.range.startLine && r.startChar < l.range.startChar))
        .lastOption.map(_._2))

  /**
   * Extension methods by the type of their receiver (`extension (s: Shape)
   * def doubled` under Shape): TASTy says which methods are extensions,
   * SemanticDB's first parameter's type says on what.
   */
  def extensionsOn(tables: Path => SpanTable): Map[String, Vector[String]] =
    infos.values.toVector.flatMap { i =>
      if i.kind != Info.Method then None
      else
        val isExt = definitions.get(i.symbol).flatMap(_.headOption)
          .flatMap(l => entryOf.get(l.file).flatMap(e => spanAt(l, tables(e.db.root)))).exists(_.extension)
        if !isExt then None
        else i.params.headOption.flatMap(infos.get).map(_.result).filter(_.nonEmpty).map(_ -> i.symbol)
    }.groupMap(_._1)(_._2).view.mapValues(_.distinct.sorted).toMap

  /** a file's non-local definitions in source order (constructors left out) */
  def outlineOf(file: String): Vector[(Range, String)] =
    outlines.getOrElse(file, Vector.empty).filterNot((_, s) => Symbols.name(s) == "<init>")

  /** module → module → how many references the first makes into the second */
  val uses: Map[String, Map[String, Int]] =
    // a package is not owned by a module: every module that declares
    // `package okay` "defines" it, and the first such file used to become
    // the package's home, so everything "used" that module (an agent
    // benchmark found all of okay depending on `compare`)
    val home: Map[String, String] = definitions.view.filterKeys(s => !s.endsWith("/")).mapValues(_.head.module).toMap
    val m = mutable.HashMap.empty[String, mutable.HashMap[String, Int]]
    for e <- entries; o <- e.doc.occurrences if !o.definition do
      home.get(o.symbol).foreach { target =>
        if target != e.db.module then
          val row = m.getOrElseUpdate(e.db.module, mutable.HashMap.empty)
          row(target) = row.getOrElse(target, 0) + 1
      }
    m.view.mapValues(_.toMap).toMap

  val modules: Vector[String] = entries.map(_.db.module).distinct.sorted

  val occurrenceCount: Int = entries.iterator.map(_.doc.occurrences.size).sum

  /** the file an entry was read for, by a path that ends with what was given */
  def fileFor(path: String): Vector[Entry] =
    val p = path.replace('\\', '/').stripPrefix("./")
    val exact = entries.filter(e => e.file == p || e.source.toString == p)
    if exact.nonEmpty then exact
    else entries.filter(e => e.file.endsWith("/" + p) || e.file.endsWith(p))

  /**
   * A name to the symbols it could mean. A SemanticDB symbol is taken as
   * is; otherwise `Bulk.joinSorted` is the symbols named `joinSorted`
   * whose owners end with `Bulk` (`.` and `#` both separate).
   */
  def resolve(query: String): Vector[String] =
    val q = query.trim
    if infos.contains(q) || definitions.contains(q) || references.contains(q) then Vector(q)
    else
      val parts = q.split("[.#]").toVector.filter(_.nonEmpty)
      parts.lastOption match
        case None => Vector.empty
        case Some(last) =>
          val owners = parts.init
          val named = byName.getOrElse(last, Vector.empty)
          val exact = named.filter(s => Symbols.names(s).init.endsWith(owners))
          // `Tables.orderedBy` for `okay/Tables.Plan.orderedBy().`: the
          // owners given, in order, with others between them allowed
          (if exact.nonEmpty then exact
           else named.filter(s => inOrder(owners, Symbols.names(s).init))).sorted

  private def inOrder(want: Vector[String], have: Vector[String]): Boolean =
    var i = 0
    for h <- have do if i < want.size && want(i) == h then i += 1
    i == want.size
