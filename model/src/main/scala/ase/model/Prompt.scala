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
       |- ReadFile(path): liest eine Datei. ListDir(path): listet ein Verzeichnis samt allen Unterverzeichnissen,
       |  Ebene für Ebene; jeder Eintrag ist ein Pfad, wie ReadFile ihn nimmt, Verzeichnisse enden auf "/".
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
       |- Hole(goal, envelope, verify): ein Loch mit Abnahme. verify = {cmd, args, until, max}: ein erlaubtes Programm,
       |  das nach dem Loch läuft und prüft, ob es sein Ziel erreicht hat (etwa ./run-tests.sh mit until
       |  LastExitCode(0)). Ist until nicht erfüllt, füllst du das Loch erneut, höchstens max-mal, und erfährst, dass die
       |  Abnahme nicht bestanden ist. Setze verify nur, wo ein Programm das Ergebnis wirklich prüft. Es läuft unter der
       |  umgebenden Hülle. Der pathPrefix eines Lochs, das ändern darf, berührt den Code dieses Programms nicht, weder
       |  darüber noch darunter: Wer seine Prüfung ändern kann, besteht sie ohne Lösung.
       |
       |# Prädikate
       |FileExists(path), LastExitCode(code), LastOutputContains(text), LastOutputEmpty, Not(p), And(p, q).
       |"Last" ist das Ergebnis des unmittelbar vorangehenden Schritts. Ergebnis von Seq, If, RepeatUntil: das ihres
       |zuletzt ausgeführten Schritts. Ergebnis eines Lochs: der Done-Text als Ausgabe, ohne Exit-Code; mit verify das Ergebnis des Programms. Nur Shell
       |liefert einen Exit-Code. Ein Prädikat, das nie wahr werden kann (LastExitCode nach ReadFile), wird abgelehnt.
       |
       |# Der Term ist statisch
       |Keine Variablen, keine Verweise auf Ergebnisse. Während du einen Term schreibst, Plan oder Teilterm eines
       |Lochs, siehst du die Ergebnisse seiner eigenen Schritte nicht. Lesen oder Suchen (ReadFile, ListDir, grep) und
       |das Verarbeiten des Gelesenen (WriteFile, EditFile, Done) stehen deshalb nie im selben Term. Im Plan liegt
       |dazwischen ein Loch. Im Teilterm eines Lochs beendest du nach dem Lesen ohne Done; dann füllst du dasselbe Loch
       |in einer weiteren Runde und siehst die Ergebnisse, solange Runden und Schritte des Lochs reichen.
       |Beim Füllen sieht ein Loch die Ergebnisse der vorangegangenen Schritte seiner Seq und aller umschließenden
       |Seqs, aber nur, wenn deren Pfade unter seinem pathPrefix liegen. Ein Shell-Aufruf ohne Pfadargument zählt
       |dabei als "."; immer sichtbar sind Exit-Codes, Done-Texte früherer Löcher, Ausgaben von Programmen, die Code
       |aus dem Arbeitsverzeichnis ausführen, und ${show(check.pathless)}.
       |
       |# Hüllen
       |Envelope(allowedOps, pathPrefix, maxSteps, maxDepth, shellAllowlist). allowedOps aus ReadFile, ListDir,
       |WriteFile, EditFile, MoveFile, Shell, Ask, Hole; Seq, If, RepeatUntil und Done sind immer erlaubt. Hole
       |erlaubt Unterlöcher, Ask erlaubt Fragen. Die Hülle eines Lochs ist nie weiter als die umschließende:
       |- allowedOps Teilmenge; pathPrefix gleich oder darunter; jeder Befehl gleich oder enger ("git status" unter "git").
       |- maxDepth strikt kleiner als das der umschließenden Hülle.
       |- Schritte im schlechtesten Fall höchstens maxSteps der umschließenden Hülle. Zählung: jede Operation 1,
       |  Seq Summe, If Maximum der Zweige, RepeatUntil max mal Rumpf, Loch 1 plus sein maxSteps,
       |  mit verify max mal (2 plus maxSteps).
       |- maxSteps mindestens 1, allowedOps nicht leer, Shell in allowedOps genau dann, wenn shellAllowlist nicht leer.
       |Gib jedem Loch genau die Rechte, die sein Ziel braucht, nicht mehr.
       |Lesen und Ändern teilen sich den pathPrefix. Ein Loch, das ändern darf (WriteFile, EditFile, MoveFile), bekommt
       |keinen pathPrefix, unter dem ein Skript liegt, das der Term ausführt (etwa ./run-tests.sh), es sei denn, die
       |Aufgabe verlangt genau diese Änderung: sonst könnte das Loch ändern, was danach läuft. Nur wenn ein solches
       |Skript unter dem pathPrefix liegt, den das Loch zum Lesen braucht, teile es in zwei: ein Loch liest unter dem
       |weiten pathPrefix, ändert nichts und nennt in Done, was zu ändern ist; ein späteres Loch ändert unter dem
       |engsten pathPrefix, der die Änderung zulässt. Führt der Term keinen Code aus, genügt ein Loch, das liest und
       |ändert.
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

  private def verified(vs: Vector[String]): String =
    if vs.isEmpty then "" else "\n\n# Abnahme nach dem Durchlauf davor\n" + vs.mkString("\n")

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
       |Daten entscheiden. Lese- und Suchschritte, die du schon kennst, stehen vor dem Loch, das ihr Ergebnis
       |verarbeitet. Lege eine Datei, die ein Loch schreiben soll, nicht vorher leer an: WriteFile überschreibt nie.
       |Ein Mensch gibt den Term einmal frei; danach läuft er ohne Rückfrage. Liegt die Aufgabe
       |außerhalb der Wurzel-Hülle, ändere nichts an ihr vorbei, sondern frage mit Ask.${errors(r.errors)}
       |
       |Rufe $toolName mit dem Term auf.""".stripMargin

  def fill(r: FillRequest): String =
    val scope =
      if r.scope.isEmpty then "(keine)"
      else r.scope.map(l => s"## Schritt ${l.at}: ${l.label}\n${l.text}").mkString("\n\n")
    val last = r.round >= r.maxRounds
    val rounds =
      if last then "Dies ist die letzte Runde: Fehlt oben etwas Nötiges, schreibe nichts blind, sondern nenne es in Done."
      else
        "Fehlt oben etwas Nötiges, lies es und beende den Teilterm ohne Done: Dann folgt eine weitere Runde, die die\n" +
          "Ergebnisse sieht."
    s"""# Loch ${r.at}, Runde ${r.round} von ${r.maxRounds}
       |Ziel: ${r.goal}
       |
       |# Hülle dieses Lochs
       |${envelopeJson(r.envelope)}
       |Verbleibende Schritte (für alle Runden zusammen): ${r.remainingSteps}
       |
       |# Ergebnisse früherer Schritte, die dieses Loch sieht
       |$scope${verified(r.verified)}${errors(r.errors)}
       |
       |Liefere den Teilterm für dieses Loch, innerhalb seiner Hülle. Was du schreibst, muss aus den Ergebnissen oben
       |stammen; was deine eigenen Schritte lesen, siehst du erst in der nächsten Runde. $rounds
       |Mit Done endet das Loch; die Zusammenfassung ist das
       |Ergebnis des Lochs, das spätere Schritte und der Mensch sehen. Liegt etwas Nötiges außerhalb der Hülle, ändere
       |es nicht, sondern nenne es in der Zusammenfassung.
       |
       |Rufe $toolName mit dem Teilterm auf.""".stripMargin
