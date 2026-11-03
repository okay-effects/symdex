package app

object Initials:
  def of(first: String, last: String): String = (first.headOption ++ last.headOption).mkString.toUpperCase
