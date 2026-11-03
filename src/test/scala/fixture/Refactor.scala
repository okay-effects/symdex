package fixture

final case class Line(sku: String, qty: Int)

/** refactorings, kept and broken: what `equivalent` is tested on */
object Refactor:
  def total(xs: Option[List[Int]]): Int = xs.map(_.sum).getOrElse(0)
  def totalFold(xs: Option[List[Int]]): Int = xs.fold(0)(_.sum)
  def totalFirst(xs: Option[List[Int]]): Int = xs.flatMap(_.headOption).getOrElse(0)
  def firstBig(lines: List[Line], min: Int): Option[String] = lines.find(_.qty >= min).map(_.sku)
  def firstBigStrict(lines: List[Line], min: Int): Option[String] =
    lines.filter(_.qty > min).headOption.map(_.sku)
  def greet(name: String): Unit = println(s"hi $name")
