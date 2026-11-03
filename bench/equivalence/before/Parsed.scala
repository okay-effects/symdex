package app

object Parsed:
  def all(xs: List[Either[String, Int]]): Either[String, List[Int]] =
    xs.foldRight[Either[String, List[Int]]](Right(Nil))((e, acc) => for x <- e; rest <- acc yield x :: rest)
