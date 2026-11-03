package app

object Sums:
  def total(xs: List[Int]): Int = xs.foldLeft(0)(_ + _)
