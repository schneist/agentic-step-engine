package ase.runner

import ase.core.*

import java.nio.file.{Files, LinkOption, Path}
import scala.util.control.NonFatal

/** Vorprüfung der Skripte eines Terms gegen das Arbeitsverzeichnis (D#94, O#46): Jedes Skript `./…`, das der Term
  * statisch aufruft, muss dort als normale Datei liegen und ausführbar sein. DryRun kennt das Dateisystem nicht und
  * bleibt rein; diese Prüfung läuft am Rand, in `ase run` vor der Freigabe und in `ase plan`, wenn es ein
  * Arbeitsverzeichnis kennt. Zwischen Prüfung und Schritt kann sich die Datei ändern; der Fehler am Schritt bleibt,
  * die Prüfung ist ein früherer Hinweis, keine Garantie. Ob ein Befund den Lauf verhindert oder nur warnt, sagt
  * `scriptCheck` in der Runner-Konfiguration.
  */
object ScriptCheck:
  enum Mode:
    case Reject, Warn

  object Mode:
    def parse(s: String): Option[Mode] = s match
      case "reject" => Some(Reject)
      case "warn"   => Some(Warn)
      case _        => None

  /** Jedes Skript `./…` im Term, aus Shell-Schritten und Abnahmen (D#80), in beiden Zweigen jedes If wie Tor 1, in
    * der Reihenfolge des Terms und ohne Doppelte. Teilterme der Agentenschritte gibt es erst im Lauf; was sie aufrufen,
    * prüft weiter der Schritt.
    */
  def scripts(t: Term): List[String] =
    def go(t: Term): List[String] = t match
      case Term.Shell(cmd, _, _)            => List(cmd)
      case Term.Hole(_, _, _, verify)       => verify.map(_.cmd).toList
      case Term.Seq(steps, _)               => steps.flatMap(go)
      case Term.If(_, a, b, _)              => go(a) ++ go(b)
      case Term.RepeatUntil(body, _, _, _)  => go(body)
      case _                                => Nil
    go(t).filter(_.startsWith("./")).distinct

  /** Je Skript mit Befund eine Zeile „./pfad: Grund“; leer, wenn alle da und ausführbar sind. */
  def problems(workspace: Path, scripts: List[String]): List[String] =
    if scripts.isEmpty then Nil
    else
      // toRealPath nur auf Pfade, die es gibt (CLAUDE.md)
      val root =
        try Right(workspace.toRealPath())
        catch case NonFatal(e) => Left(s"Arbeitsverzeichnis $workspace nicht lesbar: ${e.getMessage}")
      root match
        case Left(reason) => List(reason)
        case Right(r)     => scripts.flatMap(s => problem(r, s).map(reason => s"$s: $reason"))

  private def problem(root: Path, script: String): Option[String] =
    val p = root.resolve(script.drop(2)).normalize
    if !p.startsWith(root) then Some("liegt nicht im Arbeitsverzeichnis")
    else if !Files.exists(p, LinkOption.NOFOLLOW_LINKS) then Some("fehlt im Arbeitsverzeichnis")
    else
      val real =
        try Some(p.toRealPath())
        catch case NonFatal(_) => None
      real match
        case None                                => Some("Symlink ins Leere")
        case Some(r) if !r.startsWith(root)      => Some("führt aus dem Arbeitsverzeichnis heraus (Symlink)")
        case Some(r) if !Files.isRegularFile(r)  => Some("ist keine normale Datei")
        case Some(r) if !Files.isExecutable(r) =>
          Some("ist nicht ausführbar (chmod +x; mit core.filemode=false auch git update-index --chmod=+x)")
        case _ => None

  /** Zeilen für plan und run im Format der übrigen Kopfzeilen. */
  def render(mode: Mode, problems: List[String]): String =
    val head = mode match
      case Mode.Reject => "skripte   abgelehnt (scriptCheck reject), es läuft nichts:"
      case Mode.Warn   => "skripte   Warnung (scriptCheck warn), diese Schritte werden scheitern:"
    (head :: problems.map("          " + _)).mkString("\n")
