package symdex

import okay.agent.{ToolCall, ToolSpec, Toolbox}
import okay.codec.Json
import okay.codec.Json.*
import okay.mcp.{Mcp, Server}

/**
 * The tools, each an answer an agent would otherwise assemble from
 * grep. Answers are plain text, compact, and say which generation they
 * came from; a name that means several symbols is answered with the
 * candidates rather than a guess.
 */
final class Tools(ws: Workspace):

  /** answers too long to return whole, by content hash; bounded, oldest dropped first */
  private object archive:
    private val MaxEntries = 64
    private val entries = collection.mutable.LinkedHashMap.empty[String, String]
    def put(text: String): String = synchronized {
      val md = java.security.MessageDigest.getInstance("SHA-256")
      val id = md.digest(text.getBytes("UTF-8")).take(6).map(b => f"${b & 0xff}%02x").mkString
      entries.remove(id)
      entries(id) = text
      while entries.size > MaxEntries do entries.remove(entries.head._1)
      id
    }
    def get(id: String): Option[String] = synchronized(entries.get(id))

  private val MaxCandidates = 20

  // ---- arguments -------------------------------------------------------

  private def str(a: Json, k: String): Option[String] = a match
    case JObj(fs) => fs.collectFirst { case (`k`, JStr(s)) if s.trim.nonEmpty => s.trim }
    case _ => None
  private def bool(a: Json, k: String): Boolean = a match
    case JObj(fs) => fs.collectFirst {
      case (`k`, JBool(b)) => b
      case (`k`, JStr(s)) => s == "true"
    }.getOrElse(false)
    case _ => false
  private def int(a: Json, k: String, default: Int): Int = a match
    case JObj(fs) => fs.collectFirst {
      case (`k`, JNum(n)) => n.toInt
      case (`k`, JStr(s)) if s.toIntOption.isDefined => s.toInt
    }.getOrElse(default)
    case _ => default
  private def need(a: Json, k: String): String =
    str(a, k).getOrElse(throw IllegalArgumentException(s"missing argument '$k'"))

  // ---- shared rendering --------------------------------------------------

  private def header(g: Generation): String =
    val age = (System.currentTimeMillis() - g.builtAt) / 1000
    s"[generation ${g.number}, ${g.index.entries.size} files, built ${age}s ago]"

  private def describe(ix: Index, s: String): String =
    ix.infos.get(s).map(i => Info.describe(i)).getOrElse("symbol")

  private def where(ix: Index, s: String): String =
    ix.definitions.get(s).flatMap(_.headOption).map(_.show).getOrElse("(outside the index: a library)")

  /** a query that is a position, `path:line[:col]` */
  private val Position = """(.+?):(\d+)(?::(\d+))?""".r

  /** symbols a query means: a position's symbol, or the name's */
  private def symbolsOf(ix: Index, q: String): Either[String, Vector[String]] =
    q match
      case Position(path, line, col) if !path.contains("#") =>
        val es = ix.fileFor(path)
        if es.isEmpty then Left(s"no indexed file matches '$path'")
        else
          val l = line.toInt - 1
          val hits = es.flatMap(_.doc.occurrences).filter(o =>
            o.range.startLine == l && Option(col).forall(c => o.range.covers(l, c.toInt - 1)))
            .filterNot(o => Symbols.isLocal(o.symbol))
          if hits.isEmpty then Left(s"no symbol at $q")
          else Right(hits.map(_.symbol).distinct)
      case _ =>
        val c = ix.resolve(q)
        if c.isEmpty then Left(s"nothing named '$q' in the index") else Right(c)

  /** several candidates are one answer only when they are one NAME: overloads, a class and its companion */
  private def oneName(ss: Vector[String]): Boolean =
    ss.map(s => Symbols.names(s)).distinct.size == 1

  private def candidates(ix: Index, ss: Vector[String]): String =
    val shown = ss.take(MaxCandidates).map(s => s"  $s  (${describe(ix, s)})  ${where(ix, s)}")
    val more = if ss.size > MaxCandidates then Vector(s"  … ${ss.size - MaxCandidates} more") else Vector.empty
    (s"${ss.size} symbols match; pass one as `query`:" +: (shown ++ more)).mkString("\n")

  /** the doc comment above a definition and its first lines, from the source */
  private def snippet(l: Loc): Vector[String] =
    val lines = ws.sources.lines(l.source)
    val at = l.range.startLine
    var top = at
    var inDoc = false
    while top > 0 && {
      val t = lines(top - 1).trim
      val docLine = t.startsWith("*") || t.startsWith("/**") || t.endsWith("*/") || t.startsWith("//") || t.startsWith("@")
      if t.endsWith("*/") then inDoc = true
      docLine || (inDoc && !t.startsWith("/**") && t.nonEmpty)
    } && at - top < 30 do
      top -= 1
      if lines(top).trim.startsWith("/**") then inDoc = false
    var end = at
    var depth = 0
    var go = true
    while go && end < lines.size && end - at < 8 do
      val t = lines(end)
      depth += t.count(c => c == '(' || c == '[') - t.count(c => c == ')' || c == ']')
      end += 1
      go = depth > 0
    lines.slice(top, end)

  // ---- the tools -----------------------------------------------------------

  def definition(a: Json): String =
    val g = ws.generation
    val ix = g.index
    symbolsOf(ix, need(a, "query")) match
      case Left(why) => s"${header(g)}\n$why"
      case Right(ss) =>
        val body = ss.take(MaxCandidates).map { s =>
          val defs = ix.definitions.getOrElse(s, Vector.empty)
          val head = s"$s  (${describe(ix, s)})"
          if defs.isEmpty then
            s"$head\n  defined outside the index (a library); ${ix.references.getOrElse(s, Vector.empty).size} references in it"
          else
            (head +: defs.flatMap(l => s"  ${l.show}  [${l.module}]" +: snippet(l).map("    " + _))).mkString("\n")
        }
        val more = if ss.size > MaxCandidates then Vector(s"… ${ss.size - MaxCandidates} more candidates") else Vector.empty
        (header(g) +: (body ++ more)).mkString("\n")

  def references(a: Json): String =
    val g = ws.generation
    val ix = g.index
    val callers = bool(a, "callers")
    val context = bool(a, "context")
    val limit = int(a, "limit", 300)
    symbolsOf(ix, need(a, "query")) match
      case Left(why) => s"${header(g)}\n$why"
      case Right(ss) if ss.size > 1 && !oneName(ss) && !bool(a, "all") =>
        s"${header(g)}\n${candidates(ix, ss)}\n(or `all: true` for every one of them)"
      case Right(ss) =>
        val within = str(a, "in")
        val refs = ss.flatMap(s => ix.references.getOrElse(s, Vector.empty)).distinct
          .filter(l => within.forall(w => l.file.contains(w) || l.module.contains(w)))
          .sortBy(l => (l.file, l.range.startLine, l.range.startChar))
        val files = refs.map(_.file).distinct.size
        val title = s"${ss.mkString(", ")}: ${refs.size} references in $files files"
        val shown = refs.take(limit)
        val lines =
          if callers then
            shown.groupBy(l => ix.enclosing(l).getOrElse("(top level)")).toVector
              .sortBy((enc, ls) => (ls.head.file, ls.head.range.startLine, enc))
              .map { (enc, ls) =>
                val at = ls.map(_.line).distinct.mkString(", ")
                s"  ${Symbols.show(enc)}  ${ls.head.file}:$at"
              }
          else if context then
            shown.map(l => s"  ${l.show}  ${ws.sources.line(l.source, l.line).trim}")
          else
            shown.groupBy(_.file).toVector.sortBy(_._1).map((f, ls) =>
              s"  $f: ${ls.map(_.line).distinct.mkString(", ")}")
        val cut = if refs.size > limit then Vector(s"  … ${refs.size - limit} more (raise `limit`)") else Vector.empty
        (Vector(header(g), title) ++ lines ++ cut).mkString("\n")

  def implementations(a: Json): String =
    val g = ws.generation
    val ix = g.index
    symbolsOf(ix, need(a, "query")) match
      case Left(why) => s"${header(g)}\n$why"
      case Right(ss) if ss.size > 1 && !oneName(ss) =>
        s"${header(g)}\n${candidates(ix, ss)}"
      case Right(ss) =>
        // breadth first over subtypes and overriders; the visited set bounds it
        val seen = collection.mutable.LinkedHashMap.empty[String, Int]
        var frontier = ss.map(_ -> 0)
        while frontier.nonEmpty do
          val next = Vector.newBuilder[(String, Int)]
          for (s, d) <- frontier do
            for c <- ix.children.getOrElse(s, Vector.empty) ++ ix.overriders.getOrElse(s, Vector.empty)
              if !seen.contains(c) && !ss.contains(c) do
              seen(c) = d + 1
              next += c -> (d + 1)
          frontier = next.result()
        val givens = ss.flatMap(s => ix.givensOf.getOrElse(s, Vector.empty)).filterNot(seen.contains)
        val anonymous = (ss ++ seen.keys).flatMap(ix.anonymousOf).distinct
        val body =
          seen.toVector.map((s, d) => s"  ${"  " * (d - 1)}$s  (${describe(ix, s)})  ${where(ix, s)}") ++
            (if givens.isEmpty then Vector.empty
             else "  given instances:" +: givens.map(s => s"    $s  (${describe(ix, s)})  ${where(ix, s)}")) ++
            (if anonymous.isEmpty then Vector.empty
             else "  anonymous (`new T { ... }`):" +: anonymous.map(a =>
               s"    in ${a.within.map(Symbols.show).getOrElse("(top level)")}  ${a.loc.show}"))
        val title = s"${ss.mkString(", ")}: ${seen.size} implementations, ${givens.size} given instances, ${anonymous.size} anonymous"
        (Vector(header(g), title) ++ body).mkString("\n")

  def givens(a: Json): String =
    val g = ws.generation
    val ix = g.index
    need(a, "at") match
      case Position(path, line, col) =>
        val es = ix.fileFor(path)
        if es.isEmpty then s"${header(g)}\nno indexed file matches '$path'"
        else
          val l = line.toInt - 1
          // what STARTS on that line (a call spanning from an earlier line,
          // a test macro around the whole body, is not "at" it), one entry
          // per range with every symbol inserted there
          val hits = es.flatMap(e => e.doc.synthetics.map(e -> _)).filter((_, s) =>
            s.symbols.nonEmpty && (col match
              case null => s.range.startLine == l
              case c => s.range.startLine == l && s.range.covers(l, c.toInt - 1)))
            .groupBy((e, s) => (e.file, s.range)).toVector
            .sortBy { case ((_, r), _) => (r.startLine, r.startChar, -r.endChar) }
          if hits.isEmpty then s"${header(g)}\nthe compiler inserted nothing at ${path}:$line"
          else
            val body = hits.flatMap { case ((_, r), group) =>
              val e = group.head._1
              val text = ws.sources.lines(e.source).lift(r.startLine)
                .map(t => t.slice(r.startChar, if r.endLine == r.startLine then r.endChar else t.length))
                .getOrElse("")
              val shown = if text.isEmpty then "(inserted after the expression)" else s"`$text`"
              s"  ${e.file}:${r.startLine + 1}:${r.startChar + 1}  $shown" +:
                group.flatMap(_._2.symbols).distinct.map { sym =>
                  val tag = if ix.infos.get(sym).exists(_.isGiven) then "given " else ""
                  s"    $tag$sym  (${describe(ix, sym)})  ${where(ix, sym)}"
                }
            }
            (header(g) +: body).mkString("\n")
      case other => s"${header(g)}\n`at` is path:line[:col], got '$other'"

  def members(a: Json): String =
    val g = ws.generation
    val ix = g.index
    val all = bool(a, "all")
    val types = Set(Info.Class, Info.Trait, Info.Object, Info.Interface, Info.PackageObject, Info.Type)
    symbolsOf(ix, need(a, "query")) match
      case Left(why) => s"${header(g)}\n$why"
      case Right(found) =>
        val ss = found.filter(s => ix.infos.get(s).exists(i => types(i.kind)))
        if ss.isEmpty then s"${header(g)}\n${found.mkString(", ")}: not a type in the index"
        else if ss.size > 1 && !oneName(ss) then s"${header(g)}\n${candidates(ix, ss)}"
        else
          val out = Vector.newBuilder[String]
          for s <- ss do
            // the type, then its parents in the index, breadth first, each once
            val order = collection.mutable.LinkedHashSet(s)
            var frontier = Vector(s)
            while frontier.nonEmpty do
              val next = frontier.flatMap(t => ix.infos.get(t).map(_.parents).getOrElse(Vector.empty))
                .filter(p => ix.infos.contains(p) && !order.contains(p))
              next.foreach(order += _)
              frontier = next.distinct
            val shownNames = collection.mutable.HashSet.empty[String]
            for owner <- order do
              val decls = ix.infos.get(owner).map(_.declarations).getOrElse(Vector.empty)
                .filter(d => all || ix.definitions.contains(d))
                .filterNot(d => Symbols.name(d) == "<init>" ||
                  ix.infos.get(d).exists(i => i.kind == Info.TypeParameter || i.kind == Info.Parameter))
                .filter(d => owner == s || shownNames.add(Symbols.name(d)))
              if owner == s then decls.foreach(d => shownNames += Symbols.name(d))
              if decls.nonEmpty then
                out += (if owner == s then s"$s  (${describe(ix, s)})" else s"  inherited from $owner")
                decls.foreach(d => out += s"    ${Symbols.name(d)}  (${describe(ix, d)})  ${where(ix, d)}")
            // extension methods on the type or on any of its parents in the index
            val exts = order.toVector.flatMap(t => ix.extensionsOn.getOrElse(t, Vector.empty).map(t -> _))
            if exts.nonEmpty then
              out += "  extension methods:"
              exts.foreach((on, e) => out += s"    ${Symbols.name(e)}  (on ${Symbols.show(on)})  ${where(ix, e)}")
          (header(g) +: out.result()).mkString("\n")

  def modules(a: Json): String =
    val g = ws.generation
    val ix = g.index
    str(a, "query") match
      case None =>
        val rows = ix.modules.map { m =>
          val files = ix.entries.count(_.db.module == m)
          val uses = ix.uses.getOrElse(m, Map.empty).keys.toVector.sorted
          s"  $m  ($files files)  uses: ${if uses.isEmpty then "-" else uses.mkString(", ")}"
        }
        (Vector(header(g), s"${ix.modules.size} modules") ++ rows).mkString("\n")
      case Some(q) =>
        val byPath = ix.fileFor(q).map(_.db.module).distinct
        val named = ix.modules.filter(m => m == q || m.endsWith("/" + q) || m.stripSuffix(":test") == q)
        val ms = (named ++ byPath).distinct
        if ms.isEmpty then s"${header(g)}\nno module or indexed file matches '$q'"
        else
          val usedBy: Map[String, Map[String, Int]] =
            ix.uses.toVector.flatMap((from, row) => row.map((to, n) => (to, from, n)))
              .groupMap(_._1)(t => t._2 -> t._3).view.mapValues(_.toMap).toMap
          val body = ms.flatMap { m =>
            val uses = ix.uses.getOrElse(m, Map.empty).toVector.sortBy(-_._2)
            val by = usedBy.getOrElse(m, Map.empty).toVector.sortBy(-_._2)
            // everything a change here can reach: used-by, transitively; the set bounds the walk
            val reach = collection.mutable.LinkedHashSet.empty[String]
            var frontier = Vector(m)
            while frontier.nonEmpty do
              val next = frontier.flatMap(x => usedBy.getOrElse(x, Map.empty).keys).filter(x => x != m && !reach.contains(x)).distinct
              next.foreach(reach += _)
              frontier = next
            Vector(
              s"$m  (${ix.entries.count(_.db.module == m)} files)",
              s"  uses: ${if uses.isEmpty then "-" else uses.map((x, n) => s"$x ($n refs)").mkString(", ")}",
              s"  used by: ${if by.isEmpty then "-" else by.map((x, n) => s"$x ($n refs)").mkString(", ")}",
              s"  a change here reaches: ${if reach.isEmpty then "-" else reach.toVector.sorted.mkString(", ")}")
          }
          (Vector(header(g), "(a dependency here is a USE: a reference from one module's code to a symbol defined in another)") ++ body).mkString("\n")

  /**
   * The text of one definition and nothing else: from its first line to
   * the end of its body, read by indentation (a line indented deeper
   * than the definition, a blank, or the closing brace at its own
   * indentation). TASTy gives the exact span; without it (a class TASTy could not
   * read) the body is read by indentation, and `maxLines` bounds it either way.
   */
  def body(ix: Index, l: Loc, maxLines: Int): (Vector[String], Int) =
    val lines = ws.sources.lines(l.source)
    val at = l.range.startLine
    if at >= lines.size then (Vector.empty, 0)
    else
      val all = ix.spanAt(l) match
        case Some(sp) => lines.slice(at min sp.startLine, (sp.endLine + 1) min lines.size) // exact, from TASTy
        case None => lines.slice(at, byIndentation(lines, at))
      (all.take(maxLines), all.size)

  /** where a body ends when TASTy has no span: the first line back at the definition's indentation */
  private def byIndentation(lines: Vector[String], at: Int): Int =
    def indent(t: String): Int = t.takeWhile(_ == ' ').length
    val base = indent(lines(at))
    var end = at + 1
    var go = true
    while go && end < lines.size do
      val t = lines(end)
      if t.trim.isEmpty || indent(t) > base then end += 1
      else
        if t.trim.headOption.exists(c => c == '}' || c == ')') then end += 1
        go = false
    while end > at + 1 && lines(end - 1).trim.isEmpty do end -= 1
    end

  def source(a: Json): String =
    val g = ws.generation
    val ix = g.index
    val maxLines = int(a, "maxLines", 80)
    symbolsOf(ix, need(a, "query")) match
      case Left(why) => s"${header(g)}\n$why"
      case Right(ss) if ss.size > 1 && !oneName(ss) => s"${header(g)}\n${candidates(ix, ss)}"
      case Right(ss) =>
        val out = ss.flatMap { s =>
          ix.definitions.getOrElse(s, Vector.empty).flatMap { l =>
            val (text, total) = body(ix, l, maxLines)
            val cut = if total > text.size then Vector(s"  … ${total - text.size} more lines (raise `maxLines`)") else Vector.empty
            (s"$s  ${l.show}  (${total} lines)" +: text.zipWithIndex.map((t, i) => f"${l.line + i}%5d  $t")) ++ cut
          }
        }
        if out.isEmpty then s"${header(g)}\n${ss.mkString(", ")}: defined outside the index"
        else (header(g) +: out).mkString("\n")

  /** a file's definitions, nested, one line each: what a file IS, without reading it */
  def outline(a: Json): String =
    val g = ws.generation
    val ix = g.index
    val path = need(a, "file")
    ix.fileFor(path) match
      case es if es.isEmpty => s"${header(g)}\nno indexed file matches '$path'"
      case es if es.size > 1 => s"${header(g)}\n'$path' matches ${es.size} files:\n" + es.map("  " + _.file).mkString("\n")
      case es =>
        val e = es.head
        val defs = ix.outlineOf(e.file)
        val depth0 = defs.map((_, s) => Symbols.names(s).size).minOption.getOrElse(0)
        val total = ws.sources.lines(e.source).size
        val rows = defs.map { (r, s) =>
          val i = ix.infos.get(s)
          val kind = i.map(_.kind) match
            case Some(Info.Method) => if i.exists(_.isGiven) then "given" else "def"
            case Some(Info.Field) => "val"
            case Some(Info.Macro) => "inline def"
            case Some(k) => Info.kindNames.getOrElse(k, "")
            case None => ""
          f"${r.startLine + 1}%5d  ${"  " * (Symbols.names(s).size - depth0)}$kind ${Symbols.name(s)}"
        }
        (Vector(header(g), s"${e.file}  ($total lines, ${defs.size} definitions; `source` for one body)") ++ rows).mkString("\n")

  def status(@annotation.unused a: Json): String =
    val g = ws.generation
    val ix = g.index
    val stale = ws.stale(g)
    val lines = Vector(
      header(g),
      s"root: ${ws.root.toAbsolutePath.normalize}",
      s"semanticdb files: ${g.files.size}, documents indexed: ${ix.entries.size}, modules: ${ix.modules.size}",
      s"symbols: ${ix.infos.size}, occurrences: ${ix.occurrenceCount}, built in ${g.buildMillis} ms",
      s"TASTy: ${ix.spans.loaded} of ${ix.spans.directories} class directories read so far, " +
        s"${ix.spans.loadedFromDisk} of them from .symdex/tasty " +
        s"(${ix.spans.size} definition spans, ${ix.spans.classes} classes; the rest load on first use)" +
        (if ix.spans.skipped > 0 then s"; ${ix.spans.skipped} classes unreadable without their dependencies (approximate spans there)" else ""),
      s"tool schemas: ${specs.size} tools, ~${Tools.schemaTokens(specs)} tokens per turn " +
        s"(lean: ~${Tools.schemaTokens(specs.map(Tools.lean))}; `serve --tools a,b --lean` to pay less)",
      if stale.isEmpty then "every indexed source is older than its SemanticDB"
      else s"${stale.size} sources edited since they were compiled (answers about them may be off until a compile):"
    ) ++ stale.take(10).map(e => s"  ${e.file}") ++
      (if g.unreadable.isEmpty && ix.spans.failures.isEmpty then Vector.empty
       else "unreadable:" +: (g.unreadable ++ ix.spans.failures).take(10).map("  " + _)) ++
      (if g.files.isEmpty then Vector(
        "no SemanticDB found: compile with it on — in sbt, `set every semanticdbEnabled := true` then `Test/compile`")
       else Vector.empty)
    lines.mkString("\n")

  // ---- declaration ---------------------------------------------------------

  private def schema(props: (String, String, String)*)(required: String*): Json =
    JObj(Vector(
      "type" -> JStr("object"),
      "properties" -> JObj(props.toVector.map((n, t, d) =>
        n -> JObj(Vector("type" -> JStr(t), "description" -> JStr(d))))),
      "required" -> JArr(required.toVector.map(JStr(_)))))

  private val query = ("query", "string",
    "a name (`joinSorted`, `Bulk.joinSorted`), a SemanticDB symbol (`okay/stream/Bulk#joinSorted().`) or a position `path:line[:col]`")

  /**
   * Every answer has a size limit, because an answer is context the
   * agent pays for on every later turn (the "context diet" PostToolUse
   * hook, specs/symdex.md: 89.9% of a week's large outputs were never
   * needed whole). Past `budget` characters (default 8 000, about 2 000
   * tokens) an answer is COMPRESSED, not cut: its header, the files it
   * names with how many lines each, and as many first lines as fit — all
   * exact, taken from the answer itself, so nothing can be invented —
   * and the whole answer is archived for `more` to page through.
   * Exact reads (`source`, `definition`) are never summarized, only
   * paged, the hook's own rule for `cat`.
   */
  private val DefaultBudget = 8000

  private val PathLine = """([\w./-]+\.(?:scala|java|sc)):(\d+)""".r

  /** the answer's own size, on its first line: what the agent is about to pay */
  private def sized(out: String): String =
    val nl = out.indexOf('\n')
    val (first, rest) = if nl < 0 then (out, "") else (out.substring(0, nl), out.substring(nl))
    if first.startsWith("[generation ") && first.endsWith("]") then
      first.dropRight(1) + s", ~${Tools.tokens(out)} tokens]" + rest
    else out

  private def compressed(run: Json => String): Json => String = a => sized(compressedRaw(run)(a))

  private def compressedRaw(run: Json => String): Json => String = a =>
    val out = run(a)
    val budget = int(a, "budget", DefaultBudget)
    if out.length <= budget then out
    else
      val id = archive.put(out)
      val lines = out.linesIterator.toVector
      val files = lines.flatMap(l => PathLine.findFirstMatchIn(l).map(_.group(1)))
        .groupMapReduce(identity)(_ => 1)(_ + _).toVector.sortBy((f, n) => (-n, f))
      val head = lines.take(2)
      val fileRows = files.take(15).map((f, n) => s"  $f  ($n)") ++
        (if files.size > 15 then Vector(s"  … ${files.size - 15} more files") else Vector.empty)
      val room = budget / 2
      val first = lines.drop(2).foldLeft(Vector.empty[String]) { (acc, l) =>
        if acc.map(_.length + 1).sum + l.length < room then acc :+ l else acc
      }
      (head ++
        Vector(s"[compressed: ${lines.size} lines, ${out.length} chars over a budget of $budget; " +
          s"the whole answer is archived as `$id` — `more id=$id from=${2 + first.size + 1}` pages it, " +
          "or narrow the query (`in`, `limit`, a qualified name)]") ++
        (if files.nonEmpty then s"files named (${files.size}):" +: fileRows else Vector.empty) ++
        ("first lines:" +: first)).mkString("\n")

  /** exact reads: paged at a line, never summarized */
  private def paged(run: Json => String): Json => String = a => sized(pagedRaw(run)(a))

  private def pagedRaw(run: Json => String): Json => String = a =>
    val out = run(a)
    val budget = int(a, "budget", DefaultBudget)
    if out.length <= budget then out
    else
      val id = archive.put(out)
      val at = out.lastIndexOf('\n', budget) match
        case -1 => budget
        case i => i
      val shown = out.substring(0, at)
      val next = shown.count(_ == '\n') + 2
      shown + s"\n[paged: ${out.length} chars over a budget of $budget; `more id=$id from=$next` continues]"

  /** a page of an archived answer: lines [from, from + lines) */
  def more(a: Json): String =
    val id = need(a, "id")
    val from = int(a, "from", 1) max 1
    val count = int(a, "lines", 150) max 1
    archive.get(id) match
      case None => s"no archived answer `$id` (archives live for this server's lifetime)"
      case Some(text) =>
        val lines = text.linesIterator.toVector
        val page = lines.slice(from - 1, from - 1 + count)
        val rest = lines.size - (from - 1 + page.size)
        (s"[`$id` lines $from-${from + page.size - 1} of ${lines.size}]" +: page :++
          (if rest > 0 then Vector(s"[`more id=$id from=${from + page.size}` for the next $rest lines]") else Vector.empty))
          .mkString("\n")

  private val budget = ("budget", "integer", "the answer's size limit in characters (default 8000)")

  val box: Toolbox = Toolbox.empty
    .raw("definition",
      "Where a symbol is defined: its file:line, kind, doc comment and signature lines. " +
        "Use instead of grepping for `def name`/`class Name`.",
      schema(query, budget)("query"))(paged(definition))
    .raw("references",
      "Every use of a symbol, exact (resolved by the compiler, not by text): renamed imports, " +
        "extension calls and overloads included, comments and strings excluded. " +
        "`callers: true` groups them by the enclosing definition; `context: true` shows each line.",
      schema(query,
        ("callers", "boolean", "group by the enclosing definition"),
        ("context", "boolean", "show each reference's source line"),
        ("all", "boolean", "when the name matches several different symbols, answer for all of them"),
        ("in", "string", "only references in files or modules whose name contains this"),
        ("limit", "integer", "at most this many references (default 300)"), budget)("query"))(compressed(references))
    .raw("implementations",
      "Everything that implements a type or overrides a member, transitively: subclasses, " +
        "objects, overriding methods, and the given instances of a type class.",
      schema(query, budget)("query"))(compressed(implementations))
    .raw("givens",
      "What the compiler inserted at a line: which given/implicit each call there resolved to, " +
        "implicit conversions, inferred applies. The one question grep cannot answer.",
      schema(("at", "string", "path:line[:col]"), budget)("at"))(compressed(givens))
    .raw("members",
      "A type's members: declared, then inherited from parents in the index, each with where it is defined.",
      schema(query, ("all", "boolean", "include compiler-generated members (case class copy, …)"), budget)("query"))(compressed(members))
    .raw("modules",
      "Modules and which use which (from actual references, not build declarations). With `query` " +
        "(a module or a file path): what it uses, what uses it, and every module a change there can reach.",
      schema(("query", "string", "a module name or a source path; omit to list all"), budget)())(compressed(modules))
    .raw("source",
      "The source text of one definition — its body, not its file. Use instead of reading a " +
        "whole file to see one method or class.",
      schema(query, ("maxLines", "integer", "at most this many lines (default 80)"), budget)("query"))(paged(source))
    .raw("outline",
      "A file's definitions, nested, one line each with its line number. Use instead of reading " +
        "a file to learn what is in it; then `source` the one you need.",
      schema(("file", "string", "a path, or its tail (`Tables.scala`)"), budget)("file"))(compressed(outline))
    .raw("more",
      "A page of an answer that was too long and was archived: its id is in that answer.",
      schema(("id", "string", "the archive id from a compressed or paged answer"),
        ("from", "integer", "first line, 1-based (default 1)"),
        ("lines", "integer", "how many lines (default 150)"), budget)("id"))(paged(more))
    .raw("status",
      "The index: its generation and age, what it covers, and which sources were edited since they were compiled.",
      schema()())(compressed(status))

  /** read every module's TASTy now (a server calls this once, in the background) */
  def warm(): Unit =
    try ws.generation.index.spans.warm()
    catch case e: Exception => System.err.println(s"symdex: TASTy warm-up: ${e.getMessage}")

  def specs: Seq[ToolSpec] = box.specs
  def table: Map[String, ToolCall => String] = box.table

  /**
   * The server as one session wants it. A tool schema is context paid
   * on EVERY turn whether the tool is called or not (rozum's gateway
   * counts Claude Code's 33 at ~5K tokens), so a session can take only
   * the tools it uses (`only`, okay-mcp's `Serving.only`: absent, not
   * refused) and terse descriptions (`lean`: the first sentence, no
   * per-argument prose; rozum's lazy-tools step, as a choice made up
   * front instead of under overflow).
   */
  def serving(only: Option[Set[String]], lean: Boolean): Server.Serving =
    val all = Server.Serving(Mcp.Info("symdex", Symdex.version), specs, table)
    val narrowed = only.fold(all)(names => all.only(names))
    if lean then narrowed.copy(tools = narrowed.tools.map(Tools.lean)) else narrowed

object Tools:

  /** tokens, roughly: rozum's gateway estimate (chars / 3.5), the same for answers and schemas */
  def tokens(s: String): Int = (s.length / 3.5).toInt + 1

  /** what the tool list costs in context: the `tools/list` result as the client receives it */
  def schemaTokens(specs: Seq[ToolSpec]): Int = tokens(Json.print(Mcp.toolsResult(specs)))

  /** the first sentence of the description, and the schema with no per-argument descriptions */
  def lean(t: ToolSpec): ToolSpec =
    val first = t.description.indexOf(". ") match
      case -1 => t.description
      case i => t.description.substring(0, i + 1)
    def strip(j: Json): Json = j match
      case JObj(fs) => JObj(fs.collect {
        case ("properties", JObj(ps)) => "properties" -> JObj(ps.map((n, p) => n -> (p match
          case JObj(pf) => JObj(pf.filterNot(_._1 == "description"))
          case other => other)))
        case kv => kv
      })
      case other => other
    t.copy(description = first, schema = strip(t.schema))
