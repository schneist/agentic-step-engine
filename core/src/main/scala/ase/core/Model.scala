package ase.core

/** Herkunft eines Term-Knotens (D#19). Zählt für den termHash, nicht für den Fingerprint. */
enum Origin:
  case Human
  case Model(callId: String)

/** Operationsarten, die eine Hülle erlauben kann (D#8, D#18). Seq, If, RepeatUntil und Done sind immer erlaubt. */
enum OpKind:
  case ReadFile, ListDir, WriteFile, EditFile, MoveFile, Shell, Ask, Hole

object OpKind:
  def parse(s: String): Option[OpKind] = values.find(_.toString == s)

/** Erlaubter Shell-Befehl: Programm plus Pflicht-Präfix der Argumente ("git" + List("status"), nicht nur "git"). */
final case class AllowedCommand(binary: String, requiredPrefixArgs: List[String]):
  def allows(cmd: String, args: List[String]): Boolean =
    binary == cmd && args.startsWith(requiredPrefixArgs)

  def show: String = (binary :: requiredPrefixArgs).mkString(" ")

/** Hülle eines Lochs: was dort höchstens passieren darf. scopePrefix: unter welchem Pfad das Loch Ergebnisse früherer
  * Schritte sieht, wenn das weiter sein soll als der pathPrefix, unter dem es liest und ändert (D#69).
  */
final case class Envelope(
    allowedOps: Set[OpKind],
    pathPrefix: String,
    maxSteps: Int,
    maxDepth: Int,
    shellAllowlist: Set[AllowedCommand],
    scopePrefix: Option[String] = None
):
  /** Der Pfad, unter dem das Loch frühere Ergebnisse sieht (D#9, D#69). */
  def sees: String = scopePrefix.getOrElse(pathPrefix)

/** Ergebnis eines Schritts. Prädikate lesen das Ergebnis des unmittelbar vorangehenden Schritts (D#20). */
final case class StepResult(exitCode: Option[Int], output: Option[String])

/** Statisch auswertbare Bedingung, ohne Modell. Fehlt das abgefragte Feld, ist sie falsch (D#20). */
enum Pred:
  case FileExists(path: String)
  case LastExitCode(code: Int)
  case LastOutputContains(text: String)
  case LastOutputEmpty
  case Not(p: Pred)
  case And(p: Pred, q: Pred)

object Pred:
  /** Syntaktische Verschachtelung: Blatt 0, Not und And 1 plus Kinder (D#10). */
  def nesting(p: Pred): Int = p match
    case Not(a)    => 1 + nesting(a)
    case And(a, b) => 1 + math.max(nesting(a), nesting(b))
    case _         => 0

  def show(p: Pred): String = p match
    case FileExists(path)         => s"$path existiert"
    case LastExitCode(code)       => s"Exit-Code $code"
    case LastOutputContains(text) => s"Ausgabe enthält „$text“"
    case LastOutputEmpty          => "Ausgabe leer"
    case Not(a @ And(_, _))       => s"nicht (${show(a)})"
    case Not(a)                   => s"nicht ${show(a)}"
    case And(a, b)                => s"${show(a)} und ${show(b)}"

/** Abnahme eines Lochs (D#80): ein Programm, das nach jedem Durchlauf des Lochs läuft, und die Bedingung, die sein
  * Ergebnis erfüllen muss. Läuft unter der Hülle, in der das Loch steht, nicht unter der des Lochs. max: Durchläufe des
  * Lochs höchstens; jeder Durchlauf nach einer nicht bestandenen Abnahme ist ein neuer Aufruf mit eigenem Budget.
  */
final case class Verify(cmd: String, args: List[String], until: Pred, max: Int):
  def command: String = (cmd :: args).mkString(" ")

/** Die einzige Sprache zwischen Modell und Engine. Daten, kein Code. */
enum Term:
  case ReadFile(path: String, origin: Origin)
  case ListDir(path: String, origin: Origin)
  case WriteFile(path: String, content: String, origin: Origin)
  case EditFile(path: String, expectedOld: String, replacement: String, origin: Origin)
  case MoveFile(from: String, to: String, origin: Origin)
  case Shell(cmd: String, args: List[String], origin: Origin)
  case Ask(question: String, origin: Origin)
  case Done(summary: String, origin: Origin)
  case Seq(steps: List[Term], origin: Origin)
  case If(pred: Pred, thenT: Term, elseT: Term, origin: Origin)
  case RepeatUntil(body: Term, until: Pred, max: Int, origin: Origin)
  /** verify: Abnahme durch ein Programm (D#80); ohne sie ist das Loch mit seinem Done fertig. */
  case Hole(goal: String, envelope: Envelope, origin: Origin, verify: Option[Verify] = None)

  def origin: Origin

object Term:
  /** Nummern, die ein Loch in Explain, Engine, Tighten und Record-Log belegt: eins, mit Abnahme zwei (D#80). */
  def width(verify: Option[Verify]): Int = 1 + verify.size

  /** Syntaktische Verschachtelung: Blätter 0, Seq/If/RepeatUntil 1 plus Kinder (D#10). Nicht dasselbe wie maxDepth. */
  def nesting(t: Term): Int = t match
    case Seq(steps, _)           => 1 + steps.map(nesting).maxOption.getOrElse(0)
    case If(_, a, b, _)          => 1 + math.max(nesting(a), nesting(b))
    case RepeatUntil(b, _, _, _) => 1 + nesting(b)
    case _                       => 0
