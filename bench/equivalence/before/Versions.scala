package app

object Versions:
  def compare(a: List[Int], b: List[Int]): Int = (a, b) match
    case (Nil, Nil) => 0
    case (Nil, _) => -1
    case (_, Nil) => 1
    case (x :: xs, y :: ys) => if x != y then x.compare(y) else compare(xs, ys)
