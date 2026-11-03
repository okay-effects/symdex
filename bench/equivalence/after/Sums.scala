package app

object Sums:
  def total(xs: List[Int]): Int = xs.foldRight(0)(_ + _)
