package app

object Fallback:
  def pick(mine: Option[Int], shared: Option[Int]): Option[Int] = mine.orElse(shared)
