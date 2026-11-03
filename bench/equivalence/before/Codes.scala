package app

object Codes:
  def parse(s: String): Option[Int] = scala.util.Try(s.toInt).toOption
