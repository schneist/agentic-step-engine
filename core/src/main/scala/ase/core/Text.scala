package ase.core

/** Zeichen, die eine Anzeige verfälschen können: Steuerzeichen (C0, DEL, C1), Zeilen- und Absatztrenner,
  * Richtungssteuerung (Bidi). In Pfaden verboten, in Explain sichtbar gemacht (D#31).
  */
object Text:
  def isDangerous(c: Char): Boolean =
    c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == ' ' || c == ' ' ||
      (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩') || c == '‎' || c == '‏'

  /** Jedes gefährliche Zeichen als sichtbares Escape, damit Text eine Zeile bleibt. */
  def visible(s: String): String =
    if !s.exists(isDangerous) then s
    else
      s.flatMap {
        case '\n'                => "\\n"
        case '\r'                => "\\r"
        case '\t'                => "\\t"
        case c if isDangerous(c) => f"\\u${c.toInt}%04x"
        case c                   => c.toString
      }

  /** Einsame Surrogate lassen sich nicht als UTF-8 codieren; RFC 8785 verlangt dafür einen Fehler (D#31). */
  def hasLoneSurrogate(s: String): Boolean =
    s.indices.exists { i =>
      val c = s.charAt(i)
      (c.isHighSurrogate && (i + 1 >= s.length || !s.charAt(i + 1).isLowSurrogate)) ||
      (c.isLowSurrogate && (i == 0 || !s.charAt(i - 1).isHighSurrogate))
    }

/** Schrittzahlen sättigend (D#31): ein Überlauf darf ein Budget nie unterlaufen. Beide Werte sind nicht negativ. */
object Steps:
  def add(a: Long, b: Long): Long = if a > Long.MaxValue - b then Long.MaxValue else a + b
  def mul(a: Long, b: Long): Long = if a != 0 && b > Long.MaxValue / a then Long.MaxValue else a * b
