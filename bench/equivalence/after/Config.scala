package app

object Config:
  def firstPort(ports: List[Int]): Int = if ports.isEmpty then 8080 else ports.head
