package symdex

import okay.scala3.*

import java.net.URI
import java.nio.file.Path

/** a compiler's complaint: its line (0-based, in the text given) and its message */
final case class Problem(line: Int, message: String)

/**
 * THE COMPILER'S ANSWERS, as the `type` tool asks them (specs/symdex.md, v0.9): over a classpath, a file's text (as
 * it is, or with a probe appended) — the signature at an offset (hover), the errors. Which compiler is the handler's:
 * Scala 3's presentation compiler (`compiler`, one kept warm per classpath), or answers given (a test's).
 */
enum Typer[X]:
  case Hover(classpath: Vector[Path], file: Path, text: String, offset: Int) extends Typer[Either[String, String]]
  case Diagnose(classpath: Vector[Path], file: Path, text: String) extends Typer[Either[String, Vector[Problem]]]

object Typer:
  def hover[R[_]](cp: Vector[Path], file: Path, text: String, offset: Int)(using In[Typer, R])
      : Either[String, String] ! R = perform[Typer](Hover(cp, file, text, offset))
  def diagnose[R[_]](cp: Vector[Path], file: Path, text: String)(using In[Typer, R])
      : Either[String, Vector[Problem]] ! R = perform[Typer](Diagnose(cp, file, text))

  /** SCALA 3'S PRESENTATION COMPILER: one per classpath, kept (the first answer pays its start) */
  def compiler[R[_], Rest[_], A](p: A ! R)(using Split[Typer, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Typer, Rest, A, A](pure)([X] => (op: Typer[X]) => op match
      case Hover(cp, file, text, offset) => Pc.hover(cp, file, text, offset)
      case Diagnose(cp, file, text) => Pc.diagnose(cp, file, text)))

  /** answers given (a test's) */
  def answered[R[_], Rest[_], A](hover: (Path, String, Int) => Either[String, String],
      diagnose: (Path, String) => Either[String, Vector[Problem]])(p: A ! R)(using Split[Typer, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Typer, Rest, A, A](pure)([X] => (op: Typer[X]) => op match
      case Hover(_, file, text, offset) => hover(file, text, offset)
      case Diagnose(_, file, text) => diagnose(file, text)))

/** the presentation compiler itself: instances by classpath, its calls waited for at most a while */
private[symdex] object Pc:
  import scala.jdk.CollectionConverters.*
  import scala.jdk.OptionConverters.*
  import scala.meta.pc.*

  private val instances = scala.collection.mutable.Map.empty[Vector[Path], PresentationCompiler]
  private val waitSeconds = 60L

  private object NoCancel extends CancelToken:
    def checkCanceled(): Unit = ()
    def onCancel(): java.util.concurrent.CompletionStage[java.lang.Boolean] =
      java.util.concurrent.CompletableFuture.completedFuture(java.lang.Boolean.FALSE)

  private def of(cp: Vector[Path]): PresentationCompiler = synchronized {
    instances.getOrElseUpdate(cp, dotty.tools.pc.ScalaPresentationCompiler()
      .withConfiguration(new scala.meta.internal.pc.PresentationCompilerConfigImpl() {
        override def emitDiagnostics(): Boolean = true
      })
      .newInstance("symdex", cp.asJava, List.empty[String].asJava))
  }

  private def attempt[A](f: => A): Either[String, A] =
    try Right(f)
    catch
      case _: java.util.concurrent.TimeoutException => Left(s"the compiler gave no answer in ${waitSeconds}s")
      case e: Exception => Left(s"the compiler failed: ${Option(e.getMessage).getOrElse(e.getClass.getName)}")

  def hover(cp: Vector[Path], file: Path, source: String, at: Int): Either[String, String] = attempt {
    val params = new OffsetParams:
      def uri(): URI = file.toAbsolutePath.normalize.toUri
      def text(): String = source
      def offset(): Int = at
      def token(): CancelToken = NoCancel
    of(cp).hover(params).get(waitSeconds, java.util.concurrent.TimeUnit.SECONDS).toScala
      .flatMap(_.signature().toScala).getOrElse("")
  }

  def diagnose(cp: Vector[Path], file: Path, source: String): Either[String, Vector[Problem]] = attempt {
    val params = new VirtualFileParams:
      def uri(): URI = file.toAbsolutePath.normalize.toUri
      def text(): String = source
      def token(): CancelToken = NoCancel
      override def shouldReturnDiagnostics(): Boolean = true
    of(cp).didChange(params).get(waitSeconds, java.util.concurrent.TimeUnit.SECONDS).asScala.toVector
      .filter(_.getSeverity == org.eclipse.lsp4j.DiagnosticSeverity.Error)
      .map { d =>
        val m = d.getMessage
        Problem(d.getRange.getStart.getLine, if m.isLeft then m.getLeft else m.getRight.getValue)
      }
  }
