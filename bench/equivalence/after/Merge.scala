package app

object Merge:
  def sorted(a: List[Int], b: List[Int]): List[Int] = (b ++ a).sorted
