package app

object Signup:
  def check(age: Int, name: String): Either[String, Unit] =
    if name.isEmpty then Left("name is required")
    else if age < 0 then Left("age is negative")
    else Right(())
