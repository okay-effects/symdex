package app

object Routes:
  def primary(hosts: List[String]): Option[String] = hosts.headOption.filter(_.nonEmpty)
