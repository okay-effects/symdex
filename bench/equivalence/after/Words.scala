package app

object Words:
  def count(s: String): Int = s.split(" ").count(_.nonEmpty)
