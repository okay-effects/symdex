package app

object Lasts:
  def lastOr(xs: List[Int], default: Int): Int = xs.lastOption.getOrElse(default)
