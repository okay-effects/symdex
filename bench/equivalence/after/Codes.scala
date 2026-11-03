package app

object Codes:
  def parse(s: String): Option[Int] = s.toIntOption
