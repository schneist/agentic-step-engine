package ase.core

/** Hüllen werden nur abgeschwächt, nie erweitert. */
object Attenuation:
  /** Loch unter einer Hülle: maxDepth strikt kleiner. Flow unter der Runner-Hülle: höchstens gleich (D#22). */
  enum Mode:
    case Hole, Flow

  /** c ist gleich oder enger als p: gleiches Programm, p's Pflicht-Argumente sind Präfix von c's (D#21). */
  def refines(c: AllowedCommand, p: AllowedCommand): Boolean =
    c.binary == p.binary && c.requiredPrefixArgs.startsWith(p.requiredPrefixArgs)

  def allowedBy(allowlist: Set[AllowedCommand], c: AllowedCommand): Boolean =
    allowlist.exists(refines(c, _))

  def check(child: Envelope, parent: Envelope, loc: Location, mode: Mode): List[Violation] =
    val extra = child.allowedOps -- parent.allowedOps
    val ops = Option.when(extra.nonEmpty)(Violation.WiderOps(loc, extra)).toList
    val prefix = (RelPath.parse(child.pathPrefix), RelPath.parse(parent.pathPrefix)) match
      case (Left(reason), _) => List(Violation.InvalidPath(loc / "pathPrefix", child.pathPrefix, reason))
      case (_, Left(reason)) => List(Violation.InvalidPath(loc, parent.pathPrefix, reason))
      case (Right(c), Right(p)) =>
        Option.when(!c.startsWith(p))(Violation.WiderPrefix(loc, child.pathPrefix, parent.pathPrefix)).toList
    val shell = child.shellAllowlist.toList
      .filterNot(allowedBy(parent.shellAllowlist, _))
      .sortBy(_.show)
      .map(Violation.WiderShell(loc, _))
    val depth = mode match
      case Mode.Hole => Option.when(child.maxDepth >= parent.maxDepth)(Violation.DepthNotSmaller(loc, child.maxDepth, parent.maxDepth, true))
      case Mode.Flow => Option.when(child.maxDepth > parent.maxDepth)(Violation.DepthNotSmaller(loc, child.maxDepth, parent.maxDepth, false))
    val steps = mode match
      case Mode.Hole => None // über WorstCaseSteps geprüft (D#14)
      case Mode.Flow => Option.when(child.maxSteps > parent.maxSteps)(Violation.WiderSteps(loc, child.maxSteps, parent.maxSteps))
    ops ++ prefix ++ shell ++ depth ++ steps

  /** Well-Formedness: ein Loch muss lösbar beschränkt sein (D#21). */
  def wellFormed(e: Envelope, loc: Location): List[Violation] =
    List(
      Option.when(e.maxSteps < 1)("maxSteps muss mindestens 1 sein"),
      Option.when(e.allowedOps.isEmpty)("allowedOps ist leer"),
      Option.when(e.maxDepth < 0)("maxDepth darf nicht negativ sein"),
      Option.when(e.allowedOps(OpKind.Shell) && e.shellAllowlist.isEmpty)("Shell erlaubt, aber kein Befehl in shellAllowlist"),
      Option.when(!e.allowedOps(OpKind.Shell) && e.shellAllowlist.nonEmpty)("shellAllowlist ohne Shell in allowedOps")
    ).flatten.map(Violation.IllFormed(loc, _))
