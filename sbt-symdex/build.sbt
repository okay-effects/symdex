// sbt-symdex: one line in a project's plugins.sbt makes it symdex-ready.
// An sbt plugin builds on sbt's own Scala (2.12), so it is its own build.
sbtPlugin := true
name := "sbt-symdex"
organization := "io.github.sergey-scherbina"
version := "0.5.2"
licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings")

// Published as an Ivy repository on GitHub Pages, which serves this
// repository's docs/ directory on master: `sbt publish` here writes into
// ../docs, and the commit that adds the files is the release. A build
// needs only a resolver line, no Sonatype:
//   resolvers += Resolver.url("symdex", url("https://okay-effects.github.io/symdex"))(Resolver.ivyStylePatterns)
publishMavenStyle := false
publishTo := Some(Resolver.file("symdex-pages", baseDirectory.value / ".." / "docs")(Resolver.ivyStylePatterns))
