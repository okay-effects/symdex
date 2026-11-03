package app

import scala.math.BigDecimal.RoundingMode

object Money:
  def cents(amount: BigDecimal): Long = (amount * 100).setScale(0, RoundingMode.HALF_UP).toLong
