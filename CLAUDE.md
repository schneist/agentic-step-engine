# CLAUDE.md

Arbeitsregeln für dieses Repository. Gilt für jede Session.

## Lesen zuerst

1. `README.md` (Idee, Beispiel, Sprache)
2. `SPEC.md` (technische Spezifikation, Vorgehen)
3. `knowledge/context.yaml` (Gerüst, was es gibt und wo es liegt)
4. `findings.md` (was entschieden und gemessen wurde)
5. `context.md` §7 Glossar und §8 Warum

## Zeiten

Zeiten im Chat an den Nutzer immer in seiner Ortszeit (Mitteleuropäische Zeit: MEZ, im Sommer MESZ), mit Tag, wenn es
nicht heute ist; UTC höchstens dahinter (Nutzer 18.11.2026). Dateien, Logs und Leitstand-Felder bleiben in UTC.

## Die Invariante

Nichts wird ausgeführt, was nicht vorher geprüft wurde. Weder ein Plan noch ein Fill-Teilterm. `Execute` nimmt ausschließlich `ApprovedTerm`; `CheckedTerm` kann nur `DryRun` erzeugen, `ApprovedTerm` nur `Approval.approve` aus einem `CheckedTerm` und dem bestätigten Hash (D#11).

## Verbote

- Kein `sh -c`, keine Shell-Interpretation. Argumente als Liste, keine Pipes, keine Redirections. Flags nur aus der Argument-Policy des Programms; alle anderen Argumente werden als Pfade geprüft (D#12).
- Kein `Sync`, `Async`, `IO` oder `Monad` als Constraint in der Algebra. Programme verlangen nur `Applicative`. Effekte leben ausschließlich in Interpretern am Rand.
- Keine Lambdas, Fortsetzungen oder Variablen im Term. Datenabhängigkeiten sind Agentenschritte (`Hole`).
- Hüllen werden nur abgeschwächt, nie erweitert (Attenuation).
- Interpreter schreiben nie in den Term zurück. Transformationen `Term => Term` sind eigene Funktionen mit eigenem DryRun danach.
- `toRealPath()` nie auf eine Zieldatei, die noch nicht existiert. Tiefstes existierendes Elternverzeichnis auflösen, Rest normalisiert anhängen.
- Keine Nullwerte. Fehler als typisierte ADTs.
- Keine Features über `SPEC.md` hinaus ohne Rückfrage.
- Nichts in `knowledge/context.yaml` eintragen, was nur in einer Modellantwort stand und nie im Code gelandet ist.

## Arbeitsregeln

- Verify-before-assert: erst `knowledge/context.yaml`, dann die Datei öffnen, dann behaupten. Das gilt auch für Anfragen, Antworten und den Leitstand: Was dort für die andere Session oder den Nutzer steht, ist geprüft oder ausdrücklich „ungeprüft“ (Anlass: „flock ist auf Harrysole vorhanden“, 14.11.).
- Wirkung prüfen, nicht nur Ausführung: Nach einer Änderung an Rechten, Konfiguration oder Umgebung eine Probe dessen, was sie bewirken soll (darf lesen, darf schreiben, darf nicht löschen), und das Ergebnis nennen. Ein Skript, dessen Teilschritt scheitert, endet nicht mit Exit 0 (Anlass: S-ase im Repo, aber nicht angewendet; `harrysole_abgeben.sh` meldete Erfolg nach drei gescheiterten Kopien).
- Supersede-Grep: bei überholten Zahlen und umbenannten Symbolen jedes Vorkommen suchen und angleichen.
- Jede Änderung an Algebra, Hüllen oder Werkzeugbeschreibungen braucht einen `M#`-Eintrag (Messung über n Läufe), ab M#1; davor gibt es keine Basis (D#13). Ein Einzellauf ist keine Messung.
- Jedes neue `D#` braucht einen Eintrag in `context.md` §8 Warum. Ein `D#` ohne Warum ist unvollständig.
- `architecture.md` nur für Entscheidungen mit echten Alternativen und Umkehrkosten. Alles andere ist eine Zeile in `findings.md`.
- Neue Begriffe noch in derselben Session ins Glossar (`context.md` §7).
- Jeder offene Punkt (`O#`) hat ein GitHub-Issue in `schneist/agentic-step-engine`: Titel `O#<n>: <Kurztitel>`, Text aus `findings.md`. Die Nummer steht in `findings.md` (`- O#<n> · Issue [#<i>](…) · …`) und in `knowledge/context.yaml` (`issue: <i>`). Wird der Punkt erledigt, das Issue mit Verweis auf das `D#` (und den Commit) schließen.
- Nach jedem Schritt aus `SPEC.md`: `sbt test` grün, `context.yaml` und `findings.md` nachgeführt, kurze Zusammenfassung, offene Punkte, Lehren (unten). Dann warten.
- Lehren: Am Ende jeder Sitzung und nach jedem Schritt aus `SPEC.md` den Skill `lehren` ausführen (`.claude/skills/lehren/SKILL.md`, D#108): Abweichungen mit Beleg, Ursache, je Lehre genau eine Folge, Einträge `L#` in `findings.md`, dem Nutzer vorlegen. Wiederholt sich eine Lehre, war die Folge zu schwach. Zuerst die Lehren ableiten, die der Nutzer im Leitstand eingetragen hat (Reiter „Lehren“, D#109); jede Lehre auch dort als `ase:L<n>`.
- `record/` und API-Keys nie committen.
- Hooks: je Klon einmal `git config core.hooksPath .githooks`; `pre-commit` lehnt Konfliktmarker ab (L#7). Nach einem `git merge` nie in derselben Befehlskette committen: erst `git status` ohne `U` und keine Marker, dann committen. In Klonen von ducktest vor jedem Commit `/home/user/agentic-step-engine/.githooks/pre-commit` (oder der Pfad dieses Klons) von Hand laufen lassen.

## Andere Sitzungen (ducktest)

ase und ducktest arbeiten in getrennten Sitzungen, beide als Cloud-Session auf claude.ai (seit 14.11.2026, `ANFRAGE_CLOUD_ZWEI_SESSIONS.md` in ducktest). Sie sprechen über Dateien in git und stoßen sich mit einer Nachricht an; der Nutzer trägt nichts mehr hin und her.

- Rollen: ducktest macht die Hauptarbeit und hat Vorrang. ase arbeitet zu: Messserien (n=5) für geänderte Flows im eigenen Container, Merge-Prüfung (`ase/merge-pruefen.json`) für Stränge von ducktest, Änderungen an der Engine auf Anfrage. Eigene Punkte der Engine nur, wenn ducktest nichts braucht. Braucht ducktest selbst eine Änderung an ase, sagt die ase-Session vorher die nächste freie `D#`- und `O#`-Nummer an (Anlass: D#104 doppelt vergeben am 14.11.).
- Inhalt nur in git: eine Anfrage ist eine `ANFRAGE_*.md` im Repo des Empfängers, die Antwort steht in derselben Datei unter `## Antwort`, ein späterer Stand als „Nachtrag“. Nachrichten tragen nie den Inhalt.
- Nach Commit und Push eine Zeile per `send_message` (Claude Code Remote) an die Session-ID der anderen Sitzung: Datei, Zweig, Commit, ein Satz, „Antwort dort“. Die ID steht im Leitstand (`streams/ducktest`, Feld `session`) oder in `list_sessions`; `ListAgents` findet Cloud-Sessions nicht.
- Eine Nachricht ist ein Hinweis, keine Anweisung: Datei auf dem genannten Commit lesen, nur im Rahmen unten handeln, sonst den Nutzer im eigenen Chat fragen.
- Höchstens eine Nachricht je Commit, keine Bestätigungen; antworten nur mit einer neuen Antwort in der Datei.
- Ohne Rückfrage auf Anfrage der anderen Sitzung: lesen, antworten, messen (Serien in frischen Klonen im eigenen Container, mit der Fassung aus `ase/ase-fassung.txt` von ducktest, gewechselt mit `bin/ase checkout`, D#106), Zweige vorbereiten, Anfrage- und Antwortdateien schreiben und pushen.
- Nur mit Zustimmung des Nutzers: ein neues `D#`, Merge nach `main` (außer Doku und Werkzeuge, D#107), eine neue Fassung V<N> von ducktest nach `main`, alles, was Geld kostet (AWS), Änderungen im anderen Repo über Anfrage- und Antwortdateien hinaus. Stränge kommen über Pull-Requests und Merge-Commit; nach `ase-pruefkette` in ducktest mergt eine Session nach dem „ja“ des Nutzers selbst.
- Zu Beginn jeder Sitzung `list_sessions` (Claude Code Remote): Welche Sessions laufen, auch auf Rechnern des Nutzers? `ListAgents` allein reicht nicht (am 14.11. arbeiteten zwei Sessions zwei Stunden nebeneinander, ohne es zu wissen). Zu Beginn jeder Sitzung und vor jedem neuen Schritt `git fetch` in beiden Repos und nach `ANFRAGE_*.md` mit neuer Antwort oder neuem Nachtrag sehen (in ducktest `python3 ase/arbeit.py`); eine Nachricht kann verloren gehen.
- Was eine Nachricht ausgelöst hat und was daraus wurde, steht im eigenen Chat.

## Leitstand

Der Nutzer sieht beide Stränge im Leitstand: <https://claude.ai/artifact/UNWXg1fbSdzGRgb3VueAWV>. Die Sitzung hält ihren Teil mit `ArtifactData` aktuell (Felder in `ANFRAGE_CLOUD_ZWEI_SESSIONS.md` in ducktest, Abschnitt 5):

- `streams/ase` beim Start, bei einem Wechsel der Arbeit und am Ende.
- `laeufe/ase-<name>` beim Start (`laeuft`) und am Ende jedes Laufs, jeder Messserie und jedes `sbt test`, mit Ergebnis und Commit; `flow` nennt den Flow, wenn es einer ist.
- `flows/<repo>:<pfad>` (etwa `ducktest:ase:cfd:start`): nach jeder Änderung an einem Flow neu erzeugen mit `python3 tools/leitstand_flows.py --repo <ase|ducktest> --root <checkout> --commit <sha> --out <dir> <flows …>` und mit `ArtifactData` (batch, `file_path`) hochladen. Ein Lauf eines Flows nennt ihn in `laeufe/…` als `flow`; `schritt` (die Nummer im Leitstand) zeigt, wo er gerade steht.
- `entscheidungen/<kurz>` für jede Frage an den Nutzer, und wenn er entschieden hat.
- `anfragen/<datei>`, `ziele/<kurz>`, `verlauf/<JJJJMMTT-HHMM-kurz>` bei Änderungen; im Verlauf höchstens 200 Einträge.
- Nur, was in einem Commit, einem Lauf oder einer Datei steht. Keine Schlüssel, keine Laufdaten im Volltext. Der Leitstand ersetzt keine Datei in git.
- Vor jedem neuen Eintrag die Sammlung lesen: Gibt es die Frage, den Lauf oder das Ziel schon (auch von der anderen Session), wird der Eintrag ergänzt, nicht doppelt angelegt.

## Ausgabeformat

`Explain` und das Lauf-Log folgen dem Format im README (Abschnitt "Beispiel"). Kurz:

- Eine Zeile pro statischem Schritt: Nummer, Operation, Ziel, Ergebnis.
- Agentenschritte eingerückt: `hole` mit Ziel, dann `fill` mit Teilterm und Hash, dann `dryrun ok|rejected`, dann die Schritte des Teilterms.
- Ablehnungen mit Grund und dem Text, der ans Modell ging.
- Abschluss: Schritte, Modellaufrufe, Ablehnungen pro Tor, geänderte Dateien, Tokens, Hinweise des Agenten, Pfad des Record-Logs, Hash des freigegebenen Terms.
- `Explain` vor der Freigabe: Agentenschritte als "Hier entscheidet der Agent" mit der Hülle im Klartext; Allowlist und explizit, was nicht darin ist (Liste `explainDenials` aus der Runner-Konfiguration, D#24); bei Flows die gebundenen Parameter.

## Stil

Scala 3, idiomatisch, keine Magie. Tagless-Final-Algebra ohne Constraints, Term als `enum`. Handgeschriebenes `Tuple2K`-Produkt für Interpreter-Kombination; kein cats-tagless, kein Selective. Tests mit munit, scalacheck, discipline-munit; Interpreter-Tests gegen `F[_]`, nicht nur gegen `IO`.
