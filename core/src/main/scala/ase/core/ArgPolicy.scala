package ase.core

/** Was nach den Pflicht-Argumenten eines Befehls an Nicht-Flag-Argumenten stehen darf. */
enum Positional:
  case NoArgs
  case Paths
  case PatternThenPaths
  case PathsAfterDashDash
  case RevisionThenPathsAfterDashDash

/** Argument-Policy pro Programm (D#12): erlaubte Flags als Allowlist, Nicht-Flag-Argumente sind Pfade.
  * Nennt ein Aufruf keinen Pfad, berührt er das Arbeitsverzeichnis "." (D#27). values: was der Wert eines Flags mit
  * Wert sein darf (D#83); ohne Regel ist er beliebiger Text, der nicht mit "-" beginnt.
  */
final case class ArgPolicy(
    binary: String,
    prefixArgs: List[String],
    flags: Set[String],
    valuedFlags: Set[String],
    prefixFlags: Set[String],
    positional: Positional,
    values: Map[String, ValueRule] = Map.empty
):
  /** Nimmt der Befehl Pfade an: als Nicht-Flag-Argument oder als Wert eines Flags nach Regel Path (D#83)? */
  def takesPaths: Boolean = positional != Positional.NoArgs || values.values.exists(_.isInstanceOf[ValueRule.Path])

/** Regel für den Wert eines Flags mit Wert (D#83). Path: nach einem festen Präfix wie "file://" ein Pfad im
  * Arbeitsverzeichnis, geprüft wie bei ReadFile; er zählt zu den Pfaden des Aufrufs. Pattern: Text, der ganz auf das
  * Muster passt.
  */
enum ValueRule:
  case Path(prefix: String = "")
  case Pattern(regex: String)

