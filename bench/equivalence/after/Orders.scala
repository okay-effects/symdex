package app

final case class Line(sku: String, qty: Int, price: BigDecimal)

object Orders:
  def total(lines: List[Line]): BigDecimal = lines.foldLeft(BigDecimal(0))((acc, l) => acc + l.price * l.qty)
