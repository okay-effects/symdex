package app

object Access:
  def allowed(admin: Boolean, owner: Option[String], user: String): Boolean = owner.contains(user) || admin
