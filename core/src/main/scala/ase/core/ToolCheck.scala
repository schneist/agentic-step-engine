package ase.core

import cats.syntax.all.*

/** Syntaktische Prüfung eines Werkzeugaufrufs gegen die Hülle, ohne Dateisystem: erlaubte Operation, Pfade nach
  * RelPath und unter dem pathPrefix, Shell nach Allowlist und Argument-Policy. Eine reine Funktion für LocalTools
  * (vor jedem Syscall), Replay (jeder aufgezeichnete Aufruf) und die Werkzeuge im Speicher der Tests.
  * Scheitert sie, antwortet die Werkzeugschicht mit Reply(Nil, Left(fehler), "").
  */
object ToolCheck:
  /** Ein geprüfter Shell-Aufruf: berührte Pfade (ohne Pfadargument "." nach D#27), normalisiert; Argumente mit
    * normalisierten Pfaden, so wie das Programm sie bekommt.
    */
  final case class ShellCall(touched: List[String], args: List[String])

  def prefix(env: Envelope): Either[ToolError, Vector[String]] =
    RelPath.parse(env.pathPrefix).leftMap(ToolError.InvalidPath(env.pathPrefix, _))

  /** Ein Pfad unter dem pathPrefix der Hülle, als Segmente. */
  def path(raw: String, env: Envelope): Either[ToolError, Vector[String]] =
    for
      p <- prefix(env)
      segs <- RelPath.parse(raw).leftMap(ToolError.InvalidPath(raw, _))
      _ <- Either.cond(segs.startsWith(p), (), ToolError.OutsidePrefix(raw, env.pathPrefix))
    yield segs

  /** Ein Pfad, der angelegt oder verschoben wird: braucht einen Dateinamen. */
  def entry(raw: String, env: Envelope): Either[ToolError, Vector[String]] =
    path(raw, env).flatMap(segs => Either.cond(segs.nonEmpty, segs, ToolError.InvalidPath(raw, "kein Dateiname")))

  def shell(cmd: String, args: List[String], env: Envelope, policies: List[ArgPolicy]): Either[ToolError, ShellCall] =
    for
      _ <- Either.cond(env.shellAllowlist.exists(_.allows(cmd, args)), (), ToolError.ShellNotAllowed(cmd, args))
      policy <- ArgPolicy.find(policies, cmd, args).toRight(ToolError.ShellNotAllowed(cmd, args))
      parsed = ArgPolicy.parse(policy, args)
      _ <- parsed.rejected.headOption.map((a, r) => ToolError.ShellArgRejected(cmd, a, r)).toLeft(())
      // jeder Pfad, ohne Pfad das Arbeitsverzeichnis (D#27)
      touched <- parsed.touched.traverse(path(_, env).map(RelPath.show))
      normalized <- parsed.pathAt.traverse(i => path(args(i), env).map(i -> RelPath.show(_)))
      _ <- program(cmd, env)
    yield
      val byIndex = normalized.toMap
      ShellCall(touched, args.zipWithIndex.map((a, i) => byIndex.getOrElse(i, a)))

  /** "./x" ist ein Pfad unter dem pathPrefix; sonst ein Name ohne Trenner, den LocalTools in PATH sucht. */
  private def program(cmd: String, env: Envelope): Either[ToolError, Unit] =
    if cmd.startsWith("./") then path(cmd.drop(2), env).void
    else if cmd.isEmpty || cmd.contains('/') || cmd.contains('\\') then Left(ToolError.ProgramNotFound(cmd))
    else Right(())

  /** Der ganze Aufruf: Operation in der Hülle, dann Pfade oder Shell. */
  def call(c: Call, env: Envelope, policies: List[ArgPolicy]): Either[ToolError, Unit] =
    c.op.filterNot(env.allowedOps).map(ToolError.OpNotAllowed(_)).toLeft(()) >>
      (c match
        case Call.ReadFile(p)       => path(p, env).void
        case Call.ListDir(p)        => path(p, env).void
        case Call.WriteFile(p, _)   => entry(p, env).void
        case Call.EditFile(p, _, _) => path(p, env).void
        case Call.MoveFile(f, t)    => entry(f, env) >> entry(t, env).void
        case Call.Shell(cmd, args)  => shell(cmd, args, env, policies).void
        case Call.FileExists(p)     => path(p, env).void
      )
