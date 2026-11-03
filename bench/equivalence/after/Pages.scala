package app

object Pages:
  def count(total: Int, size: Int): Int = math.ceil(total.toDouble / size).toInt
