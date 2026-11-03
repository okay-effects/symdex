package app

object Segments:
  def join(parts: List[String]): String =
    parts.foldLeft("")((acc, p) => if acc.isEmpty then p else acc + "/" + p)
