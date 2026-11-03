package app

object Search:
  def firstBig(xs: List[Int]): Option[Int] = xs.filter(_ > 100).headOption
