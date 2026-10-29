/*
 * symdex — structural code intelligence for agents, on effects (specs/symdex.md, v0.7):
 * okay-scala for symdex's own effects, okay-simple's JSON value, okay-mcp for the
 * server — the released libraries, from their maven repositories.
 */
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.github.sergey-scherbina"
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scalacOptions ++= Seq("-Wunused:all", "-Werror")
ThisBuild / resolvers ++= Seq(
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven")

lazy val symdex = (project in file("."))
  .settings(
    name := "symdex",
    version := "0.6.0",
    // symdex's own sources are indexed too: the test fixtures under
    // src/test/scala/fixture are read back from the SemanticDB the
    // compiler writes beside the classes
    semanticdbEnabled := true,
    libraryDependencies ++= Seq(
      "io.github.okay-effects" %% "okay-scala" % "0.1.1",
      "io.github.okay-effects" %% "okay-effects-json-value" % "0.1.1",
      // the MCP server, on okay-scala's machine through okay-simple's adapter
      "io.github.okay-effects" %% "okay-mcp" % "0.1.0",
      "io.github.okay-effects" %% "okay-effects-cek" % "0.1.1",
      // TASTy, for what SemanticDB lacks: definition spans, extension flags
      "ch.epfl.scala" %% "tasty-query" % "1.9.0",
      "org.scalameta" %% "munit" % "1.1.1" % Test,
    ),
  )

/*
 * A runnable symdex without sbt: `sbt stage` lays out target/symdex with
 * every runtime jar in lib and a bin/symdex script that runs them with java.
 * bin/symdex (the checked-in launcher) re-stages only when a source or
 * the build changed; `sbt dist` zips the same tree for a release.
 */
lazy val stage = taskKey[File]("a runnable symdex in target/symdex: lib/*.jar and bin/symdex")
lazy val dist = taskKey[File]("target/symdex-<version>.zip: the staged tree, for a release")

symdex / stage := {
  val out = (symdex / target).value / "symdex"
  IO.delete(out)
  val jars = (symdex / Runtime / fullClasspathAsJars).value.map(_.data).filter(_.isFile)
  jars.foreach(j => IO.copyFile(j, out / "lib" / j.getName))
  val script = out / "bin" / "symdex"
  IO.write(script,
    """#!/bin/sh
      |# symdex, staged: no sbt needed. See https://github.com/okay-effects/symdex
      |here=$(cd "$(dirname "$0")/.." && pwd)
      |exec java -Xss8m ${SYMDEX_JAVA_OPTS:-} -cp "$here/lib/*" symdex.Symdex "$@"
      |""".stripMargin)
  script.setExecutable(true)
  val hook = out / "bin" / "symdex-hook"
  IO.write(hook,
    """#!/bin/sh
      |# Claude Code PostToolUse hook (matcher "Bash|Grep"); small outputs never start a JVM
      |input=$(cat)
      |[ ${#input} -lt 12000 ] && exit 0
      |here=$(cd "$(dirname "$0")/.." && pwd)
      |printf '%s' "$input" | exec "$here/bin/symdex" hook
      |""".stripMargin)
  hook.setExecutable(true)
  out
}

symdex / dist := {
  val tree = (symdex / stage).value
  val zip = (symdex / target).value / s"symdex-${(symdex / version).value}.zip"
  IO.delete(zip)
  val entries = (tree ** AllPassFilter).get.filter(_.isFile).map(f => f -> s"symdex/${IO.relativize(tree, f).get}")
  IO.zip(entries, zip, None)
  zip
}
