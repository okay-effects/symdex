package app

object Escape:
  def quote(s: String): String = "\"" + s.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case c => c.toString
  } + "\""
