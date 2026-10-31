package ase.core

/** Position eines Schritts im Lauf. Auf oberster Ebene die statische Nummer wie im Explain; in einem Loch
  * zusätzlich die laufende Nummer des Fills an dieser Stelle und die Nummer im Teilterm: 4.2.1 = Loch 4, zweiter
  * Fill dort, Schritt 1. Die Fill-Nummer zählt über Versuche und über Durchläufe von RepeatUntil weiter, damit
  * jeder Aufruf eines Lochs eigene Positionen hat.
  */
final case class StepId(parts: Vector[Int]):
  def show: String = parts.mkString(".")

  /** Loch-Verschachtelung: 0 auf oberster Ebene, je Loch zwei Stellen mehr. */
  def level: Int = parts.size / 2

  /** Die umschließenden Löcher, innerstes zuerst. */
  def holes: List[StepId] = (parts.size - 2 to 1 by -2).map(k => StepId(parts.take(k))).toList

object StepId:
  def parse(s: String): Option[StepId] =
    val parts = s.split('.').toVector.map(_.toIntOption)
    Option.when(parts.nonEmpty && parts.forall(_.isDefined))(StepId(parts.flatten))

/** Ein Werkzeugaufruf der Engine: was Execute am Dateisystem oder als Prozess tut. FileExists stammt aus Prädikaten. */
enum Call:
  case ReadFile(path: String)
  case ListDir(path: String)
  case WriteFile(path: String, content: String)
  case EditFile(path: String, expectedOld: String, replacement: String)
  case MoveFile(from: String, to: String)
  case Shell(cmd: String, args: List[String])
  case FileExists(path: String)

  /** Die Operationsart, die die Hülle erlauben muss; FileExists braucht keine. */
  def op: Option[OpKind] = this match
    case _: ReadFile   => Some(OpKind.ReadFile)
    case _: ListDir    => Some(OpKind.ListDir)
    case _: WriteFile  => Some(OpKind.WriteFile)
    case _: EditFile   => Some(OpKind.EditFile)
    case _: MoveFile   => Some(OpKind.MoveFile)
    case _: Shell      => Some(OpKind.Shell)
    case _: FileExists => None

  /** Das Ziel des Aufrufs, wie im Term (für Fehler ohne aufgelösten Pfad). */
  def target: String = this match
    case ReadFile(p)       => p
    case ListDir(p)        => p
    case WriteFile(p, _)   => p
    case EditFile(p, _, _) => p
    case MoveFile(f, _)    => f
    case Shell(cmd, _)     => cmd
    case FileExists(p)     => p

  def writes: Boolean = this match
    case _: WriteFile | _: EditFile | _: MoveFile => true
    case _                                        => false

/** Laufzeitfehler eines Werkzeugaufrufs. Ans Modell geht er als Handlungsanweisung (Marshal). */
enum ToolError:
  case InvalidPath(path: String, reason: String)
  case OutsidePrefix(path: String, prefix: String)
  case OutsideWorkspace(path: String)
  /** Der aufgelöste Pfad liegt im Git-Verzeichnis, etwa über einen Symlink auf .git. */
  case GitDirLocked(path: String)
  case Unresolvable(path: String)
  case NotFound(path: String)
  case NotAFile(path: String)
  case NotADirectory(path: String)
  case SymlinkNotAllowed(path: String)
  case ParentMissing(path: String)
  case AlreadyExists(path: String)
  case EmptyExpectedOld(path: String)
  case CasMissing(path: String)
  case CasAmbiguous(path: String, count: Int)
  case TooLarge(path: String, bytes: Long, limit: Long)
  case NotText(path: String)
  case ShellNotAllowed(cmd: String, args: List[String])
  case ShellArgRejected(cmd: String, arg: String, reason: String)
  case ProgramNotFound(cmd: String)
  /** git läuft nur, wenn das Arbeitsverzeichnis selbst ein Repository mit echtem .git-Verzeichnis ist. */
  case NoGitRepository
  case Timeout(cmd: String, seconds: Long, output: String)
  case OpNotAllowed(op: OpKind)
  case BudgetExhausted(maxSteps: Int)
  /** Ein-/Ausgabefehler: nur die Art (Klassenname der Ausnahme) und der Pfad relativ zum Arbeitsverzeichnis, nie
    * der Ausnahmetext; der kann Pfade außerhalb nennen.
    */
  case Io(kind: String, path: String)

/** Antwort der Werkzeugschicht. touched: aufgelöste Pfade relativ zum Arbeitsverzeichnis, an denen ein Syscall
  * stattfand; leer heißt: vor jedem Syscall abgelehnt. note: kurze Angabe für das Lauf-Log.
  */
final case class Reply(touched: List[String], result: Either[ToolError, StepResult], note: String)

/** Antwort des Modells auf einen Fill: die Eingabe von submit_term als JSON-Text, oder keine verwertbare Antwort. */
enum ModelReply:
  case Answer(submission: String, tokens: Long)
  case Failed(reason: String, tokens: Long)

  def tokens: Long

/** Wo ein Fill abgelehnt wurde: keine Antwort, Decoder der Hülle (D#25) oder DryRun gegen die Hülle (Tor 2). */
enum Gate:
  case Answer, Decode, DryRun

enum Limit:
  case TotalSteps(max: Int)
  case ModelCalls(max: Int)
  case Time(seconds: Long)
  case Depth(max: Int)

enum AbortReason:
  case StepFailed(at: StepId, error: ToolError)
  case AttemptsExhausted(at: StepId, attempts: Int)
  case BudgetExhausted(at: StepId, maxSteps: Int)
  case LimitReached(limit: Limit)

/** Ausgang eines Laufs; geht als done | ask | aborted an check.sh (D#17). */
enum Outcome:
  case Done
  case Ask(question: String)
  case Aborted(reason: AbortReason)

  def label: String = this match
    case Done       => "done"
    case Ask(_)     => "ask"
    case Aborted(_) => "aborted"

/** Ereignis im Record-Log (Event Sourcing). Das erste ist Start mit dem termHash, das letzte End mit dem Ausgang
  * und der Schrittzahl, wie die Engine sie gezählt hat (D#14).
  */
enum Event:
  case Start(termHash: TermHash, root: Envelope, term: Term)
  case Step(at: StepId, call: Call, reply: Reply)
  case Rejected(at: StepId, op: OpKind)
  case Hole(at: StepId, goal: String, envelope: Envelope)
  case Fill(at: StepId, attempt: Int, callId: String, reply: ModelReply)
  case FillRejected(at: StepId, attempt: Int, gate: Gate, term: Option[Term], reasons: List[String], toModel: String)
  case FillAccepted(at: StepId, attempt: Int, hash: TermHash, worstCase: Long, budget: Int, term: Term)
  case FillFailed(at: StepId, attempt: Int, error: ToolError, toModel: String)
  /** Ein Fill endete ohne Done, mit Schritten und Restbudget: das Loch wird erneut gefüllt (D#55). */
  case HoleRound(at: StepId, round: Int)
  case HoleDone(at: StepId, summary: String)
  /** Abnahme nach Durchlauf pass eines Lochs (D#80); at ist der Schritt der Abnahme. toModel: was der nächste
    * Durchlauf erfährt; fehlt, wenn bestanden oder kein Durchlauf mehr folgt.
    */
  case Verified(at: StepId, pass: Int, passed: Boolean, toModel: Option[String])
  case Asked(at: StepId, question: String, answer: Option[String])
  case Finished(at: StepId, summary: String)
  case End(outcome: Outcome, steps: Long)
