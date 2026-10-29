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
    // Sicht wird wie der pathPrefix nur enger (D#69); ein ungültiger scopePrefix steht in wellFormed
    val scope = (RelPath.parse(child.sees), RelPath.parse(parent.sees)) match
      case (Right(c), Right(p)) => Option.when(!c.startsWith(p))(Violation.WiderScope(loc, child.sees, parent.sees)).toList
      case _                    => Nil
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
    ops ++ prefix ++ scope ++ shell ++ depth ++ steps

  /** Well-Formedness: ein Loch muss lösbar beschränkt sein (D#21). Jeder erlaubte Befehl muss nutzbar sein:
    * es gibt eine Argument-Policy, und unter einem engeren pathPrefix als "." nimmt er Pfade an (D#27, D#31).
    */
  def wellFormed(e: Envelope, loc: Location, config: CheckConfig = CheckConfig.default): List[Violation] =
    val root = RelPath.parse(e.pathPrefix).exists(_.isEmpty)
    val unusable = e.shellAllowlist.toList.sortBy(_.show).flatMap { c =>
      val sameBinary = config.argPolicies.filter(_.binary == c.binary)
      // requiredPrefixArgs legt nur den Unterbefehl fest; Flags regelt die Argument-Policy, Pfade der pathPrefix (D#52)
      val policies = sameBinary.filter(_.prefixArgs.startsWith(c.requiredPrefixArgs))
      if sameBinary.isEmpty then Some(s"„${c.show}“ hat keine Argument-Policy")
      else if policies.isEmpty then
        val commands = sameBinary.map(p => (p.binary :: p.prefixArgs).mkString(" ")).distinct.sorted.mkString(", ")
        Some(s"„${c.show}“: requiredPrefixArgs legt nur einen Unterbefehl fest, keine Flags, Muster oder Pfade; möglich ist: $commands")
      else if !root && policies.forall(_.positional == Positional.NoArgs) then
        Some(s"„${c.show}“ nimmt keine Pfade und berührt damit das ganze Arbeitsverzeichnis, nicht nur ${e.pathPrefix}")
      else None
    }
    val scope = e.scopePrefix.toList.flatMap(s => RelPath.parse(s).left.toOption.map(Violation.InvalidPath(loc / "scopePrefix", s, _)))
    scope ++ (List(
      Option.when(e.maxSteps < 1)("maxSteps muss mindestens 1 sein"),
      Option.when(e.allowedOps.isEmpty)("allowedOps ist leer"),
      Option.when(e.maxDepth < 0)("maxDepth darf nicht negativ sein"),
      Option.when(e.allowedOps(OpKind.Shell) && e.shellAllowlist.isEmpty)("Shell erlaubt, aber kein Befehl in shellAllowlist"),
      Option.when(!e.allowedOps(OpKind.Shell) && e.shellAllowlist.nonEmpty)("shellAllowlist ohne Shell in allowedOps")
    ).flatten ++ unusable).map(Violation.IllFormed(loc, _))
