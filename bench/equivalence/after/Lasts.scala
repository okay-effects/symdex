package app

object Lasts:
  def lastOr(xs: List[Int], default: Int): Int = xs.foldLeft(default)((_, x) => x)
