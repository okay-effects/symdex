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

/** a sum type: its generator derived case by case */
enum Size:
  case Small, Medium
  case Custom(n: Int)

object Sizes:
  def price(s: Size): Int = s match
    case Size.Small => 1
    case Size.Medium => 2
    case Size.Custom(n) => n
  def priceCapped(s: Size): Int = s match
    case Size.Small => 1
    case Size.Medium => 2
    case Size.Custom(n) => n min 10
