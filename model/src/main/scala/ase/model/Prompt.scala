package ase.model

import ase.core.*

/** Plan: Aufgabe → Term, darf Löcher enthalten. errors: Handlungsanweisungen der abgelehnten Versuche davor. */
final case class PlanRequest(callId: String, attempt: Int, task: String, root: Envelope, nestingLimit: Int, errors: Vector[String])

/** Prompts sind Werkzeugbeschreibungen (SPEC MODELLADAPTER): Begriffe so, wie das Modell sie kennt. Der System-Prompt
  * hängt nur von der Prüfkonfiguration ab und bleibt über alle Aufrufe eines Laufs gleich (Prompt-Cache).
  */
object Prompt:
  val toolName = "submit_term"

  val toolDescription: String =
    "Reicht den Term ein, den die Engine prüft und danach ausführt. Eingabe: {\"term\": <Term>}. " +
      "Rufe dieses Werkzeug genau einmal auf; Text außerhalb des Aufrufs wird nicht ausgewertet."

  def system(check: CheckConfig): String =
    s"""Du schreibst Programme für eine Engine, die jedes Programm vor der Ausführung prüft. Ein Programm ist ein Term
       |in einer kleinen, statischen Sprache. Du lieferst ihn ausschließlich über das Werkzeug $toolName.
       |
       |# Operationen
       |- ReadFile(path): liest eine Datei. ListDir(path): listet ein Verzeichnis.
       |- WriteFile(path, content): legt eine neue Datei an; überschreibt nie.
       |- EditFile(path, expectedOld, replacement): ersetzt expectedOld durch replacement. expectedOld muss genau
       |  einmal in der Datei vorkommen, Zeichen für Zeichen (Compare-and-Swap); sonst scheitert der Schritt.
       |- MoveFile(from, to): verschiebt eine normale Datei; to darf nicht existieren, sein Verzeichnis muss existieren.
       |- Shell(cmd, args): startet ein erlaubtes Programm. Es gibt keine Shell: args ist eine Liste, keine Pipes,
       |  Umleitungen, Platzhalter oder Anführungszeichen.
       |- Ask(question): fragt den Menschen. Kommt keine Antwort, endet der Lauf.
       |- Done(summary): beendet den Term. In einem Loch beendet Done nur das Loch, summary ist dessen Ergebnis.
       |- Seq(steps), If(pred, thenT, elseT), RepeatUntil(body, until, max): Ablauf.
       |- Hole(goal, envelope): ein Loch. Hier entscheidest du später, zur Laufzeit, mit den Daten, die bis dahin
       |  vorliegen; die Hülle (envelope) legt fest, was dort höchstens geschehen darf.
       |
       |# Prädikate
       |FileExists(path), LastExitCode(code), LastOutputContains(text), LastOutputEmpty, Not(p), And(p, q).
       |"Last" ist das Ergebnis des unmittelbar vorangehenden Schritts. Ergebnis von Seq, If, RepeatUntil: das ihres
       |zuletzt ausgeführten Schritts. Ergebnis eines Lochs: der Done-Text als Ausgabe, ohne Exit-Code. Nur Shell
       |liefert einen Exit-Code. Ein Prädikat, das nie wahr werden kann (LastExitCode nach ReadFile), wird abgelehnt.
       |
       |# Der Term ist statisch
       |Keine Variablen, keine Verweise auf Ergebnisse. Während du einen Term schreibst, siehst du die Ergebnisse
       |seiner Schritte nicht. Brauchst du Inhalte, um zu entscheiden, setze die Schritte, die sie liefern, vor ein
       |Loch: Beim Füllen sieht ein Loch die Ergebnisse der vorangegangenen Schritte seiner Seq und aller
       |umschließenden Seqs, aber nur, wenn deren Pfade unter seinem pathPrefix liegen. Ein Shell-Aufruf ohne
       |Pfadargument zählt dabei als "."; immer sichtbar sind Exit-Codes, Done-Texte früherer Löcher, Ausgaben von
       |Programmen, die Code aus dem Arbeitsverzeichnis ausführen, und ${show(check.pathless)}.
       |
       |# Hüllen
       |Envelope(allowedOps, pathPrefix, maxSteps, maxDepth, shellAllowlist). allowedOps aus ReadFile, ListDir,
       |WriteFile, EditFile, MoveFile, Shell, Ask, Hole; Seq, If, RepeatUntil und Done sind immer erlaubt. Hole
       |erlaubt Unterlöcher, Ask erlaubt Fragen. Die Hülle eines Lochs ist nie weiter als die umschließende:
       |- allowedOps Teilmenge; pathPrefix gleich oder darunter; jeder Befehl gleich oder enger ("git status" unter "git").
       |- maxDepth strikt kleiner als das der umschließenden Hülle.
       |- Schritte im schlechtesten Fall höchstens maxSteps der umschließenden Hülle. Zählung: jede Operation 1,
       |  Seq Summe, If Maximum der Zweige, RepeatUntil max mal Rumpf, Loch 1 plus sein maxSteps.
       |- maxSteps mindestens 1, allowedOps nicht leer, Shell in allowedOps genau dann, wenn shellAllowlist nicht leer.
       |Gib jedem Loch genau die Rechte, die sein Ziel braucht, nicht mehr.
       |
       |# Pfade
       |Relativ zum Arbeitsverzeichnis, "/" als Trenner, kein "..", kein ".git", nicht absolut. pathPrefix "." ist das
       |ganze Arbeitsverzeichnis, "src/" ein Verzeichnis, "REPORT.md" genau eine Datei.
       |
       |# Programme
       |Nur Programme aus der shellAllowlist der Hülle, jeweils mit diesen Argumenten:
       |${check.argPolicies.map(policy).mkString("\n")}
       |- ./skript (ein Skript im Arbeitsverzeichnis): ohne Argumente.
       |Ohne Pfadargument berührt ein Programm das ganze Arbeitsverzeichnis "."; das ist nur unter pathPrefix "." erlaubt.
       |Kombinierte Kurz-Flags (-rn) gehen, wenn jedes einzeln erlaubt ist.
       |
       |# Ablehnungen
       |Verstößt ein Term gegen die Regeln, wird er nicht ausgeführt; du bekommst die Gründe und lieferst einen neuen.
       |Höchstens ${check.nestingLimit} Ebenen Seq, If und RepeatUntil ineinander.""".stripMargin

  private def show(cs: List[AllowedCommand]): String =
    if cs.isEmpty then "sonst nichts" else cs.map(c => s"„${c.show}“").mkString(", ")

  private def policy(p: ArgPolicy): String =
    val name = (p.binary :: p.prefixArgs).mkString(" ")
    val flags = (p.flags.toList ++ p.valuedFlags.toList.map(_ + " <n>") ++ p.prefixFlags.toList.map(_ + "…")).sorted
    val positional = p.positional match
      case Positional.NoArgs                         => "keine weiteren Argumente"
      case Positional.Paths                          => "dann Pfade"
      case Positional.PatternThenPaths               => "dann ein Muster, dann Pfade"
      case Positional.PathsAfterDashDash             => "dann \"--\" und Pfade"
      case Positional.RevisionThenPathsAfterDashDash => "dann ein Revisionsbereich, dann \"--\" und Pfade"
    s"- $name: Flags ${if flags.isEmpty then "keine" else flags.mkString(" ")}; $positional."

  private def envelopeJson(e: Envelope): String = Codec.envelope(e).spaces2

  private def errors(es: Vector[String]): String =
    if es.isEmpty then ""
    else "\n\n# Frühere Versuche, abgelehnt oder gescheitert\n" + es.zipWithIndex.map((e, i) => s"Versuch ${i + 1}:\n$e").mkString("\n\n")

  def plan(r: PlanRequest): String =
    s"""# Aufgabe
       |${r.task.trim}
       |
       |# Wurzel-Hülle (gilt für den ganzen Term)
       |${envelopeJson(r.root)}
       |
       |Schreibe einen Term, der die Aufgabe löst. Setze feste Schritte, wo du sie schon kennst, und Löcher, wo erst
       |Daten entscheiden. Ein Mensch gibt den Term einmal frei; danach läuft er ohne Rückfrage. Liegt die Aufgabe
       |außerhalb der Wurzel-Hülle, ändere nichts an ihr vorbei, sondern frage mit Ask.${errors(r.errors)}
       |
       |Rufe $toolName mit dem Term auf.""".stripMargin

  def fill(r: FillRequest): String =
    val scope =
      if r.scope.isEmpty then "(keine)"
      else r.scope.map(l => s"## Schritt ${l.at}: ${l.label}\n${l.text}").mkString("\n\n")
    s"""# Loch ${r.at}
       |Ziel: ${r.goal}
       |
       |# Hülle dieses Lochs
       |${envelopeJson(r.envelope)}
       |Verbleibende Schritte: ${r.remainingSteps}
       |
       |# Ergebnisse früherer Schritte, die dieses Loch sieht
       |$scope${errors(r.errors)}
       |
       |Liefere den Teilterm für dieses Loch, innerhalb seiner Hülle. Beende ihn mit Done; die Zusammenfassung ist das
       |Ergebnis des Lochs, das spätere Schritte und der Mensch sehen. Liegt etwas Nötiges außerhalb der Hülle, ändere
       |es nicht, sondern nenne es in der Zusammenfassung.
       |
       |Rufe $toolName mit dem Teilterm auf.""".stripMargin
