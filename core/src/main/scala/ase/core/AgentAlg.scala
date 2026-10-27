package ase.core

import cats.data.Tuple2K

/** Die Operationen der Engine, ohne Festlegung, was sie bedeuten. Tagless Final, ohne Constraints.
  * Kontrollknoten sind eigene Operationen (D#20): statische Interpreter sehen alle Zweige, nur Execute wählt.
  */
trait AgentAlg[F[_]]:
  def readFile(path: String, origin: Origin): F[StepResult]
  def listDir(path: String, origin: Origin): F[StepResult]
  def writeFile(path: String, content: String, origin: Origin): F[StepResult]
  def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): F[StepResult]
  def moveFile(from: String, to: String, origin: Origin): F[StepResult]
  def shell(cmd: String, args: List[String], origin: Origin): F[StepResult]
  def ask(question: String, origin: Origin): F[StepResult]
  def done(summary: String, origin: Origin): F[StepResult]

  /** Im ADT: Hole. In Execute: Fill, DryRun gegen envelope, rekursiv ausführen. */
  def think(goal: String, envelope: Envelope, origin: Origin): F[StepResult]

  def seq(steps: List[F[StepResult]], origin: Origin): F[StepResult]
  def branch(pred: Pred, thenP: F[StepResult], elseP: F[StepResult], origin: Origin): F[StepResult]
  def repeatUntil(body: F[StepResult], until: Pred, max: Int, origin: Origin): F[StepResult]

