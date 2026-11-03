package app

object Versions:
  def compare(a: List[Int], b: List[Int]): Int =
    a.zipAll(b, 0, 0).find(_ != _).map((x, y) => x.compare(y)).getOrElse(0)
