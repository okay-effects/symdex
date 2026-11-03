package app

object Access:
  def allowed(admin: Boolean, owner: Option[String], user: String): Boolean = admin || owner.contains(user)
