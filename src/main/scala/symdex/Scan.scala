package symdex

import okay.scala3.*

import java.nio.file.Path

/** One `.semanticdb` file on disk, and which module and configuration wrote it. */
final case class DbFile(path: Path, root: Path, module: String, test: Boolean,
                        mtime: Long, size: Long, scip: Boolean = false)

/**
 * Where a build left its SemanticDB. sbt 1.13 writes it under
 * `target/scala-<version>/meta` (`test-meta` for tests); older setups and
 * other tools put it beside the classes, in `classes/META-INF/semanticdb`.
 * Both are found, and so is any SCIP index (`*.scip`). A class directory is never walked — only its
 * `META-INF/semanticdb` — so a large build's class files cost nothing. The walk is a program over `Disk`.
 */
object Scan:

  private val skip = Set(".git", "node_modules", ".bsp", ".idea", ".metals", ".bloop",
    ".symdex", "streams", "zinc", "task-temp-directory", "global-logging",
    "resolution-cache", "src_managed", "resource_managed", "native", "jmh-classes")

  private val classDirs = Set("classes", "test-classes")

  /** every SemanticDB and SCIP file under `root`, by `Disk`: the walk an explicit stack of directories */
  def files[R[_]](root: Path)(using In[Disk, R]): Vector[DbFile] ! R =
    val base = root.toAbsolutePath.normalize
    loop[(List[Node], Vector[DbFile]), Vector[DbFile], R]((List(Node(base, true, 0L, 0L)), Vector.empty)) {
      case (Nil, out) => pure(Right(out))
      case (dir :: rest, out) => visit(base, dir).map((more, found) => Left((more ++ rest, out ++ found)))
    }

  /** a directory: the directories to walk next, and the files found in it */
  private def visit[R[_]](base: Path, dir: Node)(using In[Disk, R]): (List[Node], Vector[DbFile]) ! R =
    val name = dir.name
    val parent = Option(dir.path.getParent).flatMap(p => Option(p.getFileName)).map(_.toString).getOrElse("")
    if dir.path != base && skip(name) then pure((Nil, Vector.empty))
    else if name == "semanticdb" && parent == "META-INF" then collect(base, dir.path).map(found => (Nil, found))
    else if classDirs(name) then
      // a class directory is never walked: only its META-INF/semanticdb
      val db = dir.path.resolve("META-INF").resolve("semanticdb")
      Disk.isDirectory[R](db).flatMap(isDb => if isDb then collect(base, db).map(found => (Nil, found))
        else pure((Nil, Vector.empty)))
    else Disk.entries[R](dir.path).map { nodes =>
      // a SCIP index (`index.scip`, any `*.scip`): the module is the directory it sits in
      val scip = nodes.filter(n => !n.directory && n.name.endsWith(".scip")).map { n =>
        val rel = base.relativize(dir.path).toString.replace('\\', '/')
        val module = if rel.isEmpty then Option(base.getFileName).map(_.toString).getOrElse(".") else rel
        DbFile(n.path, dir.path, module, test = false, n.mtime, n.size, scip = true)
      }
      (nodes.filter(_.directory).toList, scip)
    }

  /** the `.semanticdb` files under one SemanticDB directory, at any depth */
  private def collect[R[_]](base: Path, db: Path)(using In[Disk, R]): Vector[DbFile] ! R =
    val (module, test) = moduleOf(base, db)
    loop[(List[Path], Vector[DbFile]), Vector[DbFile], R]((List(db), Vector.empty)) {
      case (Nil, out) => pure(Right(out))
      case (dir :: rest, out) => Disk.entries[R](dir).map { nodes =>
        val found = nodes.filter(n => !n.directory && n.name.endsWith(".semanticdb"))
          .map(n => DbFile(n.path, db, module, test, n.mtime, n.size))
        Left((nodes.filter(_.directory).map(_.path).toList ++ rest, out ++ found))
      }
    }

  /**
   * The module is the directory that owns `target`, relative to the
   * workspace, with a cross-build's `.jvm` folded away (`okay-stream/.jvm`
   * is `okay-stream`) and other platforms named (`okay-stream (js)`).
   */
  def moduleOf(base: Path, db: Path): (String, Boolean) =
    val rel = base.relativize(db).toString.replace('\\', '/')
    val parts = rel.split('/').toVector
    val t = parts.indexOf("target")
    val owner = if t < 0 then Vector.empty else parts.take(t)
    val config = if t < 0 then "" else parts.lift(t + 2).getOrElse("")
    val test = config.startsWith("test")
    // a cross-build's platform folder: `.jvm` is the module itself,
    // `.js`/`.native` are the same module on another platform
    val (folded, platform) = owner.lastOption match
      case Some(".jvm") | Some("jvm") => (owner.init, "")
      case Some(p) if p == ".js" || p == ".native" || p == "js" || p == "native" =>
        (owner.init, s" (${p.stripPrefix(".")})")
      case _ => (owner, "")
    val name =
      (if folded.isEmpty then Option(base.getFileName).map(_.toString).getOrElse(".")
       else folded.mkString("/")) + platform
    (if test then s"$name:test" else name, test)
