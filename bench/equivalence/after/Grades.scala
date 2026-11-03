package app

object Grades:
  def letter(score: Int): Char = score / 10 match
    case 10 | 9 => 'A'
    case 8 => 'B'
    case _ => 'C'
