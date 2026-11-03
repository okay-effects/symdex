package app

object Bounds:
  def clamp(x: Int, lo: Int, hi: Int): Int = math.max(lo, math.min(hi, x))
