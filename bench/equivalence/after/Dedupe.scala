package app

object Dedupe:
  def index(pairs: List[(String, Int)]): Map[String, Int] = pairs.groupBy(_._1).map((k, v) => k -> v.head._2)