object ArgPolicy:
  val defaults: List[ArgPolicy] = List(
    ArgPolicy("ls", Nil, Set("-l", "-a"), Set.empty, Set.empty, Positional.Paths),
    ArgPolicy(
      "grep",
      Nil,
      Set("-r", "-n", "-i", "-l", "-F", "-E", "-w"),
      Set.empty,
      Set("--include="),
      Positional.PatternThenPaths
    ),
    ArgPolicy("git", List("status"), Set("--porcelain", "--short"), Set.empty, Set.empty, Positional.NoArgs),
    ArgPolicy("git", List("diff"), Set("--stat", "--name-only"), Set.empty, Set.empty, Positional.PathsAfterDashDash),
    ArgPolicy("git", List("log"), Set("--oneline"), Set("-n"), Set.empty, Positional.RevisionThenPathsAfterDashDash),
    ArgPolicy("sbt", List("test"), Set.empty, Set.empty, Set.empty, Positional.NoArgs)
  )

  /** Skript im Arbeitsverzeichnis ohne Argumente, etwa ./run-tests.sh. */
  def script(binary: String): ArgPolicy =
    ArgPolicy(binary, Nil, Set.empty, Set.empty, Set.empty, Positional.NoArgs)

  /** Die Policy mit dem längsten passenden Präfix. */
  def find(policies: List[ArgPolicy], cmd: String, args: List[String]): Option[ArgPolicy] =
    policies.filter(p => p.binary == cmd && args.startsWith(p.prefixArgs)).maxByOption(_.prefixArgs.size)

  /** Abgelehnte Argumente mit Grund und die ausdrücklich genannten Pfade; pathAt sind deren Stellen in args mit dem
    * Präfix, das dort vor dem Pfad steht (Execute gibt dort Präfix und normalisierten Pfad weiter).
    */
  final case class Parsed(rejected: List[(String, String)], paths: List[String], pathAt: List[(Int, String)]):
    /** Was der Aufruf berührt: ohne Pfadargument das Arbeitsverzeichnis (D#27). */
    def touched: List[String] = if paths.isEmpty then List(".") else paths

  private val CombinedShort = "^-[A-Za-z]{2,}$".r

  def parse(policy: ArgPolicy, args: List[String]): Parsed =
    import Positional.*
    val dashDashAllowed = policy.positional == PathsAfterDashDash || policy.positional == RevisionThenPathsAfterDashDash

    def flagAllowed(arg: String): Boolean =
      policy.flags(arg) ||
        policy.prefixFlags.exists(p => arg.startsWith(p) && arg.length > p.length) ||
        (CombinedShort.matches(arg) && arg.drop(1).forall(c => policy.flags(s"-$c")))

    def allowedFlags: String =
      val all = (policy.flags ++ policy.valuedFlags ++ policy.prefixFlags.map(_ + "…")).toList.sorted
      if all.isEmpty then "keine" else all.mkString(" ")

    def value(arg: String, v: String, i: Int): Either[String, Option[(String, Int, String)]] =
      policy.values.get(arg) match
        case None => Right(None)
        case Some(ValueRule.Path(prefix)) =>
          if v.startsWith(prefix) && v.length > prefix.length then Right(Some((v.drop(prefix.length), i, prefix)))
          else Left(s"Wert „$v“ muss ein Pfad${if prefix.isEmpty then "" else s" nach „$prefix“"} sein")
        case Some(ValueRule.Pattern(regex)) =>
          Either.cond(v.matches(regex), None, s"Wert „$v“ passt nicht auf das Muster $regex")

    // paths: Pfad mit seiner Stelle in args und dem Präfix davor
    @annotation.tailrec
    def loop(rest: List[String], afterDashDash: Boolean, positionals: Int, rejected: List[(String, String)], paths: List[(String, Int, String)]): Parsed =
      val i = args.size - rest.size
      rest match
        case Nil => Parsed(rejected.reverse, paths.reverse.map(_._1), paths.reverse.map(p => (p._2, p._3)))
        case arg :: tail if afterDashDash =>
          loop(tail, true, positionals, rejected, (arg, i, "") :: paths)
        case "--" :: tail =>
          if dashDashAllowed then loop(tail, true, positionals, rejected, paths)
          else loop(tail, false, positionals, ("--", "„--“ ist hier nicht vorgesehen") :: rejected, paths)
        case arg :: tail if arg.startsWith("-") && arg != "-" =>
          if policy.valuedFlags(arg) then
            tail match
              case v :: more if !v.startsWith("-") =>
                value(arg, v, i + 1) match
                  case Right(path)   => loop(more, false, positionals, rejected, path.toList ++ paths)
                  case Left(reason)  => loop(more, false, positionals, (arg, reason) :: rejected, paths)
              case _ => loop(tail, false, positionals, (arg, "erwartet einen Wert") :: rejected, paths)
          else if flagAllowed(arg) then loop(tail, false, positionals, rejected, paths)
          else loop(tail, false, positionals, (arg, s"Flag nicht erlaubt; erlaubt sind: $allowedFlags") :: rejected, paths)
        case arg :: tail =>
          policy.positional match
            case NoArgs =>
              loop(tail, false, positionals, (arg, "keine weiteren Argumente erlaubt") :: rejected, paths)
            case Paths =>
              loop(tail, false, positionals + 1, rejected, (arg, i, "") :: paths)
            case PatternThenPaths =>
              if positionals == 0 then loop(tail, false, 1, rejected, paths)
              else loop(tail, false, positionals + 1, rejected, (arg, i, "") :: paths)
            case PathsAfterDashDash =>
              loop(tail, false, positionals, (arg, "Pfade erst nach „--“") :: rejected, paths)
            case RevisionThenPathsAfterDashDash =>
              if positionals == 0 then loop(tail, false, 1, rejected, paths)
              else loop(tail, false, positionals, (arg, "nur eine Revision; Pfade erst nach „--“") :: rejected, paths)

    loop(args.drop(policy.prefixArgs.size), false, 0, Nil, Nil)
