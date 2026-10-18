package symdex.sbt


import scala.sys.process.Process

import sbt._
import sbt.Keys._
import sjsonnew.shaded.scalajson.ast.unsafe._
import sjsonnew.support.scalajson.unsafe.{Parser, PrettyPrinter}

/**
 * Makes a build symdex-ready, end to end:
 *
 *   // project/plugins.sbt
 *   addSbtPlugin("io.github.sergey-scherbina" % "sbt-symdex" % "0.5.2")
 *   // then, once:
 *   sbt symdexIndex symdexMcp symdexHook
 *   // and any time, from the sbt shell:
 *   symdex references query=Foo.bar callers=true
 *
 * SemanticDB is on for every project. symdex itself comes from
 * `symdexHome` (the SYMDEX_HOME variable) if set, else from the GitHub release of
 * `symdexVersion`, downloaded once into ~/.symdex/<version>. The server
 * is NOT run inside sbt: an MCP client starts it as its own process (it
 * speaks on stdin/stdout, and it must outlive any sbt session); the
 * plugin only says how. Nor is there a compile hook: symdex notices a
 * compile from the SemanticDB it rewrites.
 */
object SymdexPlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {
    val symdexVersion = settingKey[String]("the symdex release to use when symdexHome is not set")
    val symdexHome = settingKey[Option[File]](
      "a symdex checkout or unpacked release holding bin/symdex (default: $SYMDEX_HOME, else the downloaded release)")
    val symdexTools = settingKey[Seq[String]](
      "the tools to serve; empty serves all (each tool schema costs context every turn)")
    val symdexLean = settingKey[Boolean]("serve terse tool schemas")
    val symdexHookFile = settingKey[File](
      "where symdexHook registers the hook (default .claude/settings.local.json: the path is this machine's)")
    val symdexLauncher = taskKey[File]("bin/symdex: from symdexHome, else the release, downloaded once")
    val symdexIndex = taskKey[Unit](
      "compile every project with its tests (SemanticDB on), then report what symdex covers and what is stale")
    val symdexMcp = taskKey[File](
      "write symdex into .mcp.json (created if absent; an existing file gains the entry, never loses one)")
    val symdexHook = taskKey[File](
      "register symdex's PostToolUse hook (large grep/test outputs digested) for Claude Code")
  }
  import autoImport._

  /** the symdex release this plugin downloads (the plugin's own version may run ahead of it) */
  val SymdexRelease = "0.5.0"

  override def globalSettings: Seq[Setting[_]] = Seq(commands += symdexCommand)

  override def buildSettings: Seq[Setting[_]] = Seq(
    semanticdbEnabled := true,
    symdexVersion := SymdexRelease,
    symdexHome := sys.env.get("SYMDEX_HOME").map(file),
    symdexTools := Nil,
    symdexLean := false,
    symdexHookFile := (ThisBuild / baseDirectory).value / ".claude" / "settings.local.json",
    symdexLauncher := launcher(symdexHome.value, symdexVersion.value, streams.value.log),
    // only THIS build's projects: `inAnyProject` also takes every project
    // of a build referenced by ProjectRef (symdex's own okay submodule:
    // 730 compiles across JVM, JS and Native before the first of symdex's;
    // found by running the plugin on symdex itself). What they depend on is
    // compiled anyway, as a dependency.
    symdexIndex := Def.taskDyn {
      // everything the inner task needs is read here: a dynamic task's inner
      // task has no `streams` of its own in this scope (0.5.1 failed so)
      val log = streams.value.log
      val launcherFile = symdexLauncher.value
      val base = (ThisBuild / baseDirectory).value
      val root = loadedBuild.value.root
      val mine = buildStructure.value.allProjectRefs.filter(_.build == root)
      Def.task {
        val _ = compile.all(ScopeFilter(inProjects(mine: _*), inConfigurations(Compile, Test))).value
        log.info(run(launcherFile, Seq("status", "--root", base.getAbsolutePath)))
      }
    }.value,
    symdexMcp := {
      val log = streams.value.log
      val args = Seq("serve", "--root", ".") ++
        (if (symdexTools.value.isEmpty) Nil else Seq("--tools", symdexTools.value.mkString(","))) ++
        (if (symdexLean.value) Seq("--lean") else Nil)
      val server = obj("command" -> JString(symdexLauncher.value.getAbsolutePath),
        "args" -> JArray(args.map(a => JString(a): JValue).toArray))
      val file = (ThisBuild / baseDirectory).value / ".mcp.json"
      merge(file, log, "mcpServers.symdex") { root =>
        val servers = field(root, "mcpServers").collect { case o: JObject => o }.getOrElse(obj())
        if (field(servers, "symdex").isDefined) None
        else Some(put(root, "mcpServers", put(servers, "symdex", server)))
      }
      file
    },
    symdexHook := {
      val log = streams.value.log
      val hook = symdexLauncher.value.getParentFile / "symdex-hook"
      if (!hook.exists) sys.error(s"no hook script beside the launcher: $hook (symdex 0.5.0 or newer)")
      val entry = obj("matcher" -> JString("Bash|Grep"),
        "hooks" -> JArray(Array[JValue](obj("type" -> JString("command"), "command" -> JString(hook.getAbsolutePath)))))
      val file = symdexHookFile.value
      merge(file, log, "hooks.PostToolUse[symdex-hook]") { root =>
        val hooks = field(root, "hooks").collect { case o: JObject => o }.getOrElse(obj())
        val post = field(hooks, "PostToolUse").collect { case JArray(xs) => xs.toVector }.getOrElse(Vector.empty)
        if (post.exists(e => PrettyPrinter(e).contains("symdex-hook"))) None
        else Some(put(root, "hooks", put(hooks, "PostToolUse", JArray((post :+ (entry: JValue)).toArray))))
      }
      file
    },
  )

  /** `symdex <tool> key=value…` in the sbt shell: one call, answered over this build */
  private def symdexCommand: Command = Command.args("symdex", "<tool> key=value…") { (state, args) =>
    val extracted = Project.extract(state)
    val (next, launcherFile) = extracted.runTask(ThisBuild / symdexLauncher, state)
    val root = extracted.get(ThisBuild / baseDirectory)
    println(run(launcherFile, args ++ Seq("--root", root.getAbsolutePath)))
    next
  }

  private def run(launcher: File, args: Seq[String]): String =
    Process(launcher.getAbsolutePath +: args).!!.stripLineEnd

  /** bin/symdex from the home given, else from the release, downloaded and unpacked once */
  def launcher(home: Option[File], version: String, log: Logger): File =
    home match {
      case Some(h) =>
        val l = h / "bin" / "symdex"
        if (!l.exists) sys.error(s"symdexHome $h has no bin/symdex")
        l
      case None =>
        val dir = file(sys.props("user.home")) / ".symdex" / version
        val l = dir / "symdex" / "bin" / "symdex"
        if (!l.exists) {
          val url = s"https://github.com/okay-effects/symdex/releases/download/v$version/symdex-$version.zip"
          log.info(s"symdex: downloading $url")
          IO.withTemporaryFile("symdex", ".zip") { zip =>
            val in = java.net.URI.create(url).toURL.openStream()
            try IO.transfer(in, zip) finally in.close()
            IO.unzip(zip, dir)
          }
          (dir / "symdex" / "bin").listFiles().foreach(_.setExecutable(true))
        }
        l
    }

  // ---- JSON: merged into, never overwritten -----------------------------

  private def obj(fields: (String, JValue)*): JObject = JObject(fields.map { case (k, v) => JField(k, v) }.toArray)

  private def field(o: JValue, k: String): Option[JValue] = o match {
    case JObject(fs) => fs.find(_.field == k).map(_.value)
    case _ => None
  }

  private def put(o: JObject, k: String, v: JValue): JObject =
    if (o.value.exists(_.field == k)) JObject(o.value.map(f => if (f.field == k) JField(k, v) else f))
    else JObject(o.value :+ JField(k, v))

  /**
   * Read `file` (an empty object if absent), let `change` add what is
   * missing (`None`: already there), write it back. A file that does not
   * parse is left alone and the entry is printed instead.
   */
  private def merge(file: File, log: Logger, what: String)(change: JObject => Option[JObject]): Unit = {
    val root: Option[JObject] =
      if (!file.exists) Some(obj())
      else scala.util.Try(Parser.parseUnsafe(IO.read(file))).toOption.collect { case o: JObject => o }
    root match {
      case None => log.warn(s"symdex: $file does not parse as a JSON object; left alone. Add $what by hand.")
      case Some(r) => change(r) match {
        case None => log.info(s"symdex: $file already has $what")
        case Some(updated) =>
          IO.createDirectory(file.getParentFile)
          IO.write(file, PrettyPrinter(updated) + "\n")
          log.info(s"symdex: added $what to $file")
      }
    }
  }
}
