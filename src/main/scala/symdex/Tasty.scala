package symdex

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream, IOException}
import java.net.URI
import java.nio.file.{FileSystems, Files, Path, StandardCopyOption}

import scala.jdk.CollectionConverters.*

import tastyquery.Contexts.Context
import tastyquery.Symbols.TermSymbol
import tastyquery.Traversers.TreeTraverser
import tastyquery.Trees.{DefTree, Tree}
import tastyquery.jdk.ClasspathLoaders

/** A definition's full extent, 0-based lines, and whether it is an extension method. */
final case class Span(startLine: Int, endLine: Int, extension: Boolean)

/**
 * What SemanticDB does not record, read from TASTy: where each
 * definition's BODY ends (SemanticDB has only the name's range), and
 * which methods are extension methods. Keyed by the source path as
 * TASTy wrote it and the position of the definition's name, which is
 * exactly SemanticDB's definition occurrence — the join between the two.
 *
 * The class directory is read with whatever else it needs for its trees
 * to unpickle: the Scala library this process runs on, and the JDK. A
 * project's other dependencies are absent, and a top-level class whose
 * trees cannot be read without them is skipped and counted, never fatal:
 * its definitions keep SemanticDB's approximations.
 */
object Tasty:

  final case class Read(spans: Map[(String, Int, Int), Span], classes: Int, skipped: Int)

  private lazy val baseClasspath: List[Path] =
    val jrt = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("modules", "java.base")
    val scalaJars = sys.props.getOrElse("java.class.path", "").split(java.io.File.pathSeparator).toList
      .filter(p => p.contains("scala3-library") || p.contains("scala-library"))
      .map(Path.of(_))
    jrt :: scalaJars

  def read(classDir: Path): Read =
    if !Files.isDirectory(classDir) then Read(Map.empty, 0, 0)
    else
      val entries = ClasspathLoaders.read(classDir :: baseClasspath)
      given ctx: Context = Context.initialize(entries)
      val out = collection.mutable.HashMap.empty[(String, Int, Int), Span]
      var classes = 0
      var skipped = 0
      // the traversal is tasty-query's, recursive over the tree; its depth
      // is the source's nesting depth, which the compiler recursed over too
      val collect = new TreeTraverser:
        override def traverse(t: Tree): Unit =
          t match
            case d: DefTree =>
              val p = t.pos
              if !p.isUnknown && p.hasLineColumnInformation && !p.isSynthetic then
                out((p.sourceFile.path, p.pointLine, p.pointColumn)) =
                  Span(p.startLine, p.endLine, d.symbol match
                    case t: TermSymbol => t.isExtensionMethod
                    case _ => false)
            case _ => ()
          super.traverse(t)
      for sym <- ctx.findSymbolsByClasspathEntry(entries.head) do
        classes += 1
        try sym.tree.foreach(collect.traverse)
        catch case _: Exception => skipped += 1
      Read(out.toMap, classes, skipped)

  /** the class directory beside a SemanticDB root: `meta` → `classes`, `test-meta` → `test-classes`, or the one it is in */
  def classDirOf(semanticdbRoot: Path): Path =
    val metaDir = semanticdbRoot.getParent.getParent // .../meta (or .../classes) above META-INF/semanticdb
    metaDir.getFileName.toString match
      case "meta" => metaDir.resolveSibling("classes")
      case "test-meta" => metaDir.resolveSibling("test-classes")
      case _ => metaDir

/**
 * One class directory's TASTy, read on first use and kept while its
 * SemanticDB is unchanged (`stamp`). With a `saved` file, a read is
 * written there and a later process with the same stamp reads the file
 * instead: reading TASTy costs about a second a directory, the file a
 * few milliseconds (specs/symdex.md, v0.6).
 */
final class TastyDir(val dir: Path, val stamp: String, saved: Option[Path] = None):
  @volatile private var failure: Option[String] = None
  @volatile private var fromFile = false
  lazy val read: Tasty.Read =
    saved.flatMap(f => SavedSpans.load(f, dir, stamp)) match
      case Some(r) =>
        fromFile = true
        r
      case None =>
        try
          val r = Tasty.read(dir)
          saved.foreach(f => SavedSpans.save(f, dir, stamp, r))
          r
        catch case e: Exception =>
          failure = Some(Option(e.getMessage).getOrElse(e.toString))
          Tasty.Read(Map.empty, 0, 0)
  def failed: Option[String] = failure
  /** read from its saved file rather than from TASTy */
  def fromDisk: Boolean = fromFile
  @volatile private var done = false
  def loaded: Boolean = done
  def force(): Tasty.Read = { val r = read; done = true; r }

/**
 * Spans by SemanticDB root, loaded per class directory when a tool first
 * asks about a file in it — `references` with callers loads only the
 * modules its references are in. TASTy writes a source path relative to
 * its build's root, as SemanticDB writes its uri: usually the same string;
 * when two builds disagree on the root (okay compiled from symdex's build),
 * the longer path ends with the shorter.
 */
