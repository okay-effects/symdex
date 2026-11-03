package app

object Segments:
  def join(parts: List[String]): String =
    parts.foldRight("")((p, acc) => if acc.isEmpty then p else p + "/" + acc)
