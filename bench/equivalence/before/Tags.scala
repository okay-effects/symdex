package app

object Tags:
  def parse(s: Option[String]): List[String] = s.toList.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty))
