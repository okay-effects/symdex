package symdex

import okay.scala3.*

import java.io.IOException
import java.nio.file.{Files as Nio, LinkOption, Path, StandardCopyOption}
import java.nio.file.attribute.BasicFileAttributes
import scala.collection.mutable

/** a directory's entry, or a file's attributes: a directory or not, when it was last written, how large */
final case class Node(path: Path, directory: Boolean, mtime: Long, size: Long):
  def name: String = Option(path.getFileName).map(_.toString).getOrElse("")

/**
 * THE DISK, as `Code`'s handler reads it (specs/symdex.md, v0.7): a directory's entries, a file's attributes, its
 * bytes, a file written whole, a file removed. A path that is not there, or cannot be read, is an empty answer —
 * never an exception. Where the files are is the handler's: the machine's disk (`real`), or a map of paths (`memory`).
 */
enum Disk[X]:
  /** a directory's entries, sorted by name; none when it is not a directory */
  case Entries(dir: Path) extends Disk[Vector[Node]]
  case Stat(path: Path) extends Disk[Option[Node]]
  case Read(path: Path) extends Disk[Option[Array[Byte]]]
  /** a file written whole: a reader sees the old file or the new one, never half; whether it was written */
  case Write(path: Path, bytes: Array[Byte]) extends Disk[Boolean]
  case Remove(path: Path) extends Disk[Unit]

object Disk:
  def entries[R[_]](dir: Path)(using In[Disk, R]): Vector[Node] ! R = perform[Disk](Entries(dir))
  def stat[R[_]](path: Path)(using In[Disk, R]): Option[Node] ! R = perform[Disk](Stat(path))
  def read[R[_]](path: Path)(using In[Disk, R]): Option[Array[Byte]] ! R = perform[Disk](Read(path))
  def write[R[_]](path: Path, bytes: Array[Byte])(using In[Disk, R]): Boolean ! R = perform[Disk](Write(path, bytes))
  def remove[R[_]](path: Path)(using In[Disk, R]): Unit ! R = perform[Disk](Remove(path))

  def isDirectory[R[_]](path: Path)(using In[Disk, R]): Boolean ! R = stat(path).map(_.exists(_.directory))
  def exists[R[_]](path: Path)(using In[Disk, R]): Boolean ! R = stat(path).map(_.isDefined)

  /** a text's lines as `Files.readAllLines` splits them: at `\n`, `\r\n` or `\r` */
  def lines(bytes: Array[Byte]): Vector[String] =
    val text = String(bytes, "UTF-8")
    if text.isEmpty then Vector.empty
    else
      val out = Vector.newBuilder[String]
      var from = 0
      var i = 0
      while i < text.length do
        val c = text.charAt(i)
        if c == '\n' || c == '\r' then
          out += text.substring(from, i)
          if c == '\r' && i + 1 < text.length && text.charAt(i + 1) == '\n' then i += 1
          from = i + 1
        i += 1
      if from < text.length then out += text.substring(from)
      out.result()

  /** THE MACHINE'S DISK: java.nio, links not followed (as a walk of the tree would not) */
  def real[R[_], Rest[_], A](p: A ! R)(using Split[Disk, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Disk, Rest, A, A](pure)([X] => (op: Disk[X]) => op match
      case Entries(dir) => list(dir)
      case Stat(path) => node(path)
      case Read(path) => try Some(Nio.readAllBytes(path)) catch case _: IOException => None
      case Write(path, bytes) => write(path, bytes)
      case Remove(path) => try { Nio.deleteIfExists(path): Unit } catch case _: IOException => ()))

  private def node(path: Path): Option[Node] =
    try
      val a = Nio.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      Some(Node(path, a.isDirectory, a.lastModifiedTime.toMillis, a.size))
    catch case _: IOException => None

  private def list(dir: Path): Vector[Node] =
    try
      val stream = Nio.list(dir)
      try stream.toArray(n => new Array[Path](n)).toVector.flatMap(node).sortBy(_.name)
      finally stream.close()
    catch case _: IOException => Vector.empty

  private def write(path: Path, bytes: Array[Byte]): Boolean =
    try
      val dir = path.toAbsolutePath.getParent
      Nio.createDirectories(dir)
      // written whole beside it, then moved over it
      val tmp = Nio.createTempFile(dir, ".symdex", ".tmp")
      try
        Nio.write(tmp, bytes)
        Nio.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE): Unit
        true
      finally Nio.deleteIfExists(tmp): Unit
    catch case _: IOException => false

  /**
   * FILES IN MEMORY: paths to bytes and the time each was last written, directories implied by the paths under them.
   * Each write is a tick of its own clock, so a file written again is newer — what a compile does to SemanticDB.
   */
  final class Memory(initial: Iterable[(Path, Array[Byte])] = Nil):
    private val files = mutable.LinkedHashMap.empty[Path, (Array[Byte], Long)]
    private var ticks = 1_000_000L
    initial.foreach((p, b) => put(p, b))

    private def key(p: Path): Path = p.toAbsolutePath.normalize
    def put(p: Path, bytes: Array[Byte]): Unit = synchronized { ticks += 1; files(key(p)) = (bytes, ticks) }
    /** written again, unchanged: newer */
    def touch(p: Path): Unit = synchronized { files.get(key(p)).foreach((b, _) => put(p, b)) }
    def delete(p: Path): Unit = synchronized { files.remove(key(p)): Unit }
    def bytes(p: Path): Option[Array[Byte]] = synchronized(files.get(key(p)).map(_._1))
    def paths: Vector[Path] = synchronized(files.keys.toVector)

    def stat(p: Path): Option[Node] = synchronized {
      val k = key(p)
      files.get(k) match
        case Some((b, t)) => Some(Node(k, directory = false, t, b.length.toLong))
        case None => Option.when(files.keysIterator.exists(f => f.startsWith(k) && f != k))(Node(k, true, 0L, 0L))
    }
    def entries(dir: Path): Vector[Node] = synchronized {
      val d = key(dir)
      files.keysIterator.filter(f => f.startsWith(d) && f != d).map(f => d.resolve(d.relativize(f).getName(0)))
        .toVector.distinct.flatMap(stat).sortBy(_.name)
    }

  def memory[R[_], Rest[_], A](m: Memory)(p: A ! R)(using Split[Disk, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Disk, Rest, A, A](pure)([X] => (op: Disk[X]) => op match
      case Entries(dir) => m.entries(dir)
      case Stat(path) => m.stat(path)
      case Read(path) => m.bytes(path)
      case Write(path, bytes) => m.put(path, bytes); true
      case Remove(path) => m.delete(path)))

/** a program for each element, in order: the results */
def each[A, B, R[_]](as: Iterable[A])(f: A => B ! R): Vector[B] ! R =
  as.foldLeft(pure[Vector[B], R](Vector.empty))((acc, a) => acc.flatMap(bs => f(a).map(bs :+ _)))
