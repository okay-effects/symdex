package app

object Ids:
  def next(used: Set[Int]): Int = Iterator.from(1).find(!used(_)).get
