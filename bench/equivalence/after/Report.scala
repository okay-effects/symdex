package app

object Report:
  def range(xs: List[Int]): (Int, Int) =
    xs.foldLeft((Int.MaxValue, Int.MinValue))((acc, x) => (acc._1.min(x), acc._2.max(x)))
