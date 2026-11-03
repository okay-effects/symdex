package app

object Settings:
  def timeout(raw: Option[String]): Either[String, Int] = raw match
    case None => Left("missing")
    case Some(s) => s.toIntOption match
      case Some(n) if n > 0 => Right(n)
      case Some(_) => Left("not positive")
      case None => Left(s"bad: $s")
