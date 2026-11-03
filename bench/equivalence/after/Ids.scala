package app

object Ids:
  def next(used: Set[Int]): Int = (1 to used.size + 1).find(!used(_)).get
