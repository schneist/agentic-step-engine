# SPEC.md

Technische Spezifikation. Das Warum steht in context.md §8, Entscheidungen in findings.md, Arbeitsregeln in CLAUDE.md.

IDEE
Ein LLM erzeugt Agentenprogramme als Terme einer Algebra. Terme dürfen Löcher enthalten: Stellen, an denen das Modell erst zur Laufzeit entscheidet, wenn Daten vorliegen. Jedes Loch trägt eine Hülle (Envelope), die festlegt, was dort maximal passieren darf. Die Engine prüft den bekannten Teil vorab (DryRun) und jeden zur Laufzeit gelieferten Teilterm gegen die Hülle seines Lochs, bevor sie ihn ausführt. Nichts wird ausgeführt, was nicht vorher geprüft wurde.

=====================================================================
MODULE
=====================================================================
- core: Algebra, Term-ADT, Interpreter. Keine HTTP- oder LLM-Abhängigkeit.
- model: Adapter zur Anthropic Messages API (Tool Use).
- runner: CLI.

=====================================================================
TERM-ADT (core): die einzige Sprache zwischen Modell und Engine
=====================================================================
enum Term:
  ReadFile(path), ListDir(path),
  WriteFile(path, content)                      -- nur für neue Dateien
  EditFile(path, expectedOld, replacement)      -- CAS: expectedOld muss genau einmal vorkommen, sonst Abbruch mit Handlungsanweisung
  MoveFile(from, to)                            -- D#18: from normale Datei, to existiert nicht, Elternverzeichnis von to existiert
  Shell(cmd, args), Ask(question), Done(summary)
  Seq(steps: List[Term])
  If(pred: Pred, thenT: Term, elseT: Term)
  RepeatUntil(body: Term, until: Pred, max: Int)
  Hole(goal: String, envelope: Envelope)

enum Pred (statisch auswertbar, ohne Modell): FileExists(path), LastExitCode(code), LastOutputContains(text), LastOutputEmpty, Not(p), And(p, q)   -- LastOutputEmpty: D#13

enum OpKind: ReadFile, ListDir, WriteFile, EditFile, MoveFile, Shell, Ask, Hole   -- D#8, D#18; Seq/If/RepeatUntil/Done sind immer erlaubt

