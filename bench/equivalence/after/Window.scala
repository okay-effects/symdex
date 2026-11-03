package app

object Window:
  def pairs(xs: List[Int]): List[(Int, Int)] = xs.sliding(2).collect { case List(a, b) => (a, b) }.toList
