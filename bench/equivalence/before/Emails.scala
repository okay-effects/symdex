package app

object Emails:
  def domain(e: String): Option[String] = e.split('@') match
    case Array(_, d) => Some(d)
    case _ => None
