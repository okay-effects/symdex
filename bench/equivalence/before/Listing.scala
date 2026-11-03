package app

object Listing:
  def visible(hidden: Boolean, draft: Boolean): Boolean = !(hidden || draft)
