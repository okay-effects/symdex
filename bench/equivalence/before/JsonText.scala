package app

object JsonText:
  def str(s: String): String = "\"" + s.flatMap {
    case '\\' => "\\\\"
    case '"' => "\\\""
    case c => c.toString
  } + "\""
