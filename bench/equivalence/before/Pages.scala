package app

object Pages:
  def count(total: Int, size: Int): Int = (total + size - 1) / size
