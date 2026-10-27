package ase.core

/** Ort im Term, etwa `$.steps[2].thenT`. */
opaque type Location = String

object Location:
  val root: Location = "$"

  extension (l: Location)
    def /(field: String): Location = s"$l.$field"
    def at(field: String, i: Int): Location = s"$l.$field[$i]"
    def show: String = l

/** Verstoß gegen Hülle oder Regeln. `message` richtet sich an das Modell: was falsch ist und was erlaubt ist. */
enum Violation:
  case OpNotAllowed(loc: Location, op: OpKind, allowed: Set[OpKind])
  case InvalidPath(loc: Location, path: String, reason: String)
  case PathOutsidePrefix(loc: Location, path: String, prefix: String)
  case ShellNotAllowed(loc: Location, cmd: String, args: List[String], allowlist: Set[AllowedCommand])
  case NoArgPolicy(loc: Location, cmd: String, args: List[String])
  case ShellArgRejected(loc: Location, cmd: String, arg: String, reason: String)
  case WiderOps(loc: Location, extra: Set[OpKind])
  case WiderPrefix(loc: Location, child: String, parent: String)
  case WiderShell(loc: Location, command: AllowedCommand)
  case WiderSteps(loc: Location, child: Int, parent: Int)
  case DepthNotSmaller(loc: Location, child: Int, parent: Int, strict: Boolean)
  case IllFormed(loc: Location, reason: String)
  case StepBudgetExceeded(loc: Location, worstCase: Long, maxSteps: Int)
  case NestingTooDeep(loc: Location, nesting: Int, limit: Int)
  case PredicateNeverTrue(loc: Location, pred: Pred)
  case InvalidRepeat(loc: Location, max: Int)

  def loc: Location

  def message: String = this match
    case OpNotAllowed(_, op, allowed) =>
      s"$op ist hier nicht erlaubt; erlaubt sind: ${Violation.ops(allowed)}."
    case InvalidPath(_, path, reason) =>
      s"Pfad „$path“ ist ungültig: $reason."
    case PathOutsidePrefix(_, path, prefix) =>
      s"Pfad „$path“ liegt außerhalb von $prefix; erlaubt ist nur $prefix. Ist dort etwas betroffen, nenne es im Ergebnis."
    case ShellNotAllowed(_, cmd, args, allowlist) =>
      val allowed = if allowlist.isEmpty then "keine" else allowlist.toList.map(_.show).sorted.mkString(", ")
      s"Befehl „${(cmd :: args).mkString(" ")}“ ist nicht erlaubt; erlaubt sind: $allowed."
    case NoArgPolicy(_, cmd, args) =>
      s"Für „${(cmd :: args).mkString(" ")}“ gibt es keine Argument-Policy; der Befehl lässt sich nicht prüfen."
    case ShellArgRejected(_, cmd, arg, reason) =>
      s"Argument „$arg“ für $cmd abgelehnt: $reason."
    case WiderOps(_, extra) =>
      s"Die Hülle erlaubt mehr als die umgebende: ${Violation.ops(extra)}. Nimm das heraus."
    case WiderPrefix(_, child, parent) =>
      s"pathPrefix $child liegt nicht unter $parent."
    case WiderShell(_, command) =>
      s"Befehl „${command.show}“ ist weiter als alles, was die umgebende Hülle erlaubt."
    case WiderSteps(_, child, parent) =>
      s"maxSteps $child ist größer als die umgebenden $parent."
    case DepthNotSmaller(_, child, parent, strict) =>
      if strict then s"maxDepth $child muss kleiner sein als $parent."
      else s"maxDepth $child darf höchstens $parent sein."
    case IllFormed(_, reason) =>
      s"Loch ist unlösbar beschränkt: $reason."
    case StepBudgetExceeded(_, worstCase, maxSteps) =>
      s"Im schlechtesten Fall $worstCase Schritte, erlaubt sind $maxSteps. Verkleinere Wiederholungen oder Loch-Budgets."
    case NestingTooDeep(_, nesting, limit) =>
      s"Verschachtelung $nesting, erlaubt sind $limit. Flache die Struktur ab."
    case PredicateNeverTrue(_, pred) =>
      s"Bedingung „${Pred.show(pred)}“ kann hier nie wahr werden: der Schritt davor liefert kein passendes Ergebnis."
    case InvalidRepeat(_, max) =>
      s"RepeatUntil braucht max ≥ 1, nicht $max."

  def render: String = s"${loc.show}: $message"

object Violation:
  private def ops(s: Set[OpKind]): String =
    if s.isEmpty then "nichts" else s.toList.sortBy(_.ordinal).mkString(", ")
