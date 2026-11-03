package app

import scala.math.BigDecimal.RoundingMode

object Money:
  def cents(amount: BigDecimal): Long = (amount * 100).toLong
