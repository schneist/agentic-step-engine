package ase.core

/** Pfade relativ zum Arbeitsverzeichnis, rein syntaktisch: DryRun berührt kein Dateisystem.
  * Execute prüft zusätzlich gegen echte Pfade (toRealPath des tiefsten existierenden Elternverzeichnisses).
  * Die Regeln gelten auf allen Plattformen gleich, auch wo das Dateisystem sie nicht bräuchte (D#29).
  */
object RelPath:
  private val Drive = "^[A-Za-z]:.*".r
  private val Reserved = (Set("CON", "PRN", "AUX", "NUL") ++ (1 to 9).flatMap(i => List(s"COM$i", s"LPT$i"))).toSet

  def parse(raw: String): Either[String, Vector[String]] =
    if raw.isEmpty then Left("Pfad ist leer")
    else if raw.contains('\u0000') then Left("Pfad enthält ein Nullzeichen")
    else if raw.startsWith("/") || raw.startsWith("\\") || Drive.matches(raw) then
      Left("absoluter Pfad; erlaubt sind nur Pfade relativ zum Arbeitsverzeichnis")
    else
      raw.split("[/\\\\]").toVector.foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) {
        case (Left(e), _)           => Left(e)
        case (Right(acc), "" | ".") => Right(acc)
        case (Right(acc), "..") =>
          if acc.isEmpty then Left("Pfad verlässt das Arbeitsverzeichnis") else Right(acc.init)
        case (Right(acc), segment) => checkSegment(segment).toLeft(acc :+ segment)
      }

  /** Windows-Fallen: Datenströme (a.txt:x), Gerätenamen (NUL, CON.txt), still entfernte Punkte und Leerzeichen am Ende. */
  private def checkSegment(segment: String): Option[String] =
    if segment.contains(':') then Some("Doppelpunkt ist in Pfaden nicht erlaubt")
    else if Reserved(segment.takeWhile(_ != '.').toUpperCase) then Some(s"„$segment“ ist ein reservierter Gerätename")
    else if segment.endsWith(".") || segment.endsWith(" ") then Some(s"„$segment“ endet mit Punkt oder Leerzeichen")
    else None

  def show(segments: Vector[String]): String =
    if segments.isEmpty then "." else segments.mkString("/")

  def isUnder(raw: String, prefix: Vector[String]): Boolean =
    parse(raw).exists(_.startsWith(prefix))

  /** Liegt einer der beiden Pfade unter dem anderen? */
  def overlaps(a: Vector[String], b: Vector[String]): Boolean =
    a.startsWith(b) || b.startsWith(a)
