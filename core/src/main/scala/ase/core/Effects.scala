package ase.core

/** Wirkungen nach außen (D#103, O#58). Die Konfiguration sagt je Argument-Policy, was ein Aufruf außerhalb des
  * Arbeitsverzeichnisses bewirkt (Effect). Hier stehen die reinen Fragen dazu: welche Befehle eines Terms wirken, und
  * wo ein Lauf ohne Menschen (--no-ask) etwas bewirken könnte, ohne dass vorher ein Ask stand.
  *
  * Ein Ask beendet einen Lauf ohne Menschen (AskHandler.nonInteractive). Darum ist eine Wirkung „bewacht“, wenn auf
  * jedem Weg vom Anfang bis zu ihr ein Ask liegt; DryRun und Explain sehen beide Zweige jedes If, also auch hier.
  * Ein Agentenschritt zählt mit allen Befehlen mit Wirkung, die seine Hülle erlaubt: Was sein Fill aufruft, steht
  * erst zur Laufzeit fest. Ein Ask, das ein Fill stellen könnte, zählt nicht; es steht nicht fest.
  */
object Effects:
  /** Die Namen in der Konfiguration (programs[].effect). */
  val Names: List[String] = List("reversible", "irreversible", "costs")

  def parse(name: String): Option[Effect] = name match
    case "reversible"   => Some(Effect.Reversible)
    case "irreversible" => Some(Effect.Irreversible)
    case "costs"        => Some(Effect.Costs)
    case _              => None

  /** Eine Stelle mit Wirkung: was dort läuft oder laufen darf, und welche Wirkung. */
  final case class Site(what: String, effect: Effect)

  /** Die Wirkung eines Aufrufs: aus der Policy mit dem längsten passenden Präfix, wie bei der Prüfung. */
  def of(policies: List[ArgPolicy], cmd: String, args: List[String]): Effect =
    ArgPolicy.find(policies, cmd, args).fold(Effect.Reversible)(_.effect)

  /** Die Policies mit Wirkung, die ein Eintrag der shellAllowlist erreicht: alle mit einem Präfix darunter, und die
    * längste darüber, wenn keine Policy genau den Eintrag trägt (ArgPolicy.find nimmt das längste passende Präfix).
    */
  def reachable(policies: List[ArgPolicy], c: AllowedCommand): List[ArgPolicy] =
    val same = policies.filter(_.binary == c.binary)
    val below = same.filter(_.prefixArgs.startsWith(c.requiredPrefixArgs))
    val above =
      if below.exists(_.prefixArgs == c.requiredPrefixArgs) then Nil
      else same.filter(p => c.requiredPrefixArgs.startsWith(p.prefixArgs)).maxByOption(_.prefixArgs.size).toList
    (below ++ above).filter(_.effect.acts)

  private def show(p: ArgPolicy): String = (p.binary :: p.prefixArgs).mkString(" ")

  private def allowed(config: CheckConfig, p: ArgPolicy): Boolean = config.unattended.exists(_.allows(p.binary, p.prefixArgs))

  /** Alle Befehle mit Wirkung im Term, feste Schritte, Abnahmen und Hüllen der Agentenschritte, je einmal. */
  def all(term: Term, config: CheckConfig): List[Site] =
    def go(t: Term): List[Site] = t match
      case Term.Shell(cmd, args, _) => shell(cmd, args, config).toList
      case Term.Seq(steps, _)       => steps.flatMap(go)
      case Term.If(_, a, b, _)      => go(a) ++ go(b)
      case Term.RepeatUntil(b, _, _, _) => go(b)
      case Term.Hole(_, env, _, verify) =>
        hull(env, config) ++ verify.toList.flatMap(v => shell(v.cmd, v.args, config))
      case _ => Nil
    go(term).distinct

  private def shell(cmd: String, args: List[String], config: CheckConfig): Option[Site] =
    ArgPolicy.find(config.argPolicies, cmd, args).filter(_.effect.acts).map(p => Site(show(p), p.effect))

  /** Was eine Hülle an Wirkung erlaubt, je Policy. */
  def hull(env: Envelope, config: CheckConfig): List[Site] =
    env.shellAllowlist.toList.flatMap(reachable(config.argPolicies, _)).distinct.map(p => Site(show(p), p.effect))

  /** Wirkungen, die ein Lauf ohne Menschen erreichen kann, ohne vorher ein Ask zu passieren, und die die
    * Konfiguration nicht unter unattended erlaubt. Leer heißt: --no-ask darf starten.
    */
  def unguarded(term: Term, config: CheckConfig): List[Site] =
    def walk(t: Term, asked: Boolean): (List[Site], Boolean) = t match
      case Term.Shell(cmd, args, _) =>
        val open = ArgPolicy.find(config.argPolicies, cmd, args).filter(p => p.effect.acts && !asked && !allowed(config, p))
        (open.map(p => Site(show(p), p.effect)).toList, asked)
      case Term.Ask(_, _) => (Nil, true)
      case Term.Seq(steps, _) =>
        steps.foldLeft((List.empty[Site], asked)) { case ((acc, a), s) =>
          val (sites, after) = walk(s, a)
          (acc ++ sites, after)
        }
      // gefragt ist danach nur, wenn beide Zweige fragen
      case Term.If(_, a, b, _) =>
        val (sa, aa) = walk(a, asked)
        val (sb, ab) = walk(b, asked)
        (sa ++ sb, aa && ab)
      // der Rumpf läuft mindestens einmal (max ≥ 1), beim ersten Mal mit dem Stand davor
      case Term.RepeatUntil(body, _, _, _) => walk(body, asked)
      case Term.Hole(goal, env, origin, verify) =>
        val inHull =
          if asked then Nil
          else env.shellAllowlist.toList.flatMap(reachable(config.argPolicies, _)).distinct.filterNot(allowed(config, _))
            .map(p => Site(s"${show(p)} (Agentenschritt „${Text.visible(goal.take(40))}${if goal.length > 40 then "…" else ""}“)", p.effect))
        val check = verify.toList.flatMap(v => walk(Term.Shell(v.cmd, v.args, origin), asked)._1)
        (inHull ++ check, asked)
      case _ => (Nil, asked)
    walk(term, false)._1.distinct
