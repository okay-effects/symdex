package symdex

import okay.scala3.*
import okay.scala3.std.Clock

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** One built index, and when and from what it was built. */
final case class Generation(number: Int, index: Index, files: Vector[DbFile],
                            builtAt: Long, buildMillis: Long, unreadable: Vector[String]):
  def signature: Set[(Path, Long, Long)] = files.map(f => (f.path, f.mtime, f.size)).toSet

/** what TASTy covers so far: class directories, read and how, their classes and spans, and what failed */
final case class Coverage(directories: Int, loaded: Int, fromDisk: Int, classes: Int, skipped: Int, spans: Int,
                          failures: Vector[String])

object Coverage:
  val none: Coverage = Coverage(0, 0, 0, 0, 0, 0, Vector.empty)

/**
 * THE CODEBASE, AS THE TOOLS ASK IT (specs/symdex.md, v0.7): the index, fresh; a SemanticDB root's definition
 * spans; a source's lines; the sources edited since they were compiled; the extension methods by receiver; what
 * TASTy covers; every TASTy read now. Where it comes from is the handler's: the compilers' output on a disk
 * (`compiled`), or an index given (`fixed`, a test's — no disk, no compiler).
 */
enum Code[X]:
  case Root() extends Code[Path]
  case Current() extends Code[Generation]
  case Table(root: Path) extends Code[SpanTable]
  case Lines(source: Path) extends Code[Vector[String]]
  case Stale(g: Generation) extends Code[Vector[Entry]]
  case Extensions() extends Code[Map[String, Vector[String]]]
  case Covered() extends Code[Coverage]
  case Warm() extends Code[Unit]

object Code:
  def root[R[_]](using In[Code, R]): Path ! R = perform[Code](Root())
  def current[R[_]](using In[Code, R]): Generation ! R = perform[Code](Current())
  def table[R[_]](root: Path)(using In[Code, R]): SpanTable ! R = perform[Code](Table(root))
  def lines[R[_]](source: Path)(using In[Code, R]): Vector[String] ! R = perform[Code](Lines(source))
  def line[R[_]](source: Path, n: Int)(using In[Code, R]): String ! R = lines(source).map(_.lift(n - 1).getOrElse(""))
  def stale[R[_]](g: Generation)(using In[Code, R]): Vector[Entry] ! R = perform[Code](Stale(g))
  def extensions[R[_]](using In[Code, R]): Map[String, Vector[String]] ! R = perform[Code](Extensions())
  def covered[R[_]](using In[Code, R]): Coverage ! R = perform[Code](Covered())
  def warm[R[_]](using In[Code, R]): Unit ! R = perform[Code](Warm())

  /** the full extent of the definition named at `l` */
  def spanAt[R[_]](ix: Index, l: Loc)(using In[Code, R]): Option[Span] ! R =
    ix.entryFor(l.file) match
      case None => pure(None)
      case Some(e) => table(e.db.root).map(t => ix.spanAt(l, t))

  /** the definition a reference sits in: exact where TASTy has the spans */
  def enclosing[R[_]](ix: Index, l: Loc)(using In[Code, R]): Option[String] ! R =
    ix.entryFor(l.file) match
      case None => pure(ix.enclosing(l, SpanTable.empty))
      case Some(e) => table(e.db.root).map(t => ix.enclosing(l, t))

  /** AN INDEX GIVEN: its tables and lines as given, nothing stale, nothing to read */
  def fixed[R[_], Rest[_], A](root: Path, index: Index, tables: Map[Path, SpanTable] = Map.empty,
                              lines: Map[Path, Vector[String]] = Map.empty, builtAt: Long = 0L)(p: A ! R)(
    using Split[Code, R, Rest]): A ! Rest =
    val g = Generation(1, index, Vector.empty, builtAt, 0L, Vector.empty)
    handle(p, Handler.tail[Code, Rest, A, A](pure)([X] => (op: Code[X]) => op match
      case Root() => root
      case Current() => g
      case Table(r) => tables.getOrElse(r, SpanTable.empty)
      case Lines(s) => lines.getOrElse(s, Vector.empty[String])
      case Stale(_) => Vector.empty[Entry]
      case Extensions() => index.extensionsOn(r => tables.getOrElse(r, SpanTable.empty))
      case Covered() => Coverage.none
      case Warm() => ()))

  /**
   * THE COMPILERS' OUTPUT ON A DISK, kept fresh: before answering, the SemanticDB under `root` is looked at (at most
   * once a `recheckMillis`); when anything was added, removed or rewritten — what a compile does — a new generation
   * is built, re-reading only the files that changed, and swapped in whole. TASTy is read per class directory on
   * first use and saved under `.symdex/tasty`; a later process with the same stamp reads the file instead. A program
   * over `Disk`, `Tasty` and `Clock`; what it keeps between calls is `kept`'s.
   */
  def compiled[R[_], Rest[_], A](root: Path, kept: Kept, include: DbFile => Boolean = _ => true,
                                 recheckMillis: Long = 2000)(p: A ! R)(
    using Split[Code, R, Rest], In[Disk, Rest], In[Tasty, Rest], In[Clock, Rest]): A ! Rest =
    val c = Compiled[Rest](root.toAbsolutePath.normalize, kept, include, recheckMillis)
    handle(p, Handler.general[Code, Rest, A, A](pure)([X] => (op: Code[X], k: X => A ! Rest) => op match
      case Root() => k(c.base)
      case Current() => c.current.flatMap(k)
      case Table(r) => c.table(r).flatMap(k)
      case Lines(s) => c.lines(s).flatMap(k)
      case Stale(g) => c.stale(g).flatMap(k)
      case Extensions() => c.extensions.flatMap(k)
      case Covered() => c.coverage.flatMap(k)
      case Warm() => c.warm.flatMap(k)))

