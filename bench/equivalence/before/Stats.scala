package app

object Stats:
  def average(xs: List[Int]): Int = if xs.isEmpty then 0 else xs.sum / xs.size
