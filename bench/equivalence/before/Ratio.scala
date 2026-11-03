package app

object Ratio:
  def safeDiv(a: Int, b: Int): Option[Int] = if b != 0 then Some(a / b) else None
