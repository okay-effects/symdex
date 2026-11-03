package app

object Ratio:
  def safeDiv(a: Int, b: Int): Option[Int] = Option.when(b != 0)(a / b)
