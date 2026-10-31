package ase.core

import cats.data.NonEmptyList

/** Konfiguration der Prüfung: Verschachtelungsgrenze (D#10) und Argument-Policies (D#12).
  * workspaceCode (D#12): Programme, die Code aus dem Arbeitsverzeichnis ausführen, und dessen Pfade; Programme, die
  * mit "./" beginnen, führen sich selbst aus. pathless (D#39): Befehle, deren Ausgabe ohne Pfadargument keine
  * Dateiinhalte trägt. Beide bestimmen mit, was ein Loch sieht (Scope.shellPaths).
  */
final case class CheckConfig(
    nestingLimit: Int,
    argPolicies: List[ArgPolicy],
    workspaceCode: Map[String, List[String]],
    pathless: List[AllowedCommand]
):
  def runsWorkspaceCode(cmd: String): Option[List[String]] =
    if cmd.startsWith("./") then Some(List(cmd.drop(2))) else workspaceCode.get(cmd)

object CheckConfig:
  val default: CheckConfig = CheckConfig(
    DecodeLimits.DefaultNesting,
    ArgPolicy.defaults,
    Map("sbt" -> List("build.sbt", "project", "src")),
    List(AllowedCommand("git", List("log")))
  )

/** Tor 1 und Tor 2: führt nichts aus, prüft den Term gegen eine Hülle. */
object DryRun:
  /** Ein geprüfter Term (D#11). Nur DryRun erzeugt ihn; freigegeben ist er damit noch nicht. Er trägt die
    * Prüfkonfiguration und die gebundenen Flow-Parameter, damit Explain und Execute dieselben verwenden (D#40).
    */
  final class CheckedTerm private[DryRun] (
      val term: Term,
      val rootEnvelope: Envelope,
      val termHash: TermHash,
      val worstCaseSteps: Long,
      val depth: Int,
      val config: CheckConfig,
      val params: Map[String, String]
  )

  /** Ein Fill-Teilterm, der Tor 2 bestanden hat (D#11). Läuft ohne eigene Freigabe (D#6). */
  final class CheckedFill private[DryRun] (
      val term: Term,
      val holeEnvelope: Envelope,
      val termHash: TermHash,
      val worstCaseSteps: Long
  )

  /** params: die Werte, die Flow.bind schon in den Term eingesetzt hat; nur zur Anzeige im Explain (D#22). */
  def plan(
      term: Term,
      root: Envelope,
      config: CheckConfig,
      params: Map[String, String] = Map.empty
  ): Either[NonEmptyList[Violation], CheckedTerm] =
    analyse(term, root, config).map(a => CheckedTerm(term, root, TermHash.of(term, root), a.worstCase, a.holeDepth, config, params))

  /** Ein Fill ohne einen einzigen Schritt (auch ohne Done) würde das Loch stumm beenden; er ist ein Fehlversuch (D#56). */
  def fill(term: Term, hole: Envelope, config: CheckConfig): Either[NonEmptyList[Violation], CheckedFill] =
    analyse(term, hole, config).flatMap { a =>
      if a.worstCase == 0 then Left(NonEmptyList.one(Violation.EmptyFill(Location.root)))
      else Right(CheckedFill(term, hole, TermHash.of(term, hole), a.worstCase))
    }

  /** Welche Felder das Ergebnis eines Schritts haben kann (D#20). */
  final case class Shape(exitCode: Boolean, output: Boolean)

  final case class Ctx(env: Envelope, prefix: Vector[String], loc: Location, last: Option[Shape], config: CheckConfig)

  /** last: das Ergebnis, auf das sich ein folgendes Prädikat bezieht. holeDepth: schlechteste Loch-Verschachtelung (D#14). */
  final case class Analysis(violations: Vector[Violation], worstCase: Long, last: Option[Shape], holeDepth: Int)

  type Check[A] = Ctx => Analysis

  def analyse(term: Term, env: Envelope, config: CheckConfig): Either[NonEmptyList[Violation], Analysis] =
    val envLoc = Location.root / "envelope"
    RelPath.parse(env.pathPrefix) match
      case Left(reason) =>
        Left(NonEmptyList.one(Violation.InvalidPath(envLoc / "pathPrefix", env.pathPrefix, reason)))
      case Right(prefix) =>
        val a = AgentAlg.fromTerm(term)(interpreter)(Ctx(env, prefix, Location.root, None, config))
        val nesting = Term.nesting(term)
        val all = Attenuation.wellFormed(env, envLoc, config).toVector ++
          Option.when(nesting > config.nestingLimit)(Violation.NestingTooDeep(Location.root, nesting, config.nestingLimit)) ++
          a.violations ++
          Option.when(a.worstCase > env.maxSteps)(Violation.StepBudgetExceeded(Location.root, a.worstCase, env.maxSteps))
        NonEmptyList.fromList(all.toList).toLeft(a)

  private val Output = Shape(exitCode = false, output = true)
  private val NoResult = Shape(exitCode = false, output = false)
  private val Process = Shape(exitCode = true, output = true)

  private def merge(a: Option[Shape], b: Option[Shape]): Option[Shape] =
    if a.isEmpty && b.isEmpty then None
    else
      val x = a.getOrElse(NoResult)
      val y = b.getOrElse(NoResult)
      Some(Shape(x.exitCode || y.exitCode, x.output || y.output))

  private def op(kind: OpKind, ctx: Ctx): Vector[Violation] =
    if ctx.env.allowedOps(kind) then Vector.empty
    else Vector(Violation.OpNotAllowed(ctx.loc, kind, ctx.env.allowedOps))

  private def path(raw: String, loc: Location, ctx: Ctx): Vector[Violation] =
    RelPath.parse(raw) match
      case Left(reason) => Vector(Violation.InvalidPath(loc, raw, reason))
      case Right(p) =>
        if p.startsWith(ctx.prefix) then Vector.empty
        else Vector(Violation.PathOutsidePrefix(loc, raw, ctx.env.pathPrefix))

  private def leaf(kind: Option[OpKind], shape: Shape, paths: (String, String)*)(ctx: Ctx): Analysis =
    val v = kind.toVector.flatMap(op(_, ctx)) ++ paths.flatMap((field, raw) => path(raw, ctx.loc / field, ctx))
    Analysis(v, 1L, Some(shape), 0)

  /** Kann das Prädikat bei diesem vorigen Ergebnis wahr werden? (kann wahr, kann falsch) */
  private def possible(p: Pred, last: Shape): (Boolean, Boolean) = p match
    case Pred.FileExists(_)         => (true, true)
    case Pred.LastExitCode(_)       => (last.exitCode, true)
    case Pred.LastOutputContains(_) => (last.output, true)
    case Pred.LastOutputEmpty       => (last.output, true)
    case Pred.Not(a) =>
      val (t, f) = possible(a, last)
      (f, t)
    case Pred.And(a, b) =>
      val (ta, fa) = possible(a, last)
      val (tb, fb) = possible(b, last)
      (ta && tb, fa || fb)

  private val writes = Set(OpKind.WriteFile, OpKind.EditFile, OpKind.MoveFile)

  /** Ein Loch, das ändern darf, darf den Code seiner Abnahme nicht ändern können, sonst bestünde es sie selbst (D#80).
    * Code der Abnahme: das Skript bei "./…", sonst workspaceCode des Programms. Weder darf der Code unter dem
    * pathPrefix liegen noch der pathPrefix im Code: Unter src/test könnte ein Loch die Tests ändern, die sbt ausführt.
    * Was die Abnahme liest, ohne es auszuführen, schützt das nicht; das ist meist gerade das Ergebnis des Lochs.
    */
  private def writableVerifier(v: Verify, hole: Envelope, loc: Location, config: CheckConfig): Vector[Violation] =
    if !hole.allowedOps.exists(writes) then Vector.empty
    else
      RelPath.parse(hole.pathPrefix).toOption.toVector.flatMap { prefix =>
        config.runsWorkspaceCode(v.cmd).toList.flatten
          .filter(code => RelPath.parse(code).exists(RelPath.overlaps(prefix, _)))
          .map(Violation.VerifierWritable(loc, _, hole.pathPrefix))
      }

  private def filePaths(p: Pred): List[String] = p match
    case Pred.FileExists(path) => List(path)
    case Pred.Not(a)           => filePaths(a)
    case Pred.And(a, b)        => filePaths(a) ++ filePaths(b)
    case _                     => Nil

  private def predicate(p: Pred, last: Option[Shape], loc: Location, ctx: Ctx): Vector[Violation] =
    val nesting = Pred.nesting(p)
    val deep = Option.when(nesting > ctx.config.nestingLimit)(Violation.NestingTooDeep(loc, nesting, ctx.config.nestingLimit))
    val paths = filePaths(p).toVector.flatMap(path(_, loc, ctx))
    val never = Option.when(!possible(p, last.getOrElse(NoResult))._1)(Violation.PredicateNeverTrue(loc, p))
    deep.toVector ++ paths ++ never

  private[core] val interpreter: AgentAlg[Check] = new AgentAlg[Check]:
    def readFile(path: String, origin: Origin): Check[StepResult] =
      leaf(Some(OpKind.ReadFile), Output, "path" -> path)
    def listDir(path: String, origin: Origin): Check[StepResult] =
      leaf(Some(OpKind.ListDir), Output, "path" -> path)
    def writeFile(path: String, content: String, origin: Origin): Check[StepResult] =
      leaf(Some(OpKind.WriteFile), NoResult, "path" -> path)
    def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): Check[StepResult] =
      leaf(Some(OpKind.EditFile), NoResult, "path" -> path)
    def moveFile(from: String, to: String, origin: Origin): Check[StepResult] =
      leaf(Some(OpKind.MoveFile), NoResult, "from" -> from, "to" -> to)
    def ask(question: String, origin: Origin): Check[StepResult] =
      leaf(Some(OpKind.Ask), Output)
    def done(summary: String, origin: Origin): Check[StepResult] =
      leaf(None, Output)

    def shell(cmd: String, args: List[String], origin: Origin): Check[StepResult] = ctx =>
      val loc = ctx.loc
      val allowlist =
        if ctx.env.shellAllowlist.exists(_.allows(cmd, args)) then Vector.empty
        else Vector(Violation.ShellNotAllowed(loc, cmd, args, ctx.env.shellAllowlist))
      val policy = ArgPolicy.find(ctx.config.argPolicies, cmd, args) match
        case None => Vector(Violation.NoArgPolicy(loc, cmd, args))
        case Some(p) =>
          val parsed = ArgPolicy.parse(p, args)
          parsed.rejected.toVector.map((arg, reason) => Violation.ShellArgRejected(loc / "args", cmd, arg, reason)) ++
            parsed.touched.toVector.flatMap(path(_, loc / "args", ctx))
      Analysis(op(OpKind.Shell, ctx) ++ allowlist ++ policy, 1L, Some(Process), 0)

    def think(goal: String, envelope: Envelope, verify: Option[Verify], origin: Origin): Check[StepResult] = ctx =>
      val at = ctx.loc / "envelope"
      val v = op(OpKind.Hole, ctx) ++
        Attenuation.wellFormed(envelope, at, ctx.config) ++
        Attenuation.check(envelope, ctx.env, at, Attenuation.Mode.Hole)
      val pass = Steps.add(1L, math.max(envelope.maxSteps, 0).toLong)
      val depth = 1 + math.max(envelope.maxDepth, 0)
      verify match
        case None => Analysis(v, pass, Some(Output), depth)
        case Some(check) =>
          // die Abnahme ist ein Programmaufruf der umgebenden Hülle; das Ergebnis des Lochs ist dann ihres (D#80)
          val loc = ctx.loc / "verify"
          val program = shell(check.cmd, check.args, origin)(ctx.copy(loc = loc))
          val vs = program.violations ++
            predicate(check.until, Some(Process), loc / "until", ctx) ++
            Option.when(check.max < 1)(Violation.InvalidVerify(loc / "max", check.max)) ++
            writableVerifier(check, envelope, loc / "cmd", ctx.config)
          Analysis(v ++ vs, Steps.mul(math.max(check.max, 0).toLong, Steps.add(pass, 1L)), Some(Process), depth)

    def seq(steps: List[Check[StepResult]], origin: Origin): Check[StepResult] = ctx =>
      steps.zipWithIndex.foldLeft(Analysis(Vector.empty, 0L, ctx.last, 0)) { case (acc, (step, i)) =>
        val a = step(ctx.copy(loc = ctx.loc.at("steps", i), last = acc.last))
        Analysis(acc.violations ++ a.violations, Steps.add(acc.worstCase, a.worstCase), a.last, math.max(acc.holeDepth, a.holeDepth))
      }

    def branch(pred: Pred, thenP: Check[StepResult], elseP: Check[StepResult], origin: Origin): Check[StepResult] = ctx =>
      val a = thenP(ctx.copy(loc = ctx.loc / "thenT"))
      val b = elseP(ctx.copy(loc = ctx.loc / "elseT"))
      Analysis(
        predicate(pred, ctx.last, ctx.loc / "pred", ctx) ++ a.violations ++ b.violations,
        math.max(a.worstCase, b.worstCase),
        merge(a.last, b.last),
        math.max(a.holeDepth, b.holeDepth)
      )

    def repeatUntil(body: Check[StepResult], until: Pred, max: Int, origin: Origin): Check[StepResult] = ctx =>
      // ab dem zweiten Durchlauf sieht der Rumpf auch sein eigenes letztes Ergebnis
      val first = body(ctx.copy(loc = ctx.loc / "body"))
      val b = body(ctx.copy(loc = ctx.loc / "body", last = merge(ctx.last, first.last)))
      Analysis(
        Option.when(max < 1)(Violation.InvalidRepeat(ctx.loc, max)).toVector ++
          b.violations ++ predicate(until, b.last, ctx.loc / "until", ctx),
        Steps.mul(math.max(max, 0).toLong, b.worstCase),
        b.last,
        b.holeDepth
      )
