package app

object Paths:
  /** a path as the index names a file: relative to the project's root */
  def relative(root: String, path: String): String =
    if path.startsWith(root) then path.drop(root.length).stripPrefix("/") else path.stripPrefix("./")
