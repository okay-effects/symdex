package app

object Grades:
  def letter(score: Int): Char = if score >= 90 then 'A' else if score >= 80 then 'B' else 'C'
