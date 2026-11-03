package app

object Inventory:
  def merge(a: Map[String, Int], b: Map[String, Int]): Map[String, Int] =
    a ++ b.map((k, v) => k -> (a.getOrElse(k, 0) + v))
