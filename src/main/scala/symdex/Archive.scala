package symdex

import okay.scala3.*

import java.nio.file.Path
import scala.collection.mutable

/**
 * WHERE A TEXT TOO LONG TO GIVE WHOLE IS KEPT (specs/symdex.md, v0.7): an answer over its budget, for `more` to page;
 * a search's output, for the hook's digest to point at. `Put` answers the name the text is kept under — an id in a
 * server's memory, a file's path on disk — and `Get` gives it back by that id. Content-addressed: the same text, the
 * same id.
 */
enum Archive[X]:
  case Put(text: String) extends Archive[String]
  case Get(id: String) extends Archive[Option[String]]

object Archive:
  def put[R[_]](text: String)(using In[Archive, R]): String ! R = perform[Archive](Put(text))
  def get[R[_]](id: String)(using In[Archive, R]): Option[String] ! R = perform[Archive](Get(id))

  /** a text's id: the first six bytes of its SHA-256, in hex */
  def id(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")).take(6)
      .map(b => f"${b & 0xff}%02x").mkString

  /** a server's answers, for its lifetime: at most `most`, the oldest dropped first */
  final class Store(most: Int = 64):
    private val entries = mutable.LinkedHashMap.empty[String, String]
    def put(text: String): String = synchronized {
      val i = id(text)
      entries.remove(i)
      entries(i) = text
      while entries.size > most do entries.remove(entries.head._1)
      i
    }
    def get(i: String): Option[String] = synchronized(entries.get(i))

  def memory[R[_], Rest[_], A](store: Store)(p: A ! R)(using Split[Archive, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Archive, Rest, A, A](pure)([X] => (op: Archive[X]) => op match
      case Put(text) => store.put(text)
      case Get(i) => store.get(i)))

  /**
   * FILES in `dir`, by `Disk`: `<id>.txt`, written once; the `keep` newest kept, the older removed. `Put` answers the
   * file's path — what the hook's digest names for the agent to read back.
   */
  def files[R[_], Rest[_], A](dir: Path, keep: Int = 200)(p: A ! R)(using Split[Archive, R, Rest], In[Disk, Rest])
  : A ! Rest =
    def fileOf(i: String): Path = dir.resolve(s"$i.txt")
    handle(p, Handler.general[Archive, Rest, A, A](pure)([X] => (op: Archive[X], k: X => A ! Rest) => op match
      case Put(text) =>
        val file = fileOf(id(text))
        Disk.exists[Rest](file).flatMap(there =>
          if there then pure[Boolean, Rest](true) else Disk.write[Rest](file, text.getBytes("UTF-8")))
          .andThen(Disk.entries[Rest](dir))
          .flatMap(all => each(all.filterNot(_.directory).sortBy(_.mtime).dropRight(keep))(n =>
            Disk.remove[Rest](n.path)))
          .flatMap(_ => k(file.toString))
      case Get(i) => Disk.read[Rest](fileOf(i)).flatMap(b => k(b.map(String(_, "UTF-8"))))))
