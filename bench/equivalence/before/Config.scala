package app

object Config:
  def firstPort(ports: List[Int]): Int = ports.headOption.getOrElse(8080)
