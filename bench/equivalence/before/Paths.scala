package app

object Paths:
  /** a path as the index names a file: relative to the project's root */
  def relative(root: String, path: String): String =
    val r = root.stripSuffix("/") + "/"
    if path.startsWith(r) then path.drop(r.length) else path.stripPrefix("./")
