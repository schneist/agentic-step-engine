package ase.core

/** Pfade relativ zum Arbeitsverzeichnis, rein syntaktisch: DryRun berührt kein Dateisystem.
  * Execute baut Pfade nur aus den geparsten Segmenten und prüft zusätzlich gegen echte Pfade.
  * Die Regeln gelten auf allen Plattformen gleich (D#29, D#31).
  */
object RelPath:
  private val Drive = "^[A-Za-z]:.*".r
  private val Reserved: Set[String] =
    Set("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") ++
      (List("1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³").flatMap(d => List(s"COM$d", s"LPT$d")))

  def parse(raw: String): Either[String, Vector[String]] =
    if raw.isEmpty then Left("Pfad ist leer")
    else if raw.exists(Text.isDangerous) then Left("Pfad enthält Steuerzeichen")
    else if raw.contains('\\') then Left("Backslash ist in Pfaden nicht erlaubt; Trenner ist /")
    else if raw.startsWith("/") || Drive.matches(raw) then
      Left("absoluter Pfad; erlaubt sind nur Pfade relativ zum Arbeitsverzeichnis")
    else
      raw.split('/').toVector.foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) {
        case (Left(e), _)           => Left(e)
        case (Right(acc), "" | ".") => Right(acc)
        case (Right(_), "..")       => Left("„..“ ist nicht erlaubt; nenne den Pfad direkt")
        case (Right(acc), segment)  => checkSegment(segment).toLeft(acc :+ segment)
      }

  /** .git: wer die Repo-Konfiguration schreibt, startet über git beliebige Programme (core.fsmonitor, Hooks).
    * Windows-Fallen: Datenströme (a.txt:x), Gerätenamen (NUL, CON.txt), still entfernte Punkte und Leerzeichen am Ende.
    */
  private def checkSegment(segment: String): Option[String] =
    if segment.equalsIgnoreCase(".git") then Some("das Git-Verzeichnis ist gesperrt")
    else if segment.contains(':') then Some("Doppelpunkt ist in Pfaden nicht erlaubt")
    else if Reserved(segment.takeWhile(_ != '.').trim.toUpperCase) then Some(s"„$segment“ ist ein reservierter Gerätename")
    else if segment.endsWith(".") || segment.endsWith(" ") then Some(s"„$segment“ endet mit Punkt oder Leerzeichen")
    else None

  def show(segments: Vector[String]): String =
    if segments.isEmpty then "." else segments.mkString("/")

  def isUnder(raw: String, prefix: Vector[String]): Boolean =
    parse(raw).exists(_.startsWith(prefix))

  /** Liegt einer der beiden Pfade unter dem anderen? */
  def overlaps(a: Vector[String], b: Vector[String]): Boolean =
    a.startsWith(b) || b.startsWith(a)
