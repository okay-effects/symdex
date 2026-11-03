package app

object JsonText:
  def str(s: String): String = "\"" + s.replace("\"", "\\\"").replace("\\", "\\\\") + "\""
