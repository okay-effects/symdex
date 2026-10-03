package symdex

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference

/** One built index, and when and from what it was built. */
final case class Generation(number: Int, index: Index, files: Vector[DbFile],
                            builtAt: Long, buildMillis: Long, unreadable: Vector[String]):
  def signature: Set[(Path, Long, Long)] = files.map(f => (f.path, f.mtime, f.size)).toSet

/**
 * A workspace keeps the current Generation fresh. Before answering, it
 * looks at the SemanticDB on disk (at most once per `recheckMillis`):
 * when anything was added, removed or rewritten — which is what a
 * compile does — it builds a new Generation, re-reading only the files
 * that changed, and swaps it in whole. A query never sees a half-built
 * index, and needs no hook: compiling is the trigger.
 */
final class Workspace(val root: Path, recheckMillis: Long = 2000,
                      include: DbFile => Boolean = _ => true):
  private val base = root.toAbsolutePath.normalize
  private val current = AtomicReference[Generation | Null](null)
  private var checkedAt = 0L
  private var parsed = Map.empty[Path, (Long, Long, Vector[Document])]
  /** a SCIP index's own spans (its enclosing ranges), by index file */
  private var scipSpans = Map.empty[Path, Map[String, Map[(Int, Int), Span]]]
  private var sourceRoots = Map.empty[(Path, String), Option[Path]]
  /** TASTy read per class directory, kept while that module's SemanticDB is unchanged */
  private var tasty = Map.empty[Path, TastyDir]
  val sources: Sources = Sources()

  def generation: Generation = synchronized {
    val now = System.currentTimeMillis()
    current.get() match
      case g: Generation if now - checkedAt < recheckMillis => g
      case g =>
        checkedAt = now
        val files = Scan.files(base).filter(include)
        g match
          case same: Generation if same.signature == files.map(f => (f.path, f.mtime, f.size)).toSet => same
          case _ =>
            val next = build(files, g match { case p: Generation => p.number + 1; case null => 1 })
            current.set(next)
            next
  }

  private def build(files: Vector[DbFile], number: Int): Generation =
    val start = System.currentTimeMillis()
    val unreadable = Vector.newBuilder[String]
    val docs = files.flatMap { f =>
      val read = parsed.get(f.path) match
        case Some((m, s, d)) if m == f.mtime && s == f.size => Some(d)
        case _ =>
          try
            val bytes = Files.readAllBytes(f.path)
            val d =
              if f.scip then
                val r = Scip.read(bytes)
                scipSpans = scipSpans.updated(f.path,
                  r.spans.toVector.groupMap(_._1._1)((k, v) => (k._2, k._3) -> v).view.mapValues(_.toMap).toMap)
                r.documents
              else Semanticdb.read(bytes)
            parsed = parsed.updated(f.path, (f.mtime, f.size, d))
            Some(d)
          catch case e: Exception =>
            unreadable += s"${base.relativize(f.path)}: ${e.getMessage}"
            None
      read.getOrElse(Vector.empty).map(f -> _)
    }
    val live = files.map(_.path).toSet
    parsed = parsed.filter((p, _) => live(p))
    // one source compiled for several platforms is one file: the first
    // (sorted, so the JVM build before `(js)`/`(native)`) is kept
    val entries = docs.sortBy((f, d) => (f.module.contains(" ("), f.module, d.uri))
      .flatMap((f, d) => sourceOf(f, d.uri).map(src => Entry(f, src, display(src), d)))
      .distinctBy(_.source)
    // TASTy per class directory: kept while that module's SemanticDB is
    // unchanged (its stamp), read lazily (on first use, or by `warm` in a server), and saved
    // under .symdex/tasty for the next process
    val roots = files.filterNot(_.scip).groupBy(_.root).view.mapValues(Workspace.stamp).toMap
    tasty = roots.map { (root, stamp) =>
      root -> (tasty.get(root) match
        case Some(d) if d.stamp == stamp => d
        case _ =>
          val classDir = Tasty.classDirOf(root)
          TastyDir(classDir, stamp, Some(SavedSpans.fileFor(base.resolve(".symdex").resolve("tasty"), classDir))))
    }
    scipSpans = scipSpans.filter((p, _) => live(p))
    val spans = Spans(tasty, files.filter(_.scip).map(f => f.root -> scipSpans.getOrElse(f.path, Map.empty)).toMap)
    val index = Index(base, entries, spans)
    Generation(number, index, files, System.currentTimeMillis(), System.currentTimeMillis() - start,
      unreadable.result())

  /**
   * A document's uri is relative to its build's source root, which is not
   * written down beside it; the first ancestor of the SemanticDB directory
   * where the uri names an existing file is it.
   */
  private def sourceOf(f: DbFile, uri: String): Option[Path] =
    val top = Option(uri.split('/').head).getOrElse(uri)
    sourceRoots.get((f.root, top)) match
      case Some(r) => r.map(_.resolve(uri)).filter(Files.exists(_))
      case None =>
        var dir: Path | Null = f.root
        var found: Option[Path] = None
        while found.isEmpty && dir != null do
          val d: Path = dir.nn
          if Files.exists(d.resolve(uri)) then found = Some(d)
          dir = d.getParent
        sourceRoots = sourceRoots.updated((f.root, top), found)
        found.map(_.resolve(uri))

  private def display(p: Path): String =
    if p.startsWith(base) then base.relativize(p).toString.replace('\\', '/') else p.toString

  /** source files newer than the SemanticDB that describes them: compiled before their last edit */
  def stale(g: Generation): Vector[Entry] =
    g.index.entries.filter(e =>
      try Files.getLastModifiedTime(e.source).toMillis > e.db.mtime
      catch case _: java.io.IOException => false)

object Workspace:
  /** one SemanticDB directory's files as a key: a compile rewrites at least one, a deleted source removes one */
  def stamp(files: Vector[DbFile]): String =
    s"${files.size}:${files.map(_.mtime).max}:${files.map(_.size).sum}"
