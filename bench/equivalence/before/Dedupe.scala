package app

object Dedupe:
  def index(pairs: List[(String, Int)]): Map[String, Int] = pairs.toMap
