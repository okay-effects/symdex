package app

object Parsed:
  def all(xs: List[Either[String, Int]]): Either[String, List[Int]] =
    xs.collectFirst { case Left(e) => e }.toLeft(xs.collect { case Right(x) => x })
