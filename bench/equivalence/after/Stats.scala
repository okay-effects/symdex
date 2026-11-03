package app

object Stats:
  def average(xs: List[Int]): Int =
    val avg = xs.sum / xs.size
    if xs.isEmpty then 0 else avg
