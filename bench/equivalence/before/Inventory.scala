package app

object Inventory:
  def merge(a: Map[String, Int], b: Map[String, Int]): Map[String, Int] =
    b.foldLeft(a) { case (m, (k, v)) => m.updated(k, m.getOrElse(k, 0) + v) }
