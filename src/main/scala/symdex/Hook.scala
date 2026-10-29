package symdex

import okay.effects.json.{Descent, Json}
import okay.scala3.*

import java.nio.file.Path

/**
 * A Claude Code PostToolUse hook for Bash and Grep: a large search or
 * test output is replaced by an exact digest of itself, and the whole
 * output is archived in a file the agent can read back.
 *
 * The rules are the "Claude Context Diet" hook's (specs/symdex.md), the
 * summarizer is not: no model. A digest is computed from the output —
 * the files it names with a count each, its first lines, and for a log
 * its head, tail and error lines — so it cannot invent a path, costs
 * nothing, and is the same every time.
 *
 *  - only outputs of `MinTokens`+ (3 500, chars / 3.5), and only when
 *    the digest saves 30% or more
 *  - only searches, listings and test runs: an exact read (`cat`,
 *    `sed -n`, `head`), a diff or a patch is never touched
 *  - never an output that looks like it carries a credential: it would
 *    otherwise be written to the archive
 *  - any failure leaves the output as it was
 *
 * The decision is a program over `Archive` (specs/symdex.md, v0.7); `run` gives it the files under the project's
 * `.symdex/archive`, on the disk.
 */
object Hook:

  val MinTokens = 3500
  private val DigestChars = 6000

  /** commands whose output is a list to skim, not text to read exactly */
  private val Allowed = Vector("rg", "grep", "egrep", "fgrep", "find", "fd", "ls", "tree", "git log",
    "git status", "git grep", "sbt", "scripts/gate.sh", "gradle", "mvn", "pytest", "npm test",
    "cargo test", "go test", "docker logs", "kubectl logs", "journalctl")
  private val Exact = Vector("cat ", "sed ", "head ", "tail ", "less ", "git diff", "git show", "diff ", "patch",
    "jq ", "awk ")

  private val Secret =
    """AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY|ghp_[A-Za-z0-9]{30,}|gh[ousr]_[A-Za-z0-9]{30,}|xox[baprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9_-]{32,}""".r

  private val PathLine = """^([^\s:]+\.[A-Za-z0-9]+):(\d+)[:-]""".r
  private val Ident = """(?:^|\s)(?:-[a-zA-Z]+\s+)*['"]?(?:\\b)?([A-Za-z_][A-Za-z0-9_]{2,})(?:\\b)?['"]?(?:\s|$)""".r

  /** the decision, as the hook's stdout: `None` leaves the output alone */
  def run(input: String, archiveDir: Path): Option[String] =
    Descent.parse(input).toOption.flatMap(json =>
      try okay.scala3.run(Disk.real(Archive.files(archiveDir)(decide[Archive + Disk](json))))
      catch case _: Exception => None)

  private def field(j: Json, k: String): Option[Json] = j.field(k)
  private def text(j: Json): Option[String] = j match
    case Json.Str(s) => Some(s)
    case _ => None

  def decide[R[_]: Has[Archive]](in: Json): Option[String] ! R =
    val tool = field(in, "tool_name").flatMap(text).getOrElse("")
    val response = field(in, "tool_response")
    val output = response.flatMap(r => text(r).orElse(field(r, "text").flatMap(text))
      .orElse(field(r, "stdout").flatMap(text))
      .orElse(field(r, "content").flatMap(text))).getOrElse("")
    val command = field(in, "tool_input").flatMap(i => field(i, "command").flatMap(text)).getOrElse("")
    val pattern = field(in, "tool_input").flatMap(i => field(i, "pattern").flatMap(text))
    val eligible = tool match
      case "Grep" => true
      case "Bash" => allowed(command)
      case _ => false
    if !eligible || Tools.tokens(output) < MinTokens || Secret.findFirstIn(output).isDefined then pure(None)
    else Archive.put[R](output).map { kept =>
      val digest = digestOf(output, kept, hint(tool, command, pattern))
      if digest.length > output.length * 7 / 10 then None
      else Some(Json.print(Json.obj("hookSpecificOutput" -> Json.obj(
        "hookEventName" -> Json.Str("PostToolUse"),
        "updatedToolOutput" -> Json.Str(digest)))))
    }

  /** a search, listing or test command, and not an exact read: every part of a pipeline is looked at */
  def allowed(command: String): Boolean =
    val c = command.trim
    val parts = c.split("""\|\||&&|;|\|""").map(_.trim).filter(_.nonEmpty)
    parts.nonEmpty && parts.exists(p => Allowed.exists(a => p == a || p.startsWith(a + " "))) &&
      !parts.exists(p => Exact.exists(e => p.startsWith(e) || p == e.trim))

  /** a grep for an identifier is a references question: say which symdex call answers it exactly */
  private def hint(tool: String, command: String, pattern: Option[String]): Option[String] =
    val word = tool match
      case "Grep" => pattern.filter(_.matches("""(\\b)?[A-Za-z_][A-Za-z0-9_]{2,}(\\b)?""")).map(_.replace("\\b", ""))
      case _ if command.matches("""^\s*(rg|grep|git grep)\b.*""") =>
        Ident.findAllMatchIn(command.replaceFirst("""^\s*(rg|grep|git grep)""", "")).map(_.group(1))
          .find(w => !Set("include", "exclude", "type").contains(w))
      case _ => None
    word.map(w => s"symdex answers this exactly, comments and other symbols named `$w` left out: " +
      s"references query=$w (callers=true to group by caller), definition query=$w")

  /** the digest of an output; `archived` names where the whole of it is kept */
  def digestOf(output: String, archived: String, hint: Option[String]): String =
    val lines = output.linesIterator.toVector
    val files = lines.flatMap(l => PathLine.findFirstMatchIn(l).map(_.group(1)))
      .groupMapReduce(identity)(_ => 1)(_ + _).toVector.sortBy((f, n) => (-n, f))
    val head = s"[symdex: ${lines.size} lines, ~${Tools.tokens(output)} tokens, digested; the whole output is $archived " +
      "(read a range of it with sed -n 'A,Bp')]"
    def fit(xs: Vector[String], room: Int): Vector[String] =
      xs.foldLeft(Vector.empty[String])((acc, l) => if acc.map(_.length + 1).sum + l.length < room then acc :+ l else acc)
    val body =
      if files.size >= 2 && files.map(_._2).sum * 2 >= lines.size then
        // a search: which files, how many hits each, and the first hits
        val rows = files.take(25).map((f, n) => s"  $f  ($n)") ++
          (if files.size > 25 then Vector(s"  … ${files.size - 25} more files") else Vector.empty)
        (s"${files.map(_._2).sum} hits in ${files.size} files:" +: rows) ++
          ("first hits:" +: fit(lines, DigestChars / 2))
      else
        // a log or a listing: its head, its tail, and every line that looks like trouble
        val trouble = lines.zipWithIndex.filter((l, _) => l.matches("""(?i).*\b(error|fail(ed|ure)?|exception|panic|warn(ing)?|==> X)\b.*"""))
          .map((l, i) => f"${i + 1}%6d: $l")
        Vector("head:") ++ fit(lines.take(40), DigestChars / 4) ++
          (if trouble.isEmpty then Vector.empty else s"lines with error/fail/exception/warn (${trouble.size}):" +: fit(trouble, DigestChars / 3)) ++
          Vector("tail:") ++ fit(lines.takeRight(40), DigestChars / 4)
    ((head +: hint.toVector) ++ body).mkString("\n")
