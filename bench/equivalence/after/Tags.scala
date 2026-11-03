package app

object Tags:
  def parse(s: Option[String]): List[String] = s.map(_.split(',').map(_.trim).toList).getOrElse(Nil)
