package app

object Bounds:
  def clamp(x: Int, lo: Int, hi: Int): Int = math.min(hi, math.max(lo, x))
