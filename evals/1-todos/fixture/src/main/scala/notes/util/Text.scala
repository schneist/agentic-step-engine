package notes.util

object Text:
  /** Kürzt einen Text auf n Zeichen.
    * TODO: Wortgrenzen beachten
    */
  def shorten(s: String, n: Int): String = s.take(n)

  val help = "Schreibe TODO: vor eine Notiz, um sie zu markieren"
