package app

final case class Line(sku: String, qty: Int, price: BigDecimal)

object Orders:
  def total(lines: List[Line]): BigDecimal = lines.map(l => l.price * l.qty).sum
