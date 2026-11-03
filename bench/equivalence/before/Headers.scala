package app

object Headers:
  def effective(defaults: Map[String, String], request: Map[String, String]): Map[String, String] = defaults ++ request