Bedeutung von "Last" (D#20): StepResult hat optional exitCode und optional output. Ein Pred liest das Ergebnis des unmittelbar vorangehenden Schritts. Ergebnis von Seq/If/RepeatUntil/Hole = Ergebnis ihres zuletzt ausgeführten Schritts; leeres Seq reicht das vorherige Ergebnis durch; Ergebnis eines Hole = Done-Zusammenfassung als output, ohne exitCode. Fehlt das abgefragte Feld, ist das Pred falsch.

Regeln:
- Keine Fortsetzungen, keine Lambdas, keine Variablen. Jede Verarbeitung, die den Inhalt eines Ergebnisses inspizieren muss, ist ein Hole.
- Jeder Term-Knoten trägt ein Feld origin: Human | Model(callId); callId benennt den Modellaufruf (plan-n, fill-n) (D#19). Terme können auch aus Dateien (flows/) geladen statt vom Modell erzeugt werden; DryRun und Execute behandeln beide gleich. Eine Autoren-Oberfläche für Menschen ist v2, nicht v1.
- Flow-Datei (D#22): case class Flow(description: String, params: Map[String, Regex], envelope: Envelope, term: Term). Laden: decodieren → jeden Parameterwert gegen seine Regex prüfen → ${name} nur in String-Blättern des Terms ersetzen (unbekannter oder ungebundener Name = Fehler) → flow.envelope muss die Wurzel-Hülle des Runners abschwächen → DryRun gegen flow.envelope. Der Hash geht über den gebundenen Term; Explain zeigt die Parameterwerte.
- Gleitkommazahlen kommen im Term nicht vor (D#23).
- Scope (D#9): Ein Hole erhält beim Fill die Ergebnisse aller vorangegangenen Schritte derselben Seq und der umschließenden Seqs als Kontext, gekürzt per Marshalling, gefiltert: nur Ergebnisse ohne Pfad (Shell-Aufrufe ohne Pfadargument, Exit-Codes, Done-Zusammenfassungen früherer Löcher) oder deren Pfade alle innerhalb des pathPrefix des Lochs liegen; die Pfade eines Shell-Aufrufs liefert die Argument-Policy (D#12); ohne Pfadargument ist das Ergebnis nur bei Workspace-Code und bei Befehlen aus CheckConfig.pathless (git log) pfadlos, sonst gilt "." (D#27, D#39). Explain rechnet mit den Pfaden im Term, Execute mit aufgelösten; Execute zeigt also höchstens, was Explain ankündigt (D#42). Eine Funktion für Explain und Execute: Scope.visible.
- Done in einem Fill beendet nur das Loch; die Zusammenfassung ist das Ergebnis des Lochs ("Hinweis des Agenten", D#15).
- JSON-Codec über circe-Json, von Hand (D#26): Diskriminator "type", strikt (unbekannte Felder sind Fehler), Mengen sortiert, origin als "Human" | "Model:<callId>". OriginMode.Required für Flows und Aufzeichnungen; OriginMode.Stamp(origin) für Modellantworten, die kein origin enthalten dürfen. Roundtrip-Garantie.
- JSON-Schema für submit_term (D#10): von Hand als Scala-Funktion termSchema(nesting), ohne Bibliothek. `strict: true` erlaubt keine Rekursion, daher ausgerollt: $defs Term_k verweist nur auf Term_{k-1}, Term_0 ohne Kinder. Verschachtelungsgrenze (syntaktisch, nicht maxDepth) aus der Runner-Konfiguration, Standard 5; Decoder und DryRun lehnen tiefere Terme ab. Summe als anyOf, jeder Zweig mit "type": {"const": "<Fall>"}; kein oneOf, kein discriminator. additionalProperties: false an jedem Objekt, alle Felder required. Keine Zahlen-Constraints im Schema (minimum o. ä. nicht unterstützt); Grenzen prüft DryRun. Gesetz: jeder vom Generator erzeugte Term (bis zur Verschachtelungsgrenze) validiert gegen das Schema und überlebt den circe-Roundtrip.
- structuralFingerprint(t: Term): ignoriert Prosa-Felder (goal, summary, question) und origin (D#19), behält Kontrollfluss, Operationsarten, Pfade und Envelope-Grenzen.
- termHash(t, rootEnvelope) (D#23): SHA-256 über die UTF-8-Bytes von kanonischem JSON nach RFC 8785 (JCS) von {"envelope": rootEnvelope, "term": t}, origin eingeschlossen. circe Printer.noSpaces mit sortKeys; Tests gegen die String-Vektoren aus RFC 8785; weicht circe ab, eigener Printer. Keine Unicode-Normalisierung.
- Decoder pro Hülle (D#25): decoderFor(envelope): Decoder[Term] lehnt Knoten ab, deren OpKind nicht in allowedOps liegt, und Terme tiefer als die Verschachtelungsgrenze. Fill-Teilterme werden nur damit decodiert.

=====================================================================
ALGEBRA (core)
=====================================================================
trait AgentAlg[F[_]] (tagless final, OHNE Typeclass-Constraints im Trait):
  readFile(path): F[StepResult], listDir(path): F[StepResult],
  writeFile(path, content): F[StepResult], editFile(path, expectedOld, replacement): F[StepResult],
  moveFile(from, to): F[StepResult],
  shell(cmd, args): F[StepResult], ask(question): F[StepResult], done(summary): F[StepResult],
  think(goal, envelope): F[StepResult]  -- im ADT: Hole. Im Execute: Fill → DryRun gegen envelope → rekursiv ausführen; das Ergebnis des Teilbaums ist das StepResult.
  seq(steps: List[F[StepResult]]): F[StepResult]
  branch(pred: Pred, thenP: F[StepResult], elseP: F[StepResult]): F[StepResult]
  repeatUntil(body: F[StepResult], until: Pred, max: Int): F[StepResult]
Kontrolloperationen (D#20): seq/branch/repeatUntil sind Operationen der Algebra. Statische Interpreter (DryRun, Cost, Explain) bekommen beide Zweige als Werte und sehen sie an; nur Execute wählt zur Laufzeit. Programme verlangen nur Applicative[F]; kein Monad, kein Selective, weil der Term keine Fortsetzungen hat. Sync/Async/IO ausschließlich in Interpretern am Rand.
Capabilities (D#25): restrict(alg: AgentAlg[F], envelope): AgentAlg[F] reicht nur Operationen mit OpKind in allowedOps weiter und liefert sonst ein Rejected-Ergebnis; Execute faltet Fill-Teilterme gegen die eingeschränkte Algebra (zweite Linie nach decoderFor und DryRun). Ein fester Teil-Trait wie ReadOnlyAlg[F] ist nur für Programme in Scala sinnvoll und kein Mechanismus für Fills.
ToTerm: AgentAlg[TermWriter] schreibt Terme; fromTerm[F[_]](t: Term)(alg: AgentAlg[F]) faltet einen Term in jede Algebra-Instanz, ohne Constraint (D#28). Gesetze: toTerm(program(t)) == t für alle Terme; fromTerm(toTerm(p)) ≡ p, beobachtet über Deutungen.

=====================================================================
ENVELOPE (Hülle eines Lochs)
=====================================================================
case class Envelope(allowedOps: Set[OpKind], pathPrefix: Path, maxSteps: Int, maxDepth: Int, shellAllowlist: Set[AllowedCommand])
case class AllowedCommand(binary: String, requiredPrefixArgs: List[String])  -- "git" + List("status"), nicht nur "git"

Attenuation-Gesetz (als Property-Test):
  child.allowedOps ⊆ parent.allowedOps
  child.pathPrefix liegt kanonisch unter parent.pathPrefix
  für jedes c in child.shellAllowlist gibt es p in parent.shellAllowlist mit c.binary == p.binary und p.requiredPrefixArgs Präfix von c.requiredPrefixArgs (D#21)
  child.maxDepth < parent.maxDepth (strikt kleiner, sonst unendliche Rekursion möglich)
  WorstCaseSteps(Teilbaum) ≤ parent.maxSteps, wobei (D#14) WorstCaseSteps: Blattoperation = 1; Seq = Summe (leer = 0); If = max(then, else); RepeatUntil = max · steps(body); Hole = 1 + hole.maxSteps
  Zur Laufzeit: ausgeführte Schritte aller Fills eines Lochs zusammen ≤ hole.maxSteps. Abgelehnte oder wiederholte Fills zählen gegen das Versuchslimit und das Modellaufruf-Limit, nicht gegen Schritte.
  Tiefe (Explain): Loch auf oberster Ebene = 1 + maxDepth; Term-Tiefe = Maximum über alle Löcher.

Well-Formedness jedes Hole: maxSteps ≥ 1, allowedOps nicht leer, maxDepth ≥ 0, Shell ∈ allowedOps ⇔ shellAllowlist nicht leer (D#21). Ein Loch, das formal monoton, aber unlösbar restringiert ist, wird abgelehnt.
Ask und Hole sind OpKinds (D#8): im Loch nur, wenn in allowedOps; Standard aus.
Die Wurzel-Hülle kommt aus der Runner-Konfiguration; pathPrefix wird relativ zum Arbeitsverzeichnis kanonisiert.
Ausgabegrößen (maxBytesPerStep) gehören NICHT in die Envelope, sondern in Runner-Konfiguration und Marshalling.

=====================================================================
INTERPRETER (alle über AgentAlg)
=====================================================================
- DryRun: führt nichts aus. Prüft statische Operationen gegen die Hülle, jedes Hole auf Attenuation und Well-Formedness, WorstCaseSteps gegen maxSteps, Shell-Argumente gegen die Argument-Policy, Prädikate auf Erfüllbarkeit: ein Pred, das statisch nie wahr werden kann (z. B. LastExitCode nach ListDir), ist ein Verstoß (D#20). Ergebnis bei ok ist ein Wert vom Typ CheckedTerm(term, rootEnvelope, termHash); CheckedTerm hat keinen öffentlichen Konstruktor, nur DryRun erzeugt ihn (parse, don't validate). Sonst Liste der Verstöße.
- Approval (D#11): approve(checked: CheckedTerm, confirmedHash: TermHash): Either[ApprovalError, ApprovedTerm]. Einziger Weg zu einem ApprovedTerm; scheitert, wenn confirmedHash nicht checked.termHash ist. Der Runner übergibt den Hash, den Explain gezeigt hat; der Auto-Approver in Tests ebenso.
- Cost: WorstCaseSteps und grobe Tokenschätzung, ohne Ausführung vollständig (der Term ist statisch).
- Explain: CheckedTerm als Prosa zur Freigabe; Löcher als "hier entscheidet der Agent, begrenzt auf: …" und darunter "kann Ergebnisse von Schritt … sehen" nach Scope.visible (D#30, D#42); statische WriteFile- und EditFile-Inhalte vollständig, lange oder mehrzeilige als eingerückter Block (D#41); Prüfkonfiguration und Flow-Parameter aus dem CheckedTerm (D#40); zeigt termHash als Kurzform und darunter vollständig (`hash      <64 hex>`, D#38). Programme, die Workspace-Code ausführen, als solche gekennzeichnet; Hinweis, wenn ein Loch diesen Code vorher ändern darf (D#12). Zeile "nicht erlaubt, u. a.:" mit den Einträgen aus explainDenials (Runner-Konfiguration), die die Wurzel-Hülle nicht erlaubt; steht ein Eintrag doch in der Allowlist, eine Warnung (D#24). Bei Flows die gebundenen Parameterwerte (D#22).
- Execute[IO]: nur unter Linux (WSL oder CI); auf anderen Plattformen beim Start ein typisierter Fehler (D#29). Tests, die Execute brauchen, sind dort sichtbar übersprungen. Bei Zeitüberschreitung endet der Prozess samt allen Kindprozessen (ProcessHandle.descendants). Scope für Fill über Scope.visible (D#30).
  Kern und Ports (D#32): Engine deutet die Algebra über Tools, FillModel, AskHandler, Clock, Recorder; Execute und Replay unterscheiden sich nur in den Ports. Ablauf und Ausgang D#33, Zählung D#34, Record-Log D#35, Shell-Zusätze D#36, Dateien D#37.
  Nimmt ausschließlich ApprovedTerm. Fill-Teilterme laufen nur als CheckedFill (Ergebnis von Tor 2), intern, ohne eigene Freigabe (D#6).
  Schreibt termHash als erstes Ereignis ins Record-Log; jeder Fill-Teilterm erhält seinen eigenen Hash im Log.
  Dateisystem nur unter dem Arbeitsverzeichnis. Syntaktisch (RelPath, auch in DryRun, auf allen Plattformen): nur relative Pfade mit / als Trenner; kein `..`, kein Backslash, keine Steuerzeichen, Zeilentrenner oder Bidi-Steuerung, kein Segment `.git` (D#31); kein Doppelpunkt, keine reservierten Gerätenamen (CON, PRN, AUX, NUL, COM1–9, LPT1–9, auch mit Endung), kein Namensteil mit Punkt oder Leerzeichen am Ende (D#29). Pfadprüfung zur Laufzeit: tiefstes existierendes Elternverzeichnis mit toRealPath() auflösen, Restpfad normalisiert anhängen, gegen workspace.toRealPath() und envelope.pathPrefix prüfen (toRealPath auf die Zieldatei selbst wirft bei writeFile NoSuchFileException). moveFile: from mit toRealPath (existiert, normale Datei, NOFOLLOW_LINKS), to nach der Regel für nicht existierende Ziele; beide geprüft; Files.move mit ATOMIC_MOVE, ohne REPLACE_EXISTING.
  Shell: nur AllowedCommand-Treffer (binary + Präfix-Args), keine Shell-Interpretation (kein sh -c), Argumente als Liste, keine Pipes/Redirections, Timeout, Ausgabe begrenzt.
  Argument-Policy pro Binary (D#12, Runner-Konfiguration): erlaubte Flags als Allowlist; jedes Nicht-Flag-Argument ist ein Pfad (bei grep das erste: Muster) und unterliegt derselben Pfadprüfung wie ReadFile. Start-Allowlist mit Policy:
    ls          -l -a                                  Pfade
    grep        -r -n -i -l -F -E -w --include=        Muster, dann Pfade
    git status  --porcelain --short                    keine
    git diff    --stat --name-only                     Pfade nach --
    git log     --oneline -n                           Revisionsbereich, Pfade nach --
    sbt test    keine                                  keine
  Ohne Pfadargument berührt ein Aufruf das Arbeitsverzeichnis "." (D#27); kombinierte Kurz-Flags (-rn) sind erlaubt, wenn jedes einzelne erlaubt ist.
  Das AdversarialFill-Gesetz gilt für Syscalls der Engine, nicht für Kindprozesse erlaubter Programme.
  Große Ausgaben: maxBytesPerStep aus der Runner-Konfiguration. Execute liest nicht die ganze Datei in den Heap; Marshalling liefert Ausschnitt (erste/letzte n Zeilen, Gesamtzeilen) mit Hinweis, gezielter zu suchen.
  think: Modelladapter (Fill) mit Ziel, Hülle, Scope-Kontext → Teilterm → DryRun gegen die Hülle des Lochs (CheckedFill) → erst bei ok rekursiv ausführen, ohne erneute Nutzer-Freigabe (D#6). Tiefe mitzählen.
  Laufzeitfehler im Teilterm (D#15): Fehler als Handlungsanweisung ans Modell, neuer Fill mit dem Fehler im Scope; Gelaufenes bleibt gelaufen; zählt gegen Versuche und das Schrittbudget des Lochs. Versuche erschöpft → Lauf endet mit Outcome aborted.
  Outcome eines Laufs: done | ask | aborted.
- Replay: gegen Aufzeichnungen, derselbe Kern wie Execute; jeder aufgezeichnete Werkzeugaufruf wird erneut gegen seine Hülle geprüft (ToolCheck, D#32). Aufgezeichnet werden Werkzeugergebnisse UND Modellantworten (Teilterme). Execute im Record-Modus schreibt ein versioniertes Ereignislog. Gleichheit (D#16): dieselbe Ereignisfolge; die aufgezeichneten Schreibvorgänge, in eine frische Kopie des Fixtures gespielt, ergeben denselben Dateibaum.
- BestOfN (Wrapper über den Modelladapter): Term n-mal erzeugen; Terme gelten als übereinstimmend, wenn die Mehrheit denselben structuralFingerprint hat; sonst ask statt raten.
Interpreter-Kombination (DryRun+Cost in einem Lauf) über ein handgeschriebenes Tuple2K-Produkt; kein cats-tagless (Makros in Scala 3 unzuverlässig), kein Selective (nicht in cats-core).
Gesetz: Interpreter lesen den Term und verändern ihn nie. Kein Interpreter normalisiert, optimiert oder schreibt in den Term zurück; Transformationen eines Terms (falls je nötig) sind eigene Funktionen Term => Term mit eigenem DryRun danach. Dadurch sind Interpreter unabhängig voneinander testbar und ihr Produkt ist kommutativ (als Property-Test: product(A, B) ≡ product(B, A) in beiden Ergebnissen).

=====================================================================
MARSHALLING
=====================================================================
Typeclass pro Ergebnis-Typ, Zielpublikum ist das Modell: Kürzung mit Hinweis ("gekürzt, n weitere Zeilen"), Fehler als Handlungsanweisung ("Pfad liegt außerhalb des Arbeitsverzeichnisses; erlaubt ist …", "expectedOld kommt 3-mal vor; erweitere den Kontext"). Der Scope-Kontext für Fill wird durch dasselbe Marshalling gebaut, mit dem Pfadfilter aus D#9.

=====================================================================
MODELLADAPTER (model)
=====================================================================
- Genau ein Tool: submit_term mit dem handgeschriebenen Term-JSON-Schema, `strict: true` (D#10).
- tool_choice: auto (erzwungenes any/tool ist auf claude-opus-5-5 ein 400). Prompt weist an, submit_term zu benutzen; eine Antwort ohne submit_term zählt als Fehlversuch.
- Plan (Aufgabe → Term, darf Löcher enthalten) und Fill (Loch: Ziel + Hülle + Scope-Kontext → Teilterm). Beim Fill steht die Hülle explizit im Prompt.
- Werkzeugbeschreibungen sind Prompts: Namen und Beschreibungen so, wie das Modell die Konzepte aus dem Training kennt.
- Ungültiges JSON, fehlender submit_term-Aufruf oder DryRun-Verstoß: Fehler als Text zurück, max 3 Versuche, dann Abbruch mit Begründung.
- API-Key aus Umgebungsvariable.

=====================================================================
RUNNER
=====================================================================
Aufgabe → Plan → DryRun (CheckedTerm) → bei Verstoß zurück ans Modell (max 3 Runden) → Explain mit termHash → Freigabe: approve(checked, gezeigter Hash) (ApprovedTerm) → Execute (Record) → Ergebnis und Outcome. Harte Limits: Gesamtschritte, Modellaufrufe, Zeit, Tiefe, maxBytesPerStep; aus Konfigurationsdatei.

=====================================================================
EVALS
=====================================================================
Sechs Aufgaben, Details und Eval 6 (Integrations-Use-Case, deterministisch und live) in evals/README.md. Erfolgsquote über n Läufe; Ergebnis als M#-Eintrag nach playbook/evals.md. Prüfvertrag: check.sh WORKDIR OUTCOME (D#17).

=====================================================================
TESTS
=====================================================================
- Gesetze (discipline/scalacheck): Attenuation; Well-Formedness; JSON-Roundtrip für zufällige Terme; Schema-Validierung aller generierten Terme; fromTerm(toTerm(p)) ≡ p; DryRun.ok ⇒ Execute verlässt nie Arbeitsverzeichnis/Allowlist und reißt nie maxSteps (über Replay prüfbar); Replay(Record(t)) == Execute(t).
- AdversarialFill: Test-Modelladapter, der für jedes Hole sowohl gültige Teilterme als auch Ausbruchsversuche liefert (../ außerhalb des Workspace, Symlink nach außen, nicht erlaubte Shell-Befehle, allowedOps-Verletzung, Schachtelung tiefer als maxDepth, maxSteps überzogen). Gesetz: für beliebige generierte Terme und beliebige Adversarial-Antworten führt Execute nie einen Syscall außerhalb von Wurzel- oder Loch-Hülle aus (über Record nachweisbar). Das ist der Beweis der zentralen Invariante für dynamische Löcher.
- Ein Test: Hole mit zu weiter Hülle wird abgelehnt. Ein Test: Hole mit maxDepth ≥ parent wird abgelehnt. Ein Test: unlösbar restringiertes Hole wird abgelehnt.
- Pfadtest: writeFile auf nicht existierende Datei innerhalb/außerhalb des Workspace, inkl. Symlink nach außen. RelPath: Doppelpunkt, Gerätenamen, Punkt/Leerzeichen am Ende abgelehnt; ähnliche harmlose Namen erlaubt.
- Scope: Explain zeigt pro Loch genau die Schritte, die Scope.visible liefert; Shell-Ergebnisse tragen nur ausdrückliche Pfade.
- Shell-Test: "git" + "push" wird abgelehnt, "git" + "status" akzeptiert. Argument-Policy: grep mit Pfad außerhalb des pathPrefix abgelehnt; git diff --output=x und --ext-diff abgelehnt; unbekanntes Flag abgelehnt.
- editFile: expectedOld fehlt, mehrdeutig, genau einmal.
- ApprovedTerm: Compile-Tests, dass Execute weder einen rohen Term noch einen CheckedTerm annimmt. approve mit falschem Hash scheitert.
- Scope: Ergebnis mit Pfad außerhalb des pathPrefix eines Lochs erscheint nicht in dessen Fill-Kontext.
- Schema: tiefer als die Verschachtelungsgrenze verschachtelter Term wird vom Decoder abgelehnt.
- Fingerprint: zwei Terme mit gleicher Struktur und anderer Prosa oder anderem origin haben denselben Fingerprint; ein geänderter Pfad ändert ihn. termHash: anderes origin ändert ihn.
- termHash: Schlüsselreihenfolge im Eingabe-JSON ändert den Hash nicht; String-Vektoren aus RFC 8785 werden exakt kanonisiert.
- moveFile: Ziel existiert → abgelehnt; Quelle ist Verzeichnis oder Symlink → abgelehnt; Ziel außerhalb pathPrefix → abgelehnt; Quelle danach weg, Ziel mit identischem Inhalt da.
- Shell-Attenuation: Kind git+[status] unter Eltern git+[] zulässig; Kind git+[] unter Eltern git+[status] abgelehnt. Hole mit Shell, aber leerer Allowlist abgelehnt.
- Prädikate: LastExitCode nach ListDir ist ein DryRun-Verstoß; LastOutputEmpty nach leerem Seq liest das Ergebnis davor.
- Flows: Parameterwert, der die Regex verletzt, abgelehnt; Wert mit Anführungszeichen und JSON-Fragment landet unverändert als Text im String-Blatt, nie als Knoten; ungebundener ${name} abgelehnt; Flow-Hülle weiter als die Wurzel-Hülle abgelehnt.
- decoderFor/restrict: Fill mit EditFile gegen Hülle ohne EditFile scheitert schon beim Decodieren; restrict liefert für verbotene Operation Rejected und keinen Syscall.
- Interpreter-Tests gegen F[_] (munit-tagless-final), nicht nur gegen IO.

=====================================================================
REFERENZEN (lies diese vor dem Entwurf, nutze die Konventionen daraus)
=====================================================================
- Kiselyov, "Typed Tagless Final Interpreters" (okmij.org/ftp/tagless-final/course/): Final-Initial-Isomorphismus → ToTerm/fromTerm; Transformationen auf Termen → DryRun.
- softwaremill.com/free-tagless-compared-how-not-to-commit-to-a-monad-too-early: warum ein Term-ADT nötig ist.
- blog.softwaremill.com/final-tagless-seen-alive: TF zum Einschränken des Effekt-Wrappers, nicht zum Effekt-Tracking.
- degoes.net/articles/tagless-horror; dev.to/devkat/tagless-final-in-scala-best-practices-3a01: Algebra ohne Constraints; Effekte nur in Interpretern.
- github.com/cb372/free-vs-tagless-final: Strukturvorlage.
- Object Capabilities (Mark Miller, "attenuation"): Envelopes als Capabilities.
- cats FreeApplicative; Mokhov, "Selective Applicative Functors" (2019): Idee hinter If/RepeatUntil als statische Knoten (ohne die Bibliothek).
- cats-laws / discipline: Invarianten als Gesetze.
- "Parse, don't validate" (Alexis King): ApprovedTerm als Typ, nicht als Flag.
- Anthropic Docs: Tool Use, Structured Outputs, "Building effective agents" (Dez 2024).
- Inspect AI, SWE-bench: Vorlage für evals/.
- Event Sourcing (Fowler, Young): Record als versioniertes Ereignislog.
- Später, nicht v1: Outlines/XGrammar (Constrained Decoding), MCP als externe Werkzeugschnittstelle, Let/Referenzen im Term.

=====================================================================
VORGEHEN
=====================================================================
0. Review des Gerüsts: README, CLAUDE.md, context.md, architecture.md, findings.md, knowledge/context.yaml, flows/, evals/README.md lesen. Widersprüche oder Lücken melden, nichts umschreiben. Warte auf OK.
1. Anlegen der sechs Eval-Fixtures mit task.md, envelope.json, check.sh in evals/ (Eval 6 inkl. flows/red-test.json). README nur ergänzen, wo Fixtures Details festlegen. Warte auf OK.
2. core: Term-ADT (mit MoveFile), Pred, JSON-Codec, decoderFor, handgeschriebenes Schema (D#10), Algebra mit Kontrolloperationen, restrict, ToTerm/fromTerm, DryRun mit CheckedTerm, Approval mit ApprovedTerm, Cost, Explain, structuralFingerprint, termHash (JCS), Flow-Format mit Parameterbindung, Term-Generator und AdversarialFill-Generator. Tests grün. Kein Execute, kein Modell.
3. core: Execute (Pfad- und Shell-Prüfung zuerst, mit Tests), Record/Replay, AdversarialFill-Gesetz grün, Eval 6 im deterministischen Modus grün. Läuft und wird getestet in WSL (D#29); Java 17 und sbt 1.10.7 sind dort installiert (O#15). · erledigt 2026-10-27 (D#32–D#38), zwei Reviews eingearbeitet.
4. model + runner mit submit_term, Plan und Fill. Erste Serie (n=10) gegen Eval 1 als M#1, Eval 6 live als M#2.
5. BestOfN, Eval-Harness über alle sechs Aufgaben.
Nach jedem Schritt: sbt test grün, context.yaml und findings.md nachgeführt, kurze Zusammenfassung, offene Punkte. Keine Features über diese Liste hinaus ohne Rückfrage.

OFFENE FRAGEN: entschieden als D#8 (Ask im Loch) und D#9 (Scope). Weitere offene Punkte in findings.md, Abschnitt Offen.
