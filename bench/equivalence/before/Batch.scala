package app

object Batch:
  def chunks(xs: List[Int], n: Int): List[List[Int]] = xs.grouped(n).toList
