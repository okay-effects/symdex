package app

object Search:
  def firstBig(xs: List[Int]): Option[Int] = xs.find(_ > 100)
