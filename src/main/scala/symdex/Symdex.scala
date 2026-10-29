package symdex

import okay.effects.Void
import okay.effects.cek.OnCek
import okay.effects.json.Json
import okay.mcp.{Link, Servers, ToolCall, ToolResult, ToolSpec}
import okay.scala3.*
import okay.scala3.std.Clock

import java.nio.file.Path

/**
 * SYMDEX RUNNING: the tools' effects given their handlers, in one line (`Live.run`) — the compilers' output on this
 * machine's disk, TASTy by tasty-query, the system clock, a server's archive in memory — and the MCP server, okay-mcp's,
 * on okay-scala's machine through okay-simple's adapter: a call is its tool's program run in that line.
 */
object Symdex:
  val version = "0.5.0"

  private val usage =
    """symdex — structural code intelligence over SemanticDB, TASTy and SCIP
      |
      |  symdex serve [--root DIR] [--tools a,b] [--lean]
      |                                          an MCP server on stdin/stdout; --tools serves only
      |                                          those, --lean sends terse schemas (fewer tokens per turn)
      |  symdex <tool> [--root DIR] key=value…   one tool call, answer on stdout
      |  symdex files [--root DIR]               the source files indexed
      |  symdex hook                             a Claude Code PostToolUse hook (bin/symdex-hook)
      |
      |tools: definition, references, implementations, givens, members, modules,
      |       outline, source, more, status            (docs: GUIDE.md)
      |e.g.   symdex references query=Bulk.joinSorted callers=true""".stripMargin

  /** the effects every tool may perform, and what `Code`'s handler performs below them */
  type Asked[X] = (Clock + Tasty + Disk + Archive + Code)[X]

  /**
   * One project's symdex: what it keeps between calls (`Kept`, the archive), and its programs run with the
   * machine's handlers.
   */
  final class Live(val root: Path, include: DbFile => Boolean = _ => true, recheckMillis: Long = 2000):
    val kept: Kept = Kept()
    val archive: Archive.Store = Archive.Store()
    def run[A](p: A ! Asked): A =
      okay.scala3.run(Clock.system(Tasty.query(Disk.real(Archive.memory(archive)(
        Code.compiled(root, kept, include, recheckMillis)(p))))))
    /** a tool called: its answer, or why not */
    def call(name: String, args: Json): Either[String, String] =
      try run(Tools.call[Asked](name, args))
      catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))
    /** every module's TASTy read now (a server does it once, in the background) */
    def warm(): Unit =
      try run(Code.warm[Asked])
      catch case e: Exception => System.err.println(s"symdex: TASTy warm-up: ${e.getMessage}")

  /** THE SERVER: okay-mcp's, on okay-scala's machine (`OnCek`); its link `Link`, its tools symdex's */
  object Serve:
    val servers: Servers[OnCek.type] = new Servers[OnCek.type]()(using OnCek)
    type Linked[X] = okay.effects.Sum[Void, Link, X]

    def server(live: Live, specs: Vector[ToolSpec]): servers.Server[Linked] =
      val served = specs.map(_.name).toSet
      servers.Server[Linked]("symdex", version, new servers.Tools[Linked]:
        def list = OnCek.pure(specs.toList)
        def call(c: ToolCall) = OnCek.delay(OnCek.pure(
          if !served(c.name) then ToolResult.error(s"no such tool '${c.name}'")
          else live.call(c.name, c.arguments).fold(ToolResult.error, ToolResult.text))))

    /** lines in, lines out: the server over a script (a test's) */
    def scripted(live: Live, specs: Vector[ToolSpec], lines: List[String]): List[String] =
      OnCek.run(servers.links.scripted(lines)(servers.serve(server(live, specs))))._1

    /** the process's standard streams */
    def stdio(live: Live, specs: Vector[ToolSpec]): Unit =
      OnCek.run(servers.links.stdio(servers.serve(server(live, specs))))

  def main(args: Array[String]): Unit =
    val (root, rest) = rootOf(args.toList)
    rest match
      case "serve" :: opts =>
        val live = Live(root)
        val only = valueOf(opts, "--tools").orElse(sys.env.get("SYMDEX_TOOLS"))
          .map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet)
        val lean = opts.contains("--lean") || sys.env.get("SYMDEX_LEAN").contains("1")
        val specs = Tools.serving(only, lean)
        // TASTy for every module, in the background: the first `callers`,
        // `source` or `members` then finds it read
        Thread.ofVirtual().start(() => live.warm()): Unit
        System.err.println(s"symdex $version: serving ${root.toAbsolutePath.normalize}, " +
          s"${specs.size} tools, ~${Tools.schemaTokens(specs)} schema tokens")
        Serve.stdio(live, specs)
      case "hook" :: Nil =>
        // a PostToolUse hook: stdin in, a decision (or nothing) out; never fails the tool call
        val input = String(System.in.readAllBytes(), "UTF-8")
        val project = sys.env.get("CLAUDE_PROJECT_DIR").map(Path.of(_)).getOrElse(Path.of("."))
        Hook.run(input, project.resolve(".symdex").resolve("archive")).foreach(println)
      case "files" :: Nil =>
        Live(root).run(Code.current[Asked]).index.entries.foreach(e => println(e.source))
      case tool :: kvs if kvs.exists(_.startsWith("--")) && tool != "serve" =>
        System.err.println(s"unknown option ${kvs.filter(_.startsWith("--")).mkString(" ")}: tool arguments are key=value\n\n$usage")
        sys.exit(2)
      case tool :: kvs if tool != "help" && tool != "--help" =>
        if !Tools.specs.exists(_.name == tool) then
          System.err.println(s"no tool '$tool'\n\n$usage")
          sys.exit(2)
        else Live(root).call(tool, argsOf(kvs)) match
          case Right(answer) => println(answer)
          case Left(why) =>
            System.err.println(why)
            sys.exit(1)
      case _ => println(usage)

  private def valueOf(opts: List[String], flag: String): Option[String] =
    opts.indexOf(flag) match
      case -1 => None
      case i => opts.lift(i + 1)

  /**
   * `--root DIR` or `--root=DIR`, anywhere. Found 2026-10-02 by an agent
   * benchmark: `--root=DIR` was silently not a root, so the index was the
   * working directory's — empty — and every answer was "nothing named".
   */
  def rootOf(args: List[String]): (Path, List[String]) =
    val eq = args.indexWhere(_.startsWith("--root="))
    val sp = args.indexOf("--root")
    if eq >= 0 then (Path.of(args(eq).stripPrefix("--root=")), args.patch(eq, Nil, 1))
    else if sp >= 0 && sp + 1 < args.size then (Path.of(args(sp + 1)), args.patch(sp, Nil, 2))
    else (Path.of(sys.env.getOrElse("SYMDEX_ROOT", ".")), args)

  private def argsOf(kvs: List[String]): Json =
    Json.Obj(kvs.toVector.map { kv =>
      kv.split("=", 2) match
        case Array(k, v) =>
          k -> (v match
            case "true" => Json.Bool(true)
            case "false" => Json.Bool(false)
            case n if n.toIntOption.isDefined => Json.num(n.toInt)
            case s => Json.Str(s))
        case Array(k) => k -> Json.Bool(true)
        case _ => kv -> Json.Bool(true)
    })
