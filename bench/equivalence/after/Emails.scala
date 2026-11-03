package app

object Emails:
  def domain(e: String): Option[String] = Option(e.indexOf('@')).filter(_ >= 0).map(i => e.substring(i + 1))
