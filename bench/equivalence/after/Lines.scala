package app

object Lines:
  def indent(l: String): Int = l.indexWhere(_ != ' ')