/** a SemanticDB root's class directory: its TASTy, kept while that root's SemanticDB is unchanged (`stamp`) */
final case class Classes(root: Path, dir: Path, stamp: String, saved: Path, read: Option[Tasty.Read] = None,
                         table: SpanTable = SpanTable.empty, fromDisk: Boolean = false,
                         failure: Option[String] = None)

/** what `Code.compiled` keeps between calls: the generation, and what it was built from */
final case class Held(
  generation: Option[Generation] = None,
  checkedAt: Long = 0L,
  /** each SemanticDB or SCIP file's documents, by the time and size they were read at */
  parsed: Map[Path, (Long, Long, Vector[Document])] = Map.empty,
  /** a SCIP index's own spans (its enclosing ranges), by index file */
  scipSpans: Map[Path, SpanTable] = Map.empty,
  sourceRoots: Map[(Path, String), Option[Path]] = Map.empty,
  /** TASTy by SemanticDB root */
  tasty: Map[Path, Classes] = Map.empty,
  /** a source's lines, by the time it was read at */
  lines: Map[Path, (Long, Vector[String])] = Map.empty,
  /** the extension methods, by generation */
  extensions: Option[(Int, Map[String, Vector[String]])] = None)

/** one server's memory for `Code.compiled`: shared by its calls and its background warm-up, updated whole */
final class Kept:
  private val ref = AtomicReference(Held())
  def get: Held = ref.get
  def update(f: Held => Held): Held = ref.updateAndGet(h => f(h))

