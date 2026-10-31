package ase.core

import scala.collection.mutable

/** Aufbereitung für das Modell, eine Instanz pro Ergebnistyp: Kürzung mit Hinweis, Fehler als Handlungsanweisung. */
trait Marshal[A]:
  def forModel(a: A): String

object Marshal:
  def apply[A](a: A)(using m: Marshal[A]): String = m.forModel(a)

  given Marshal[Violation] = _.render
  given Marshal[DecodeError] = _.message

  given Marshal[StepResult] = r => (r.exitCode.map(c => s"Exit-Code $c").toList ++ r.output.toList).mkString("\n")

  given Marshal[ToolError] =
    case ToolError.InvalidPath(p, reason) => s"Pfad „$p“ ist ungültig: $reason."
    case ToolError.OutsidePrefix(p, prefix) =>
      s"Pfad „$p“ liegt außerhalb von $prefix; erlaubt ist nur $prefix. Ist dort etwas betroffen, nenne es im Ergebnis."
    case ToolError.OutsideWorkspace(p) =>
      s"Pfad „$p“ führt aus dem Arbeitsverzeichnis heraus (Symlink); nutze nur Pfade, die im Arbeitsverzeichnis bleiben."
    case ToolError.GitDirLocked(p) =>
      s"Pfad „$p“ führt in das Git-Verzeichnis; das ist gesperrt. Nutze Pfade außerhalb von .git."
    case ToolError.Unresolvable(p)      => s"Pfad „$p“ lässt sich nicht auflösen (Symlink ins Leere?); nimm einen anderen Pfad."
    case ToolError.NotFound(p)          => s"„$p“ gibt es nicht; prüfe den Pfad mit ListDir."
    case ToolError.NotAFile(p)          => s"„$p“ ist keine normale Datei."
    case ToolError.NotADirectory(p)     => s"„$p“ ist kein Verzeichnis; lies Dateien mit ReadFile."
    case ToolError.SymlinkNotAllowed(p) => s"„$p“ ist ein Symlink; MoveFile verschiebt nur normale Dateien."
    case ToolError.ParentMissing(p)     => s"Das Verzeichnis für „$p“ existiert nicht; lege Dateien nur in vorhandenen Verzeichnissen an."
    case ToolError.AlreadyExists(p) =>
      s"„$p“ existiert schon; WriteFile und MoveFile überschreiben nichts. Ändere bestehende Dateien mit EditFile."
    case ToolError.EmptyExpectedOld(p) => s"expectedOld für „$p“ ist leer; nenne den Text, der ersetzt werden soll."
    case ToolError.CasMissing(p) =>
      s"expectedOld kommt in „$p“ nicht vor; lies die Datei und übernimm den Text exakt, mit Leerzeichen und Zeilenumbrüchen."
    case ToolError.CasAmbiguous(p, n) =>
      s"expectedOld kommt in „$p“ $n-mal vor; erweitere den Kontext, bis er genau einmal vorkommt."
    case ToolError.TooLarge(p, bytes, limit) =>
      s"„$p“ ist mit $bytes Bytes zu groß für EditFile (höchstens $limit); ändere eine kleinere Datei."
    case ToolError.NotText(p)                => s"„$p“ ist kein UTF-8-Text; EditFile ändert nur Text."
    case ToolError.ShellNotAllowed(cmd, args) => s"Befehl „${(cmd :: args).mkString(" ")}“ ist hier nicht erlaubt."
    case ToolError.ShellArgRejected(cmd, a, r) => s"Argument „$a“ für $cmd abgelehnt: $r."
    case ToolError.ProgramNotFound(cmd)       => s"Programm „$cmd“ wurde nicht gefunden oder ist nicht ausführbar."
    case ToolError.NoGitRepository =>
      "Das Arbeitsverzeichnis ist kein eigenes Git-Repository (kein .git-Verzeichnis); git ist hier nicht verfügbar."
    case ToolError.Timeout(cmd, seconds, out) =>
      val tail = if out.isBlank then "" else s"\nAusgabe bis dahin:\n$out"
      s"„$cmd“ lief länger als $seconds s und wurde samt Kindprozessen beendet.$tail"
    case ToolError.OpNotAllowed(op)      => s"$op ist in dieser Hülle nicht erlaubt."
    case ToolError.BudgetExhausted(max)  => s"Das Schrittbudget des Lochs ($max) ist aufgebraucht."
    case ToolError.Io(kind, p)           => s"Ein-/Ausgabefehler ($kind) bei „$p“."

  /** Was nach einer Ablehnung an das Modell geht. */
  def rejection(reasons: List[String]): String =
    ("Dein Teilterm wurde abgelehnt und nicht ausgeführt:" :: reasons.map("- " + _)).mkString("\n") +
      "\nLiefere einen neuen Teilterm innerhalb der Hülle."

  /** Was der nächste Durchlauf eines Lochs über die nicht bestandene Abnahme erfährt (D#80). Die Ausgabe der Abnahme
    * steht nicht darin: Sie sieht das Loch nur, wenn sein Scope sie zulässt (D#9).
    */
  def verifyFailed(v: Verify, at: StepId, result: StepResult, pass: Int): String =
    val code = result.exitCode.fold("")(c => s" (Exit-Code $c)")
    s"Abnahme nach Durchlauf $pass von ${v.max} nicht bestanden: „${v.command}“ erfüllte „${Pred.show(v.until)}“ nicht$code. " +
      s"Dies ist ein neuer Durchlauf; was die Abnahme ausgab, steht unter Schritt ${at.show}, wenn dieses Loch es sieht."

  /** Was nach einem Laufzeitfehler an das Modell geht (D#15): Gelaufenes bleibt gelaufen. */
  def failure(at: StepId, error: ToolError): String =
    s"Schritt ${at.show} ist gescheitert: ${Marshal(error)} Was davor lief, bleibt bestehen. Liefere einen neuen Teilterm."

  /** Ausschnitt eines Textes nach denselben Regeln wie beim Lesen großer Dateien. */
  def excerpt(text: String, maxBytes: Int, headLines: Int, tailLines: Int): Excerpt.Result =
    val e = Excerpt(maxBytes, headLines, tailLines)
    e.add(text.toCharArray, text.length)
    e.result

  /** Ausschnitt großer Ausgaben, ohne alles im Speicher zu halten: erst der volle Text bis maxBytes, danach nur die
    * ersten und letzten Zeilen (jede auf eine Länge begrenzt) und die Zeilenzahl. Wird nur am Rand benutzt.
    */
  final class Excerpt(maxBytes: Int, headLines: Int, tailLines: Int):
    private val lineCap = math.max(80, maxBytes / math.max(1, headLines + tailLines))
    private val full = new StringBuilder
    private var bytes = 0L
    private var overflow = false
    private var head = Vector.empty[String]
    private val tail = mutable.Queue.empty[String]
    private val current = new StringBuilder
    private var completed = 0L
    private var cutLines = false

    private def width(c: Char): Int = if c < 0x80 then 1 else if c < 0x800 then 2 else 3

    private def cap(line: String): String =
      if line.length > lineCap then
        cutLines = true
        line.take(lineCap) + " …"
      else line

    private def push(line: String): Unit =
      completed += 1
      if head.size < headLines then head = head :+ cap(line)
      else
        tail.enqueue(cap(line))
        if tail.size > tailLines then tail.dequeue()

    def add(chunk: Array[Char], n: Int): Unit =
      var i = 0
      while i < n do
        val c = chunk(i)
        if !overflow then
          bytes += width(c)
          full.append(c)
          if bytes > maxBytes then switch()
        else if c == '\n' then
          push(current.toString)
          current.clear()
        else if current.length <= lineCap then current.append(c)
        i += 1

    /** Ab hier nur noch Kopf, Schwanz und Zählung. */
    private def switch(): Unit =
      overflow = true
      val parts = full.toString.split("\n", -1)
      parts.init.foreach(push)
      current.append(parts.last.take(lineCap + 1))
      full.clear()

    def result: Excerpt.Result =
      if !overflow then
        val text = full.toString
        val lines = if text.isEmpty then 0L else text.count(_ == '\n').toLong + (if text.endsWith("\n") then 0 else 1)
        Excerpt.Result(text, lines, truncated = false)
      else
        val last = Option.when(current.nonEmpty)(cap(current.toString)).toList
        val total = completed + last.size
        val shownTail = (tail.toList ++ last).takeRight(math.max(tailLines, 1))
        val omitted = math.max(0L, total - head.size - shownTail.size)
        val note =
          s"… gekürzt: $omitted von $total Zeilen ausgelassen" +
            (if cutLines then ", lange Zeilen abgeschnitten" else "") +
            "; suche gezielter (grep) oder lies einen engeren Pfad …"
        Excerpt.Result((head ++ (note :: shownTail)).mkString("\n"), total, truncated = true)

  object Excerpt:
    final case class Result(text: String, lines: Long, truncated: Boolean)