object AgentAlg:
  /** Ein Programm, unabhängig von seiner Deutung. */
  type Program = [F[_]] => AgentAlg[F] => F[StepResult]

  /** Faltet einen Term in jede Algebra-Instanz. */
  def fromTerm[F[_]](t: Term)(alg: AgentAlg[F]): F[StepResult] = t match
    case Term.ReadFile(p, o)          => alg.readFile(p, o)
    case Term.ListDir(p, o)           => alg.listDir(p, o)
    case Term.WriteFile(p, c, o)      => alg.writeFile(p, c, o)
    case Term.EditFile(p, old, r, o)  => alg.editFile(p, old, r, o)
    case Term.MoveFile(f, to, o)      => alg.moveFile(f, to, o)
    case Term.Shell(c, args, o)       => alg.shell(c, args, o)
    case Term.Ask(q, o)               => alg.ask(q, o)
    case Term.Done(s, o)              => alg.done(s, o)
    case Term.Hole(g, e, o)           => alg.think(g, e, o)
    case Term.Seq(steps, o)           => alg.seq(steps.map(fromTerm(_)(alg)), o)
    case Term.If(p, a, b, o)          => alg.branch(p, fromTerm(a)(alg), fromTerm(b)(alg), o)
    case Term.RepeatUntil(b, u, m, o) => alg.repeatUntil(fromTerm(b)(alg), u, m, o)

  def program(t: Term): Program = [F[_]] => (alg: AgentAlg[F]) => fromTerm(t)(alg)

  type TermWriter[A] = Term

  /** ToTerm: die Deutung, die den Term zurückschreibt. Gesetz: toTerm(program(t)) == t. */
  val termWriter: AgentAlg[TermWriter] = new AgentAlg[TermWriter]:
    def readFile(path: String, origin: Origin): Term = Term.ReadFile(path, origin)
    def listDir(path: String, origin: Origin): Term = Term.ListDir(path, origin)
    def writeFile(path: String, content: String, origin: Origin): Term = Term.WriteFile(path, content, origin)
    def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): Term =
      Term.EditFile(path, expectedOld, replacement, origin)
    def moveFile(from: String, to: String, origin: Origin): Term = Term.MoveFile(from, to, origin)
    def shell(cmd: String, args: List[String], origin: Origin): Term = Term.Shell(cmd, args, origin)
    def ask(question: String, origin: Origin): Term = Term.Ask(question, origin)
    def done(summary: String, origin: Origin): Term = Term.Done(summary, origin)
    def think(goal: String, envelope: Envelope, origin: Origin): Term = Term.Hole(goal, envelope, origin)
    def seq(steps: List[Term], origin: Origin): Term = Term.Seq(steps, origin)
    def branch(pred: Pred, thenP: Term, elseP: Term, origin: Origin): Term = Term.If(pred, thenP, elseP, origin)
    def repeatUntil(body: Term, until: Pred, max: Int, origin: Origin): Term = Term.RepeatUntil(body, until, max, origin)

  def toTerm(p: Program): Term = p[TermWriter](termWriter)

  /** Zwei Deutungen in einem Lauf, handgeschrieben über Tuple2K. Gesetz: kommutativ in beiden Ergebnissen. */
  def product[F[_], G[_]](f: AgentAlg[F], g: AgentAlg[G]): AgentAlg[[A] =>> Tuple2K[F, G, A]] =
    new AgentAlg[[A] =>> Tuple2K[F, G, A]]:
      type P = Tuple2K[F, G, StepResult]
      def readFile(path: String, origin: Origin): P = Tuple2K(f.readFile(path, origin), g.readFile(path, origin))
      def listDir(path: String, origin: Origin): P = Tuple2K(f.listDir(path, origin), g.listDir(path, origin))
      def writeFile(path: String, content: String, origin: Origin): P =
        Tuple2K(f.writeFile(path, content, origin), g.writeFile(path, content, origin))
      def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): P =
        Tuple2K(f.editFile(path, expectedOld, replacement, origin), g.editFile(path, expectedOld, replacement, origin))
      def moveFile(from: String, to: String, origin: Origin): P = Tuple2K(f.moveFile(from, to, origin), g.moveFile(from, to, origin))
      def shell(cmd: String, args: List[String], origin: Origin): P = Tuple2K(f.shell(cmd, args, origin), g.shell(cmd, args, origin))
      def ask(question: String, origin: Origin): P = Tuple2K(f.ask(question, origin), g.ask(question, origin))
      def done(summary: String, origin: Origin): P = Tuple2K(f.done(summary, origin), g.done(summary, origin))
      def think(goal: String, envelope: Envelope, origin: Origin): P =
        Tuple2K(f.think(goal, envelope, origin), g.think(goal, envelope, origin))
      def seq(steps: List[P], origin: Origin): P = Tuple2K(f.seq(steps.map(_.first), origin), g.seq(steps.map(_.second), origin))
      def branch(pred: Pred, thenP: P, elseP: P, origin: Origin): P =
        Tuple2K(f.branch(pred, thenP.first, elseP.first, origin), g.branch(pred, thenP.second, elseP.second, origin))
      def repeatUntil(body: P, until: Pred, max: Int, origin: Origin): P =
        Tuple2K(f.repeatUntil(body.first, until, max, origin), g.repeatUntil(body.second, until, max, origin))

  /** Eingeschränkte Algebra (D#25): reicht nur Operationen weiter, deren OpKind die Hülle erlaubt; sonst `reject`.
    * Zweite Linie in Execute nach decoderFor und DryRun.
    */
  def restrict[F[_]](alg: AgentAlg[F], envelope: Envelope)(reject: (OpKind, Origin) => F[StepResult]): AgentAlg[F] =
    new AgentAlg[F]:
      private def guard(op: OpKind, origin: Origin)(run: => F[StepResult]): F[StepResult] =
        if envelope.allowedOps(op) then run else reject(op, origin)
      def readFile(path: String, origin: Origin): F[StepResult] = guard(OpKind.ReadFile, origin)(alg.readFile(path, origin))
      def listDir(path: String, origin: Origin): F[StepResult] = guard(OpKind.ListDir, origin)(alg.listDir(path, origin))
      def writeFile(path: String, content: String, origin: Origin): F[StepResult] =
        guard(OpKind.WriteFile, origin)(alg.writeFile(path, content, origin))
      def editFile(path: String, expectedOld: String, replacement: String, origin: Origin): F[StepResult] =
        guard(OpKind.EditFile, origin)(alg.editFile(path, expectedOld, replacement, origin))
      def moveFile(from: String, to: String, origin: Origin): F[StepResult] =
        guard(OpKind.MoveFile, origin)(alg.moveFile(from, to, origin))
      def shell(cmd: String, args: List[String], origin: Origin): F[StepResult] =
        guard(OpKind.Shell, origin)(alg.shell(cmd, args, origin))
      def ask(question: String, origin: Origin): F[StepResult] = guard(OpKind.Ask, origin)(alg.ask(question, origin))
      def done(summary: String, origin: Origin): F[StepResult] = alg.done(summary, origin)
      def think(goal: String, env: Envelope, origin: Origin): F[StepResult] =
        guard(OpKind.Hole, origin)(alg.think(goal, env, origin))
      def seq(steps: List[F[StepResult]], origin: Origin): F[StepResult] = alg.seq(steps, origin)
      def branch(pred: Pred, thenP: F[StepResult], elseP: F[StepResult], origin: Origin): F[StepResult] =
        alg.branch(pred, thenP, elseP, origin)
      def repeatUntil(body: F[StepResult], until: Pred, max: Int, origin: Origin): F[StepResult] =
        alg.repeatUntil(body, until, max, origin)
