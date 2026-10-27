# CLAUDE.md

Arbeitsregeln für dieses Repository. Gilt für jede Session.

## Lesen zuerst

1. `README.md` (Idee, Beispiel, Sprache)
2. `SPEC.md` (technische Spezifikation, Vorgehen)
3. `knowledge/context.yaml` (Gerüst, was es gibt und wo es liegt)
4. `findings.md` (was entschieden und gemessen wurde)
5. `context.md` §7 Glossar und §8 Warum

## Die Invariante

Nichts wird ausgeführt, was nicht vorher geprüft wurde. Weder ein Plan noch ein Fill-Teilterm. `Execute` nimmt ausschließlich `ApprovedTerm`; `CheckedTerm` kann nur `DryRun` erzeugen, `ApprovedTerm` nur `Approval.approve` aus einem `CheckedTerm` und dem bestätigten Hash (D#11).

## Verbote

- Kein `sh -c`, keine Shell-Interpretation. Argumente als Liste, keine Pipes, keine Redirections. Flags nur aus der Argument-Policy des Programms; alle anderen Argumente werden als Pfade geprüft (D#12).
- Kein `Sync`, `Async`, `IO` oder `Monad` als Constraint in der Algebra. Programme verlangen nur `Applicative`. Effekte leben ausschließlich in Interpretern am Rand.
- Keine Lambdas, Fortsetzungen oder Variablen im Term. Datenabhängigkeiten sind Löcher.
- Hüllen werden nur abgeschwächt, nie erweitert (Attenuation).
- Interpreter schreiben nie in den Term zurück. Transformationen `Term => Term` sind eigene Funktionen mit eigenem DryRun danach.
- `toRealPath()` nie auf eine Zieldatei, die noch nicht existiert. Tiefstes existierendes Elternverzeichnis auflösen, Rest normalisiert anhängen.
- Keine Nullwerte. Fehler als typisierte ADTs.
- Keine Features über `SPEC.md` hinaus ohne Rückfrage.
- Nichts in `knowledge/context.yaml` eintragen, was nur in einer Modellantwort stand und nie im Code gelandet ist.

## Arbeitsregeln

- Verify-before-assert: erst `knowledge/context.yaml`, dann die Datei öffnen, dann behaupten.
- Supersede-Grep: bei überholten Zahlen und umbenannten Symbolen jedes Vorkommen suchen und angleichen.
- Jede Änderung an Algebra, Hüllen oder Werkzeugbeschreibungen braucht einen `M#`-Eintrag (Messung über n Läufe), ab M#1; davor gibt es keine Basis (D#13). Ein Einzellauf ist keine Messung.
- Jedes neue `D#` braucht einen Eintrag in `context.md` §8 Warum. Ein `D#` ohne Warum ist unvollständig.
- `architecture.md` nur für Entscheidungen mit echten Alternativen und Umkehrkosten. Alles andere ist eine Zeile in `findings.md`.
- Neue Begriffe noch in derselben Session ins Glossar (`context.md` §7).
- Nach jedem Schritt aus `SPEC.md`: `sbt test` grün, `context.yaml` und `findings.md` nachgeführt, kurze Zusammenfassung, offene Punkte. Dann warten.
- `record/` und API-Keys nie committen.

## Ausgabeformat

`Explain` und das Lauf-Log folgen dem Format im README (Abschnitt "Beispiel"). Kurz:

- Eine Zeile pro statischem Schritt: Nummer, Operation, Ziel, Ergebnis.
- Löcher eingerückt: `hole` mit Ziel, dann `fill` mit Teilterm und Hash, dann `dryrun ok|rejected`, dann die Schritte des Teilterms.
- Ablehnungen mit Grund und dem Text, der ans Modell ging.
- Abschluss: Schritte, Modellaufrufe, Ablehnungen pro Tor, geänderte Dateien, Tokens, Hinweise des Agenten, Pfad des Record-Logs, Hash des freigegebenen Terms.
- `Explain` vor der Freigabe: Löcher als "Hier entscheidet der Agent" mit der Hülle im Klartext; Allowlist und explizit, was nicht darin ist (Liste `explainDenials` aus der Runner-Konfiguration, D#24); bei Flows die gebundenen Parameter.

## Stil

Scala 3, idiomatisch, keine Magie. Tagless-Final-Algebra ohne Constraints, Term als `enum`. Handgeschriebenes `Tuple2K`-Produkt für Interpreter-Kombination; kein cats-tagless, kein Selective. Tests mit munit, scalacheck, discipline-munit; Interpreter-Tests gegen `F[_]`, nicht nur gegen `IO`.
