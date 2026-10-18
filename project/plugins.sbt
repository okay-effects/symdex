// symdex on itself: the plugin as any project gets it
resolvers += Resolver.url("symdex", url("https://okay-effects.github.io/symdex"))(Resolver.ivyStylePatterns)
addSbtPlugin("io.github.sergey-scherbina" % "sbt-symdex" % "0.5.2")
