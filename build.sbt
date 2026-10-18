/*
 * symdex — structural code intelligence for agents, built on okay.
 *
 * okay rides as a git SUBMODULE (`okay/`) and its modules are referenced
 * as ProjectRefs, the arrangement okay-chat uses: one checkout, no
 * published artifact, and a change symdex needs in okay is made in okay
 * (its own repository, its own gate) and the submodule moved.
 */
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.github.sergey-scherbina"
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scalacOptions ++= Seq("-Wunused:all", "-Werror")

lazy val okayMcp = ProjectRef(file("okay"), "okayMcpJVM")
lazy val okayCodec = ProjectRef(file("okay"), "okayCodecJVM")

lazy val symdex = (project in file("."))
  .dependsOn(okayMcp, okayCodec)
  .settings(
    name := "symdex",
    version := "0.5.0",
    // symdex's own sources are indexed too: the test fixtures under
    // src/test/scala/fixture are read back from the SemanticDB the
    // compiler writes beside the classes
    semanticdbEnabled := true,
    libraryDependencies ++= Seq(
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
