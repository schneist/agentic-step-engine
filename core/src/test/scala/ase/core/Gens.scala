package ase.core

import org.scalacheck.Gen

/** Generatoren: beliebige Terme (Codec, Schema, Fingerprint) und gültige Terme innerhalb einer Hülle (DryRun). */
object Gens:
  val segment: Gen[String] = Gen.oneOf("src", "docs", "lib", "a", "b")
  val fileName: Gen[String] = Gen.oneOf("Main.scala", "api.md", "x.txt", "README.md")
  val text: Gen[String] = Gen.oneOf(
    Gen.alphaNumStr,
    Gen.asciiPrintableStr,
    Gen.const("€ \"zitiert\" \\ Zeile\nzwei ${x} ü \u0001"),
    Gen.const("")
  )
  val origin: Gen[Origin] =
    Gen.oneOf(Gen.const(Origin.Human), Gen.oneOf("plan-1", "fill-2", "fill-17").map(Origin.Model(_)))

  def pathUnder(prefix: Vector[String]): Gen[String] =
    for
      n <- Gen.choose(0, 2)
      dirs <- Gen.listOfN(n, segment)
      f <- fileName
    yield (prefix ++ dirs :+ f).mkString("/")

  val anyPath: Gen[String] =
    Gen.oneOf(pathUnder(Vector.empty), Gen.const("../geheim.txt"), Gen.const("/etc/passwd"), Gen.const("."))

  /** Befehle, für die es eine Standard-Policy gibt. */
  val commands: List[AllowedCommand] = List(
    AllowedCommand("ls", Nil),
    AllowedCommand("grep", Nil),
    AllowedCommand("git", List("status")),
    AllowedCommand("git", List("diff")),
    AllowedCommand("git", List("log")),
    AllowedCommand("sbt", List("test"))
  )

  val anyEnvelope: Gen[Envelope] =
    for
      ops <- Gen.someOf(OpKind.values.toList)
      prefix <- Gen.oneOf(".", "src", "docs/", "CHANGELOG.md")
      steps <- Gen.choose(0, 30)
      depth <- Gen.choose(0, 3)
      shell <- Gen.someOf(commands)
    yield Envelope(ops.toSet, prefix, steps, depth, shell.toSet)

  def anyPred(level: Int): Gen[Pred] =
    val leaves = Gen.oneOf(
      anyPath.map(Pred.FileExists(_)),
      Gen.choose(-1, 255).map(Pred.LastExitCode(_)),
      text.map(Pred.LastOutputContains(_)),
      Gen.const(Pred.LastOutputEmpty)
    )
    if level <= 0 then leaves
    else
      Gen.frequency(
        4 -> leaves,
        1 -> Gen.lzy(anyPred(level - 1)).map(Pred.Not(_)),
        1 -> Gen.zip(Gen.lzy(anyPred(level - 1)), Gen.lzy(anyPred(level - 1))).map((a, b) => Pred.And(a, b))
      )

  def anyTerm(level: Int): Gen[Term] =
    val args = Gen.choose(0, 3).flatMap(Gen.listOfN(_, text))
    val leaves: Gen[Term] = Gen.oneOf(
      Gen.zip(anyPath, origin).map((p, o) => Term.ReadFile(p, o)),
      Gen.zip(anyPath, origin).map((p, o) => Term.ListDir(p, o)),
      Gen.zip(anyPath, text, origin).map((p, c, o) => Term.WriteFile(p, c, o)),
      Gen.zip(anyPath, text, text, origin).map((p, a, b, o) => Term.EditFile(p, a, b, o)),
      Gen.zip(anyPath, anyPath, origin).map((a, b, o) => Term.MoveFile(a, b, o)),
      Gen.zip(Gen.oneOf("ls", "grep", "git", "curl"), args, origin).map((c, a, o) => Term.Shell(c, a, o)),
      Gen.zip(text, origin).map((q, o) => Term.Ask(q, o)),
      Gen.zip(text, origin).map((s, o) => Term.Done(s, o)),
      Gen.zip(text, anyEnvelope, origin).map((g, e, o) => Term.Hole(g, e, o))
    )
    if level <= 0 then leaves
    else
      val below = Gen.lzy(anyTerm(level - 1))
      Gen.frequency(
        6 -> leaves,
        1 -> Gen.zip(Gen.choose(0, 3).flatMap(Gen.listOfN(_, below)), origin).map((s, o) => Term.Seq(s, o)),
        1 -> Gen.zip(anyPred(2), below, below, origin).map((p, a, b, o) => Term.If(p, a, b, o)),
        1 -> Gen.zip(below, anyPred(2), Gen.choose(0, 5), origin).map((b, p, m, o) => Term.RepeatUntil(b, p, m, o))
      )

  // ---------- gültig innerhalb einer Hülle

  /** Wohlgeformte Wurzel-Hüllen mit Shell nur, wenn es Befehle gibt. */
  val rootEnvelope: Gen[Envelope] =
    for
      ops <- Gen.someOf(OpKind.values.toList.filterNot(_ == OpKind.Shell))
      withShell <- Gen.oneOf(true, false)
      shell <- if withShell then Gen.atLeastOne(commands) else Gen.const(Nil)
      prefix <- Gen.oneOf(".", "src")
      steps <- Gen.choose(5, 40)
      depth <- Gen.choose(0, 2)
    yield
      val usableShell = usable(prefix, shell)
      val withUsable = withShell && usableShell.nonEmpty
      val allOps = ops.toSet ++ (if withUsable then Set(OpKind.Shell) else Set.empty) ++ Set(OpKind.Hole)
      Envelope(allOps, prefix, steps, depth, if withUsable then usableShell else Set.empty)

  /** Befehle, die unter dem Präfix nutzbar sind: unter einem Unterpfad nur solche, die Pfade nehmen (D#27, D#31). */
  def usable(prefix: String, cs: Iterable[AllowedCommand]): Set[AllowedCommand] =
    cs.filter(c => Attenuation.wellFormed(Envelope(Set(OpKind.Shell), prefix, 1, 0, Set(c)), Location.root).isEmpty).toSet

  /** Eine Hülle, die parent abschwächt: Teilmenge der Operationen und Befehle, gleicher oder engerer Pfad, Tiefe - 1. */
  def childEnvelope(parent: Envelope, maxSteps: Int): Gen[Envelope] =
    for
      ops <- Gen.someOf(parent.allowedOps.toList)
      shell <- Gen.someOf(parent.shellAllowlist.toList)
      ext <- Gen.oneOf(Vector.empty[String], Vector("src"))
    yield
      val prefix = RelPath.parse(parent.pathPrefix).getOrElse(Vector.empty) ++ ext
      val usableShell = usable(RelPath.show(prefix), shell)
      val withShell = ops.contains(OpKind.Shell) && usableShell.nonEmpty
      val finalOps = ops.toSet.filterNot(o => o == OpKind.Shell && !withShell)
      Envelope(
        if finalOps.isEmpty then Set(OpKind.Hole) else finalOps,
        RelPath.show(prefix),
        maxSteps,
        parent.maxDepth - 1,
        if withShell then usableShell else Set.empty
      )

  /** Ein Shell-Aufruf, der Allowlist und Policy genügt und nur Pfade unter prefix berührt (D#27). */
  private def validShell(env: Envelope, prefix: Vector[String], origin: Origin): List[Gen[Term]] =
    env.shellAllowlist.toList.sortBy(_.show).flatMap { c =>
      ArgPolicy.find(ArgPolicy.defaults, c.binary, c.requiredPrefixArgs).flatMap { p =>
        val tail: Option[Gen[List[String]]] = p.positional match
          case Positional.NoArgs                         => Option.when(prefix.isEmpty)(Gen.const(Nil))
          case Positional.Paths                          => Some(pathUnder(prefix).map(List(_)))
          case Positional.PatternThenPaths               => Some(pathUnder(prefix).map(List("muster", _)))
          case Positional.PathsAfterDashDash             => Some(pathUnder(prefix).map(List("--", _)))
          case Positional.RevisionThenPathsAfterDashDash => Some(pathUnder(prefix).map(List("HEAD", "--", _)))
        tail.map(_.map(t => Term.Shell(c.binary, c.requiredPrefixArgs ++ t, origin)))
      }
    }

  def validTerm(env: Envelope, budget: Int, level: Int, o: Origin = Origin.Human): Gen[Term] =
    val prefix = RelPath.parse(env.pathPrefix).getOrElse(Vector.empty)
    val path = pathUnder(prefix)
    def allowed(op: OpKind, g: => Gen[Term]): List[Gen[Term]] = if env.allowedOps(op) then List(g) else Nil
    val leaves: List[Gen[Term]] =
      List(Gen.const(Term.Done("fertig", o))) ++
        allowed(OpKind.ReadFile, path.map(Term.ReadFile(_, o))) ++
        allowed(OpKind.ListDir, path.map(Term.ListDir(_, o))) ++
        allowed(OpKind.WriteFile, path.map(Term.WriteFile(_, "inhalt", o))) ++
        allowed(OpKind.EditFile, path.map(Term.EditFile(_, "alt", "neu", o))) ++
        allowed(OpKind.MoveFile, Gen.zip(path, path).map((a, b) => Term.MoveFile(a, b, o))) ++
        allowed(OpKind.Ask, Gen.const(Term.Ask("wie weiter?", o))) ++
        (if env.allowedOps(OpKind.Shell) then validShell(env, prefix, o) else Nil) ++
        Option.when(env.allowedOps(OpKind.Hole) && env.maxDepth >= 1 && budget >= 2)(
          Gen.choose(1, budget - 1).flatMap(childEnvelope(env, _)).map(Term.Hole("ziel", _, o))
        )
    val leaf = Gen.oneOf(leaves).flatMap(identity)
    val pred = path.flatMap(p => Gen.oneOf(Pred.FileExists(p), Pred.Not(Pred.FileExists(p))))
    if level <= 0 || budget < 2 then leaf
    else
      Gen.frequency(
        4 -> leaf,
        1 -> Gen.choose(0, math.min(3, budget)).flatMap { k =>
          Gen.listOfN(k, Gen.lzy(validTerm(env, budget / math.max(k, 1), level - 1, o))).map(Term.Seq(_, o))
        },
        1 -> Gen.zip(pred, Gen.lzy(validTerm(env, budget, level - 1, o)), Gen.lzy(validTerm(env, budget, level - 1, o)))
          .map((p, a, b) => Term.If(p, a, b, o)),
        1 -> Gen.choose(1, math.min(3, budget)).flatMap { m =>
          Gen.zip(Gen.lzy(validTerm(env, budget / m, level - 1, o)), pred).map((b, p) => Term.RepeatUntil(b, p, m, o))
        }
      )

  /** Ein Paar aus Wurzel-Hülle und gültigem Term darin. */
  val validPlan: Gen[(Envelope, Term)] =
    rootEnvelope.flatMap(env => validTerm(env, env.maxSteps, 3).map(env -> _))

  /** Wohlgeformte Loch-Hüllen für AdversarialFill. */
  val holeEnvelope: Gen[Envelope] =
    for
      root <- rootEnvelope
      steps <- Gen.choose(1, 12)
      depth <- Gen.choose(0, 2)
      child <- childEnvelope(root.copy(maxDepth = depth + 1), steps)
    yield child

