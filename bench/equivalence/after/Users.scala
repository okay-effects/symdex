package app

object Users:
  def display(first: Option[String], last: Option[String]): Option[String] =
    for f <- first; l <- last yield s"$f $l"
