//> using scala 3.7.4
//> using toolkit default

// THE BENCH PROJECT: an sbt project with sbt-symdex in `/tmp/eqbench` (or the directory given), `before/` committed,
// `after/` in the working tree — every case's change uncommitted at once — compiled with SemanticDB and indexed
// (`sbt symdexIndex` also writes the classpath `equivalent` reads). `SYMDEX_HOME`: the symdex the plugin uses.

val here = os.Path(sys.props.getOrElse("verify.dir", os.pwd.toString))

@main def setup(args: String*): Unit =
  val dir = os.Path(args.headOption.getOrElse("/tmp/eqbench"), os.pwd)
  if os.exists(dir) then
    require(os.exists(dir / ".eqbench"), s"$dir exists and is not a bench project: not touched")
    os.remove.all(dir)
  val src = dir / "src" / "main" / "scala" / "app"
  os.makeDir.all(src)
  os.write(dir / ".eqbench", "")
  os.write(dir / "build.sbt", "scalaVersion := \"3.9.0\"\n")
  os.write(dir / "project" / "plugins.sbt",
    "addSbtPlugin(\"io.github.sergey-scherbina\" % \"sbt-symdex\" % \"0.8.0\")\n", createFolders = true)
  os.write(dir / "project" / "build.properties", "sbt.version = 1.13.0\n")
  os.write(dir / ".gitignore", "target/\n.symdex/\n.bsp/\n.eqbench\n")
  os.write(dir / ".git-exclude", "")
  def git(a: String*) = os.proc("git" +: a).call(cwd = dir, stdout = os.Pipe)
  for f <- os.list(here / "before") do os.copy(f, src / f.last)
  git("init", "-q")
  os.remove(dir / ".git-exclude")
  os.write.append(dir / ".git" / "info" / "exclude", ".mcp.json\n")
  git("add", "-A")
  git("-c", "user.name=bench", "-c", "user.email=bench@localhost", "commit", "-qm", "The shop's helpers")
  for f <- os.list(here / "after") do os.copy.over(f, src / f.last)
  val changed = git("status", "--porcelain").out.lines().size
  println(s"$dir: ${os.list(src).size} files, $changed changed in the working tree; indexing")
  os.proc("sbt", "-batch", "symdexIndex").call(cwd = dir, stdout = os.Inherit, stderr = os.Inherit)