/** Ausbruchsversuche eines Fills gegen die Hülle seines Lochs. */
enum Attack:
  case EscapeWorkspace, AbsolutePath, OutsidePrefix, ForbiddenOp, ShellNotAllowed, ShellBadFlag, TooDeep, OverBudget

/** Test-Modelladapter-Generator: liefert für ein Loch Ausbruchsversuche, die Tor 2 ablehnen muss. */
object AdversarialFill:
  private val o = Origin.Model("fill-adversarial")

  private def pathLeaf(hole: Envelope, raw: String): Term =
    if hole.allowedOps(OpKind.ReadFile) then Term.ReadFile(raw, o)
    else Term.If(Pred.FileExists(raw), Term.Done("ja", o), Term.Done("nein", o), o)

  def attacks(hole: Envelope): List[(Attack, Gen[Term])] =
    val prefix = RelPath.parse(hole.pathPrefix).getOrElse(Vector.empty)
    val inside = Gens.pathUnder(prefix)
    val forbidden = OpKind.values.toList.filterNot(hole.allowedOps)
    List(
      Some(Attack.EscapeWorkspace -> Gen.oneOf("../secrets.txt", "src/../../x", "..").map(pathLeaf(hole, _))),
      Some(Attack.AbsolutePath -> Gen.oneOf("/etc/passwd", "C:/Windows/win.ini", "\\\\server\\share").map(pathLeaf(hole, _))),
      Option.when(prefix.nonEmpty)(Attack.OutsidePrefix -> Gen.const(pathLeaf(hole, "zz-ausserhalb/x.txt"))),
      Option.when(forbidden.nonEmpty)(Attack.ForbiddenOp -> Gen.oneOf(forbidden).flatMap(op => inside.map(p => leafFor(op, p, hole)))),
      Some(Attack.ShellNotAllowed -> Gen.const(Term.Shell("curl", List("https://example.org"), o))),
      Option.when(hole.allowedOps(OpKind.Shell) && hole.shellAllowlist.nonEmpty)(
        Attack.ShellBadFlag -> Gen.oneOf(hole.shellAllowlist.toList).map(c =>
          Term.Shell(c.binary, c.requiredPrefixArgs ++ List("--output=../x"), o)
        )
      ),
      Some(Attack.TooDeep -> Gen.const(Term.Hole("tiefer", hole, o))),
      Some(Attack.OverBudget -> Gen.const(Term.Seq(List.fill(hole.maxSteps + 1)(Term.Done("x", o)), o)))
    ).flatten

  private def leafFor(op: OpKind, p: String, hole: Envelope): Term = op match
    case OpKind.ReadFile  => Term.ReadFile(p, o)
    case OpKind.ListDir   => Term.ListDir(p, o)
    case OpKind.WriteFile => Term.WriteFile(p, "x", o)
    case OpKind.EditFile  => Term.EditFile(p, "a", "b", o)
    case OpKind.MoveFile  => Term.MoveFile(p, p + ".neu", o)
    case OpKind.Shell     => Term.Shell("ls", List(p), o)
    case OpKind.Ask       => Term.Ask("?", o)
    case OpKind.Hole      => Term.Hole("x", hole.copy(maxDepth = hole.maxDepth - 1), o)

  def gen(hole: Envelope): Gen[(Attack, Term)] =
    Gen.oneOf(attacks(hole)).flatMap((a, g) => g.map(a -> _))

  /** Welcher Verstoß die Ablehnung begründen muss. */
  def expected(attack: Attack): Violation => Boolean =
    import Violation.*
    attack match
      case Attack.EscapeWorkspace | Attack.AbsolutePath => { case _: InvalidPath => true; case _ => false }
      case Attack.OutsidePrefix                         => { case _: PathOutsidePrefix => true; case _ => false }
      case Attack.ForbiddenOp                           => { case _: OpNotAllowed => true; case _ => false }
      case Attack.ShellNotAllowed =>
        { case _: ShellNotAllowed | OpNotAllowed(_, OpKind.Shell, _) => true; case _ => false }
      case Attack.ShellBadFlag => { case _: ShellArgRejected => true; case _ => false }
      case Attack.TooDeep =>
        { case _: DepthNotSmaller | OpNotAllowed(_, OpKind.Hole, _) => true; case _ => false }
      case Attack.OverBudget => { case _: StepBudgetExceeded => true; case _ => false }
