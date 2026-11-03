package app

object Users:
  def display(first: Option[String], last: Option[String]): Option[String] =
    first.map(f => last.fold(f)(l => s"$f $l"))
