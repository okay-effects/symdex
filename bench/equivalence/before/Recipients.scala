package app

object Recipients:
  def unique(emails: List[String]): List[String] = emails.distinct