final class Spans(dirs: Map[Path, TastyDir],
                  fixed: Map[Path, Map[String, Map[(Int, Int), Span]]] = Map.empty):
  private val byRoot = collection.concurrent.TrieMap.empty[Path, Map[String, Map[(Int, Int), Span]]]

  private def table(root: Path): Map[String, Map[(Int, Int), Span]] =
    byRoot.getOrElseUpdate(root, dirs.get(root) match
      case None => fixed.getOrElse(root, Map.empty)
      case Some(d) =>
        d.force().spans.toVector.groupMap(_._1._1)((k, v) => (k._2, k._3) -> v).view.mapValues(_.toMap).toMap)

  /** the span of the definition whose name SemanticDB puts at `r` in `uri`, under SemanticDB root `root` */
  def of(root: Path, uri: String, r: Range): Option[Span] =
    val t = table(root)
    val path = if t.contains(uri) then Some(uri) else t.keys.find(p => p.endsWith("/" + uri) || uri.endsWith("/" + p))
    path.flatMap(p => t(p).get((r.startLine, r.startChar)))

  /** read every class directory now, in parallel: a server does this once, in the background */
  def warm(): Unit =
    val all = java.util.ArrayList[Path](dirs.keys.toVector.sortBy(_.toString).toSeq.asJava)
    all.parallelStream().forEach(r => { val _ = table(r) })

  private def done = dirs.values.filter(_.loaded)
  def directories: Int = dirs.size
  def loaded: Int = done.size
  def loadedFromDisk: Int = done.count(_.fromDisk)
  def classes: Int = done.iterator.map(_.read.classes).sum
  def skipped: Int = done.iterator.map(_.read.skipped).sum
  def size: Int = done.iterator.map(_.read.spans.size).sum + fixed.valuesIterator.map(_.valuesIterator.map(_.size).sum).sum
  def failures: Vector[String] = dirs.values.flatMap(d => d.failed.map(m => s"tasty ${d.dir}: $m")).toVector

object Spans:
  val none: Spans = Spans(Map.empty)

/**
 * A class directory's spans on disk: a format version, the directory and
 * the stamp they were read under, then the source paths once and every
 * span. A file that does not match — another version, directory or
 * stamp, or truncated — is not read; one that cannot be written is not
 * written. Neither fails a query.
 */
object SavedSpans:
  private val format = 1

  /** where `classDir`'s spans are kept under `dir`: a name from its path */
  def fileFor(dir: Path, classDir: Path): Path =
    val digest = java.security.MessageDigest.getInstance("SHA-1").digest(classDir.toString.getBytes("UTF-8"))
    dir.resolve(digest.take(10).map(b => f"${b & 0xff}%02x").mkString + ".spans")

  def load(file: Path, classDir: Path, stamp: String): Option[Tasty.Read] =
    try
      val in = DataInputStream(BufferedInputStream(Files.newInputStream(file), 1 << 16))
      try
        if in.readInt() != format || in.readUTF() != classDir.toString || in.readUTF() != stamp then None
        else
          val classes = in.readInt()
          val skipped = in.readInt()
          val paths = Vector.fill(in.readInt())(in.readUTF())
          val spans = Vector.fill(in.readInt())(
            (paths(in.readInt()), in.readInt(), in.readInt()) -> Span(in.readInt(), in.readInt(), in.readBoolean()))
          Some(Tasty.Read(spans.toMap, classes, skipped))
      finally in.close()
    catch case _: IOException | _: IndexOutOfBoundsException => None

  def save(file: Path, classDir: Path, stamp: String, r: Tasty.Read): Unit =
    try
      val dir = file.toAbsolutePath.getParent
      Files.createDirectories(dir)
      // written whole beside it, then moved over it: a reader sees the old file or the new one
      val tmp = Files.createTempFile(dir, "spans", ".tmp")
      try
        val out = DataOutputStream(BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))
        try
          val paths = r.spans.keysIterator.map(_._1).toVector.distinct
          val index = paths.zipWithIndex.toMap
          out.writeInt(format)
          out.writeUTF(classDir.toString)
          out.writeUTF(stamp)
          out.writeInt(r.classes)
          out.writeInt(r.skipped)
          out.writeInt(paths.size)
          paths.foreach(out.writeUTF)
          out.writeInt(r.spans.size)
          for ((p, line, col), s) <- r.spans do
            out.writeInt(index(p)); out.writeInt(line); out.writeInt(col)
            out.writeInt(s.startLine); out.writeInt(s.endLine); out.writeBoolean(s.extension)
        finally out.close()
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE): Unit
      finally Files.deleteIfExists(tmp): Unit
    catch case _: IOException => ()
