package symdex

import okay.scala3.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream, IOException}
import java.net.URI
import java.nio.file.{FileSystems, Files, Path}

import scala.jdk.CollectionConverters.*

import tastyquery.Contexts.Context
import tastyquery.Symbols.TermSymbol
import tastyquery.Traversers.TreeTraverser
import tastyquery.Trees.{DefTree, Tree}
import tastyquery.jdk.ClasspathLoaders

/** A definition's full extent, 0-based lines, and whether it is an extension method. */
final case class Span(startLine: Int, endLine: Int, extension: Boolean)

/**
 * THE COMPILED TREES (specs/symdex.md, v0.7): class directories' TASTy read — each its definition spans, or why it
 * could not be read. Who reads them is the handler's: tasty-query (`query`, several directories in parallel), or
 * reads given (`fixed`, a test's).
 */
enum Tasty[X]:
  case Classes(dirs: Vector[Path]) extends Tasty[Vector[Either[String, Tasty.Read]]]

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

  def classes[R[_]](dirs: Vector[Path])(using In[Tasty, R]): Vector[Either[String, Read]] ! R =
    perform[Tasty](Classes(dirs))

  /** a directory read, or why not: never an exception */
  def attempt(dir: Path): Either[String, Read] =
    try Right(read(dir)) catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

  /** TASTY-QUERY: one directory in this thread, several in parallel (about a second each) */
  def query[R[_], Rest[_], A](p: A ! R)(using Split[Tasty, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Tasty, Rest, A, A](pure)([X] => (op: Tasty[X]) => op match
      case Classes(dirs) =>
        if dirs.size <= 1 then dirs.map(attempt)
        else
          val out = java.util.concurrent.ConcurrentHashMap[Path, Either[String, Read]]()
          java.util.ArrayList[Path](dirs.asJava).parallelStream().forEach(d => { out.put(d, attempt(d)): Unit })
          dirs.map(d => out.get(d))))

  /** reads given: a directory's read, or why not */
  def fixed[R[_], Rest[_], A](reads: Path => Either[String, Read])(p: A ! R)(using Split[Tasty, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Tasty, Rest, A, A](pure)([X] => (op: Tasty[X]) => op match
      case Classes(dirs) => dirs.map(reads)))

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

  /** the spans read back from a saved file's bytes — or none: another version, directory or stamp, or damaged */
  def decode(bytes: Array[Byte], classDir: Path, stamp: String): Option[Tasty.Read] =
    try
      val in = DataInputStream(ByteArrayInputStream(bytes))
      if in.readInt() != format || in.readUTF() != classDir.toString || in.readUTF() != stamp then None
      else
        val classes = in.readInt()
        val skipped = in.readInt()
        val paths = Vector.fill(in.readInt())(in.readUTF())
        val spans = Vector.fill(in.readInt())(
          (paths(in.readInt()), in.readInt(), in.readInt()) -> Span(in.readInt(), in.readInt(), in.readBoolean()))
        Some(Tasty.Read(spans.toMap, classes, skipped))
    catch case _: IOException | _: IndexOutOfBoundsException => None

  /** a directory's spans as a saved file's bytes */
  def encode(classDir: Path, stamp: String, r: Tasty.Read): Array[Byte] =
    val bytes = ByteArrayOutputStream(1 << 16)
    val out = DataOutputStream(bytes)
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
    out.flush()
    bytes.toByteArray
