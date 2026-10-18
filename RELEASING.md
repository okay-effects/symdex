# Releasing

Two artifacts, released separately: **symdex** (a zip on GitHub Releases)
and **sbt-symdex** (an Ivy repository on GitHub Pages). Neither needs
Maven Central or any credential beyond `gh` being logged in.

## Before anything: run it

Every release is RUN before it is published, on a fresh project and on
symdex itself. sbt-symdex 0.5.1 was published without being run and failed
at the first task; the checks below are what would have caught it.

```sh
sbt symdex/clean test                 # the suite, from clean (a warm build hides warnings)
cd sbt-symdex && sbt publishLocal     # then, in a throwaway project and in symdex itself:
sbt symdexIndex symdexMcp symdexHook "symdex references query=<something> callers=true"
```

## symdex

1. Bump `version` in `build.sbt` and `Symdex.version` in
   `src/main/scala/symdex/Symdex.scala` (they must agree).
2. `sbt test dist` — `target/symdex-<version>.zip`.
3. Check the zip alone: unzip it somewhere outside the checkout and run
   `symdex/bin/symdex status --root <a compiled project>`.
4. Commit, then tag and release:

   ```sh
   git tag -a v<version> -m "symdex <version>" && git push origin master v<version>
   gh release create v<version> target/symdex-<version>.zip --title "symdex <version>" --notes "…"
   ```

5. Add the release to CHANGELOG.md.

## sbt-symdex

1. Bump `version` in `sbt-symdex/build.sbt`. If it should download a new
   symdex release, set `SymdexRelease` in `SymdexPlugin.scala` — only to a
   release that exists (the plugin downloads it by that name).
2. Run it (above), against `publishLocal`.
3. `cd sbt-symdex && sbt publish` — writes into `../docs`, which GitHub
   Pages serves.
4. Update the version in README.md, GUIDE.md, `docs/index.html` and
   `project/plugins.sbt` (symdex builds with its own plugin).
5. Commit and push. Pages rebuilds from `docs/` on master — when it does
   not (it did not after the source was switched), ask for it:

   ```sh
   gh api -X POST repos/okay-effects/symdex/pages/builds
   ```

6. Check from the network alone: move
   `~/.ivy2/local/io.github.sergey-scherbina/sbt-symdex` aside, delete
   `~/Library/Caches/Coursier/v1/https/okay-effects.github.io`, and
   load a project that names the version; restore the local copy after.
7. A broken version is removed from `docs/` and the CHANGELOG says so; it is
   never overwritten in place (resolvers cache by version).

## The okay submodule

symdex builds against okay as a submodule. Moving it (`cd okay && git
checkout <sha>`, then commit the submodule) is part of a release when
symdex needs something new from okay; okay changes land in okay first,
under okay's own rules.