/** `Code.compiled`'s answers, each a program over `Disk`, `Tasty` and `Clock` */
private final class Compiled[R[_]](val base: Path, kept: Kept, include: DbFile => Boolean, recheckMillis: Long)(
  using In[Disk, R], In[Tasty, R], In[Clock, R]):

  def current: Generation ! R =
    Clock.now[R].flatMap { now =>
      val h = kept.get
      h.generation match
        case Some(g) if now - h.checkedAt < recheckMillis => pure(g)
        case old =>
          Scan.files[R](base).map(_.filter(include)).flatMap { files =>
            old match
              case Some(same) if same.signature == files.map(f => (f.path, f.mtime, f.size)).toSet =>
                kept.update(_.copy(checkedAt = now))
                pure(same)
              case _ => build(files, old.fold(1)(_.number + 1), now)
          }
    }

  private def rel(p: Path): String = base.relativize(p).toString

  /** a file's documents — kept from before while its time and size are unchanged — and a SCIP index's spans */
  private def parse(f: DbFile): Either[String, (Vector[Document], Option[SpanTable])] ! R =
    kept.get.parsed.get(f.path) match
      case Some((m, s, d)) if m == f.mtime && s == f.size => pure(Right((d, None)))
      case _ => Disk.read[R](f.path).map {
        case None => Left(s"${rel(f.path)}: unreadable")
        case Some(bytes) =>
          try
            if f.scip then
              val r = Scip.read(bytes)
              Right((r.documents, Some(SpanTable.from(r.spans))))
            else Right((Semanticdb.read(bytes), None))
          catch case e: Exception => Left(s"${rel(f.path)}: ${e.getMessage}")
      }

  private def build(files: Vector[DbFile], number: Int, now: Long): Generation ! R =
    each(files)(f => parse(f).map(f -> _)).flatMap { read =>
      val ok = read.collect { case (f, Right((docs, spans))) => (f, docs, spans) }
      val live = files.map(_.path).toSet
      kept.update(h => h.copy(
        parsed = (h.parsed ++ ok.map((f, d, _) => f.path -> (f.mtime, f.size, d))).filter((p, _) => live(p)),
        scipSpans = (h.scipSpans ++ ok.collect { case (f, _, Some(t)) => f.path -> t }).filter((p, _) => live(p))))
      // one source compiled for several platforms is one file: the first
      // (sorted, so the JVM build before `(js)`/`(native)`) is kept
      val docs = ok.flatMap((f, ds, _) => ds.map(f -> _)).sortBy((f, d) => (f.module.contains(" ("), f.module, d.uri))
      each(docs)((f, d) => sourceOf(f, d.uri).map(_.map(src => Entry(f, src, display(src), d)))).flatMap { found =>
        val entries = found.flatten.distinctBy(_.source)
        Clock.now[R].map { end =>
          val g = Generation(number, Index(base, entries), files, end, end - now,
            read.collect { case (_, Left(why)) => why })
          // TASTy per class directory: kept while that root's SemanticDB is unchanged (its stamp)
          val roots = files.filterNot(_.scip).groupBy(_.root).view.mapValues(Compiled.stamp).toMap
          val savedIn = base.resolve(".symdex").resolve("tasty")
          kept.update(h => h.copy(generation = Some(g), checkedAt = now, extensions = None,
            tasty = roots.map { (root, stamp) =>
              root -> (h.tasty.get(root) match
                case Some(c) if c.stamp == stamp => c
                case _ =>
                  val dir = Tasty.classDirOf(root)
                  Classes(root, dir, stamp, SavedSpans.fileFor(savedIn, dir)))
            }))
          g
        }
      }
    }

  /**
   * A document's uri is relative to its build's source root, which is not written down beside it; the first
   * ancestor of the SemanticDB directory where the uri names an existing file is it.
   */
  private def sourceOf(f: DbFile, uri: String): Option[Path] ! R =
    val top = Option(uri.split('/').head).getOrElse(uri)
    kept.get.sourceRoots.get((f.root, top)) match
      case Some(r) => r match
        case None => pure(None)
        case Some(dir) => Disk.exists[R](dir.resolve(uri)).map(there => Option.when(there)(dir.resolve(uri)))
      case None =>
        loop[Option[Path], Option[Path], R](Some(f.root)) {
          case None => pure(Right(None))
          case Some(d) => Disk.exists[R](d.resolve(uri)).map(there =>
            if there then Right(Some(d)) else Left(Option(d.getParent)))
        }.map { found =>
          kept.update(h => h.copy(sourceRoots = h.sourceRoots.updated((f.root, top), found)))
          found.map(_.resolve(uri))
        }

  private def display(p: Path): String =
    if p.startsWith(base) then base.relativize(p).toString.replace('\\', '/') else p.toString

  /** a root's spans as kept now: a SCIP index's own, or its class directory's TASTy if read */
  private def tableNow(h: Held, root: Path): SpanTable =
    val scip = h.generation.toVector.flatMap(_.files).filter(f => f.scip && f.root == root)
    if scip.nonEmpty then
      scip.map(f => h.scipSpans.getOrElse(f.path, SpanTable.empty)).foldLeft(SpanTable.empty)(_ ++ _)
    else h.tasty.get(root).map(_.table).getOrElse(SpanTable.empty)

  def table(root: Path): SpanTable ! R =
    kept.get.tasty.get(root) match
      case Some(c) if c.read.isEmpty => load(Vector(c)).map(_ => tableNow(kept.get, root))
      case _ => pure(tableNow(kept.get, root))

  /** class directories' TASTy: from their saved files where the stamp matches, else read (and saved) */
  private def load(cs: Vector[Classes]): Unit ! R =
    each(cs)(c => Disk.read[R](c.saved).map(b => c -> b.flatMap(SavedSpans.decode(_, c.dir, c.stamp))))
      .flatMap { tried =>
      val fromFile = tried.collect { case (c, Some(r)) =>
        c.copy(read = Some(r), table = SpanTable.from(r.spans), fromDisk = true) }
      val missing = tried.collect { case (c, None) => c }
      (if missing.isEmpty then pure[Vector[(Classes, Either[String, Tasty.Read])], R](Vector.empty)
       else Tasty.classes[R](missing.map(_.dir)).map(missing.zip(_))).flatMap { reads =>
        each(reads.collect { case (c, Right(r)) => (c, r) })((c, r) =>
          Disk.write[R](c.saved, SavedSpans.encode(c.dir, c.stamp, r))).map { _ =>
          val readNow = reads.map {
            case (c, Right(r)) => c.copy(read = Some(r), table = SpanTable.from(r.spans))
            case (c, Left(why)) => c.copy(read = Some(Tasty.Read(Map.empty, 0, 0)), failure = Some(why))
          }
          val done = (fromFile ++ readNow).map(c => (c.root, c.stamp) -> c).toMap
          kept.update(h => h.copy(tasty = h.tasty.map((root, c) => root -> done.getOrElse((root, c.stamp), c))))
          ()
        }
      }
    }

  /** every class directory not yet read, now: the handler of `Tasty` may read them in parallel */
  def warm: Unit ! R =
    current.flatMap(_ => load(kept.get.tasty.values.filter(_.read.isEmpty).toVector.sortBy(_.root.toString)))

  def extensions: Map[String, Vector[String]] ! R =
    current.flatMap { g =>
      kept.get.extensions match
        case Some((n, m)) if n == g.number => pure(m)
        case _ => warm.map { _ =>
          val h = kept.get
          val m = g.index.extensionsOn(root => tableNow(h, root))
          kept.update(_.copy(extensions = Some(g.number -> m)))
          m
        }
    }

  def stale(g: Generation): Vector[Entry] ! R =
    each(g.index.entries)(e => Disk.stat[R](e.source).map(n => Option.when(n.exists(_.mtime > e.db.mtime))(e)))
      .map(_.flatten)

  /** a source's lines, read again when the file is newer than when it was read */
  def lines(source: Path): Vector[String] ! R =
    Disk.stat[R](source).flatMap { n =>
      val at = n.map(_.mtime).getOrElse(-1L)
      kept.get.lines.get(source) match
        case Some((t, ls)) if t == at => pure(ls)
        case _ => Disk.read[R](source).map { b =>
          val ls = b.map(Disk.lines).getOrElse(Vector.empty)
          kept.update(h => h.copy(lines = h.lines.updated(source, at -> ls)))
          ls
        }
    }

  def coverage: Coverage ! R =
    current.map { g =>
      val h = kept.get
      val cs = h.tasty.values.toVector
      val done = cs.flatMap(c => c.read.map(c -> _))
      val scip = g.files.filter(_.scip).map(f => h.scipSpans.getOrElse(f.path, SpanTable.empty).valuesIterator
        .map(_.size).sum).sum
      Coverage(cs.size, done.size, done.count(_._1.fromDisk), done.map(_._2.classes).sum, done.map(_._2.skipped).sum,
        done.map(_._2.spans.size).sum + scip, cs.flatMap(c => c.failure.map(m => s"tasty ${c.dir}: $m")))
    }

object Compiled:
  /** one SemanticDB directory's files as a key: a compile rewrites at least one, a deleted source removes one */
  def stamp(files: Vector[DbFile]): String =
    s"${files.size}:${files.map(_.mtime).max}:${files.map(_.size).sum}"
