package app

object Window:
  def pairs(xs: List[Int]): List[(Int, Int)] = xs.zip(xs.drop(1))
