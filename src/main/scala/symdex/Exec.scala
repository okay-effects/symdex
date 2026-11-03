package symdex

import okay.scala3.*

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** a process's end: its exit code, what it wrote to standard output and to standard error */
final case class Ran(code: Int, out: String, err: String)

/**
 * PROCESSES, as `Code`'s handler runs them (specs/symdex.md, v0.9): a command in a directory, waited for at most
 * `timeoutMs`. One that cannot start, or is killed at its timeout, is a `Left` with why — never an exception. Which
 * processes there are is the handler's: the machine's (`real`), or answers given (a test's).
 */
enum Exec[X]:
  case Run(command: List[String], dir: Path, timeoutMs: Long) extends Exec[Either[String, Ran]]

object Exec:
  def run[R[_]](command: List[String], dir: Path, timeoutMs: Long)(using In[Exec, R]): Either[String, Ran] ! R =
    perform[Exec](Run(command, dir, timeoutMs))

  /** THE MACHINE'S PROCESSES: java.lang.ProcessBuilder, its output read whole, killed at its timeout */
  def real[R[_], Rest[_], A](p: A ! R)(using Split[Exec, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Exec, Rest, A, A](pure)([X] => (op: Exec[X]) => op match
      case Run(command, dir, timeoutMs) => process(command, dir, timeoutMs)))

  /** answers given: each command to its answer (a test's), none answered for one not given */
  def answered[R[_], Rest[_], A](answer: List[String] => Either[String, Ran])(p: A ! R)(
    using Split[Exec, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Exec, Rest, A, A](pure)([X] => (op: Exec[X]) => op match
      case Run(command, _, _) => answer(command)))

  def process(command: List[String], dir: Path, timeoutMs: Long): Either[String, Ran] =
    try
      val pb = ProcessBuilder(command*).directory(dir.toFile)
      val out = java.io.File.createTempFile("symdex-out", ".txt")
      val err = java.io.File.createTempFile("symdex-err", ".txt")
      pb.redirectOutput(out).redirectError(err)
      val p = pb.start()
      p.getOutputStream.close()
      try
        if !p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) then
          p.descendants().forEach(_.destroyForcibly(): Unit)
          p.destroyForcibly()
          Left(s"${command.headOption.getOrElse("")}: no answer in ${timeoutMs / 1000}s")
        else Right(Ran(p.exitValue(), java.nio.file.Files.readString(out.toPath),
          java.nio.file.Files.readString(err.toPath)))
      finally
        out.delete(): Unit
        err.delete(): Unit
    catch case e: java.io.IOException => Left(s"${command.headOption.getOrElse("")}: ${e.getMessage}")
