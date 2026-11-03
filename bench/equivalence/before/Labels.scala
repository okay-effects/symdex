package app

object Labels:
  def count(labels: List[String]): Int = labels.distinct.size
