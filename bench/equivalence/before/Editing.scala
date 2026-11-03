package app

object Editing:
  def canEdit(locked: Boolean, roles: List[String]): Boolean = !locked && roles.contains("editor")
