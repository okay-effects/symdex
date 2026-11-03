package app

object Settings:
  def timeout(raw: Option[String]): Either[String, Int] =
    raw.toRight("missing").flatMap(s => s.toIntOption.toRight(s"bad: $s")).filterOrElse(_ > 0, "not positive")
