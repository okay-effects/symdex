package app

final case class Task(name: String, priority: Int)

object Tasks:
  def ordered(mine: List[Task], theirs: List[Task]): List[Task] = (theirs ++ mine).sortBy(_.priority)
