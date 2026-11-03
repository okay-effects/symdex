package app

object Initials:
  def of(first: String, last: String): String = s"${first.take(1)}${last.take(1)}".toUpperCase
