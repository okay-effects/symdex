package app

object Batch:
  def chunks(xs: List[Int], n: Int): List[List[Int]] = if n <= 0 then List(xs) else xs.grouped(n).toList
