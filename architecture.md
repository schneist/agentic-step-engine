# architecture.md

Nur Entscheidungen mit echten Alternativen und Umkehrkosten. Alles andere ist eine Zeile in findings.md. Begründung im Detail: context.md §8.

## D#1 Löcher statt Per-Schritt-Schleife

Alternativen: (a) Per-Schritt: Modell liefert pro Runde einen kleinen Term, Engine führt aus, Ergebnis zurück. (b) Terme mit Löchern. (c) Hybrid, erst a, dann b.
Gewählt: b. Nur so entsteht ein Plan, den man vorab prüfen und freigeben kann; a ist das heutige Tool-Use-Muster mit DryRun davor und bringt wenig Neues.
Umkehrkosten: hoch. Die Hüllen-Semantik, Tor 2, Record/Replay und die Evals bauen darauf auf.

## D#2 Ein Tool `submit_term` statt Tool pro Operation

Alternativen: (a) Ein Tool pro Operation (readFile, editFile, …), wie Modelle es gewohnt sind. (b) Ein Tool, das einen ganzen Term entgegennimmt. (c) Beides, mit Eval-Vergleich.
Gewählt: b, weil D#1 den Term braucht. Bekanntes Risiko: Modelle sind auf Einzel-Tools trainiert; die Trefferquote könnte niedriger sein. Wird mit Eval 1–6 gemessen; wenn schlecht, ist c der nächste Schritt.
Umkehrkosten: mittel. Modelladapter und Schema betroffen, Engine nicht.

## D#3 Shell mit Allowlist bereits in v1

Alternativen: (a) Nur Fake-Modell und Replay. (b) Nur Dateisystem. (c) Dateisystem plus Shell mit Allowlist.
Gewählt: c. Ohne Shell sind die Evals nicht realistisch (Tests laufen lassen, grep). Risiko begrenzt durch `AllowedCommand(binary, requiredPrefixArgs)`, keine Shell-Interpretation, Timeout, Ausgabelimit.
Umkehrkosten: niedrig. Shell ist eine Operation und eine Allowlist; entfernen ist leicht.

## D#4 Tagless-Final-Algebra plus Term-ADT als Doppelrepräsentation

Alternativen: (a) Nur Free-Monad/ADT. (b) Nur Tagless Final. (c) Beides, mit ToTerm und fromTerm als Brücke.
Gewählt: c. Das ADT ist nötig für Serialisierung und Inspektion; die Algebra für Interpreter-Komposition und eingeschränkte Teil-Algebren (Capabilities). Kiselyovs Final-Initial-Isomorphismus ist die theoretische Grundlage.
Umkehrkosten: hoch. Jeder Interpreter hängt daran.

## D#5 Statischer Baum ohne Fortsetzungen; Algebra verlangt nur Applicative

Alternativen: (a) Monadische Programme mit flatMap, nicht serialisierbar. (b) Variablen/Let im Term. (c) Jede Datenabhängigkeit ist ein Loch; Term ist Seq/If/RepeatUntil/Hole.
Gewählt: c für v1. Hält die Sprache klein, zwingt jede Datenabhängigkeit durch Tor 2. b ist Kandidat für v2, wenn Evals zeigen, dass zu viele Löcher nur Daten durchreichen.
Umkehrkosten: mittel. Variablen hinzufügen ist additiv; Monad einführen würde die Serialisierung brechen.

## D#6 Fill-Teilterme laufen ohne erneute Nutzer-Freigabe

Alternativen: (a) Jeden Fill vom Menschen freigeben lassen. (b) Fill läuft, wenn er die freigegebene Loch-Hülle einhält.
Gewählt: b. Die Hülle ist das Sicherheitsversprechen; a würde den Nutzen der Engine aufheben (der Mensch sitzt wieder daneben). Der Mensch sieht beim Plan jede Hülle im Klartext und gibt damit alle Fills frei, die darin bleiben.
Umkehrkosten: niedrig technisch (ein Hook im Runner), hoch konzeptionell.

## D#7 JSON-Schema für `submit_term` von Hand, nicht generiert

Alternativen: (a) tapir oder anderes Schema-Framework. (b) Handgeschrieben mit `$defs`.
Gewählt: b. Elf Term-Knoten und sechs Prädikate, etwa hundert Zeilen pro Ebene, keine Abhängigkeit. Rekursion und strict: siehe D#10. Gesetz: jeder generierte Term validiert und überlebt den Codec-Roundtrip.
Umkehrkosten: niedrig.

## D#8 `Ask` im Loch nur als erlaubte Operation

Alternativen: (a) Ask nur auf oberster Ebene; das Loch endet mit einem Ergebnis, der Flow fragt per If. (b) Ask im Loch, wenn `Ask ∈ allowedOps`. (c) Ask überall.
Gewählt: b. Eine Regel für alle Operationen; Eval 5 braucht ein Loch, das merkt, dass die Aufgabe nicht in die Hülle passt. Standard ist aus, Explain zeigt „darf nachfragen“.
Umkehrkosten: niedrig. Eine OpKind weniger.

## D#9 Scope: alle umschließenden Seqs, gefiltert nach pathPrefix

Alternativen: (a) nur die eigene Seq. (b) alle umschließenden Seqs. (c) explizit benannte Schritte (braucht Referenzen, D#5).
Gewählt: b mit Filter. Löcher brauchen oft Ausgaben von weiter oben (git log im Release-Flow); der Filter verhindert, dass ein Loch Dateiinhalte außerhalb seiner Hülle sieht.
Umkehrkosten: niedrig. Eine Funktion im Marshalling; messbar über die Evals.

## D#10 Strict-Schema, ausgerollt bis zu einer Verschachtelungsgrenze

Alternativen: (a) rekursives Schema ohne `strict`; Gültigkeit nur durch Decoder und Wiederholung. (b) ausgerolltes Schema mit `strict: true`. (c) Structured Outputs statt Tool (gleiche Rekursionsgrenze).
Gewählt: b. Strict garantiert gültiges JSON und spart Versuche; Rekursion ist in strict ausgeschlossen. Preis: eine Verschachtelungsgrenze, die Generator, Decoder und DryRun kennen müssen.
Umkehrkosten: niedrig. `termSchema(nesting)` kann beide Formen erzeugen.

## D#11 `CheckedTerm` und `ApprovedTerm` getrennt

Alternativen: (a) ein Typ `ApprovedTerm` aus DryRun, Freigabe im Runner. (b) zwei Typen, Freigabe als Funktion mit Hash-Bestätigung.
Gewählt: b. Erst dann beweist der Compiler beide Säulen: geprüft und freigegeben. Die Freigabe ist an den gezeigten Hash gebunden.
Umkehrkosten: niedrig technisch, hoch konzeptionell (D#6 stützt sich darauf).

## D#12 Argument-Policy pro Shell-Befehl

Alternativen: (a) Denylist gefährlicher Flags. (b) Allowlist der Flags pro Befehl, Nicht-Flag-Argumente als Pfade geprüft. (c) Shell streichen.
Gewählt: b. Eine Denylist ist nie vollständig; ohne Pfadprüfung der Argumente umgehen `cat`/`grep` den pathPrefix.
Umkehrkosten: mittel. Policy-Format und Explain hängen daran.

## D#18 `MoveFile` als neue Operation

Alternativen: (a) keine neue Operation, Eval 2 als Symbol-Umbenennung. (b) `MoveFile` ohne Überschreiben. (c) `MoveFile` plus `DeleteFile`.
Gewählt: b. Umbenennen ist eine alltägliche Aufgabe und mit WriteFile/EditFile nicht lösbar. Verschieben verliert nichts; Löschen schon, deshalb nicht in v1.
Umkehrkosten: mittel. Algebra, ADT, Schema, Interpreter und Tests bekommen einen Fall mehr.

## D#20 Kontrollknoten als Operationen der Algebra

Alternativen: (a) `seq`/`branch`/`repeatUntil` als Algebra-Operationen. (b) eigene Typklasse Selective. (c) Monad.
Gewählt: a. Statische Interpreter sehen beide Zweige als Werte, nur Execute wählt; die Algebra bleibt ohne Constraints. b bringt bei zwei Knoten nur Abstraktion, c bricht Serialisierung und CLAUDE.md.
Umkehrkosten: hoch. Jeder Interpreter implementiert diese Operationen.

## D#22 Flow als Wrapper mit typisierten Parametern

Alternativen: (a) Textersetzung im JSON vor dem Parsen. (b) Wrapper `{description, params, envelope, term}`, Ersetzung nach dem Decodieren in String-Blättern, Werte gegen Regex geprüft.
Gewählt: b. a erlaubt Injection neuer Knoten über Parameterwerte. Die Hülle gehört in die Datei, nicht in einen Kommentar.
Umkehrkosten: niedrig. Ein Datenformat und eine Funktion.

## D#29 Execute nur unter Linux

Alternativen: (a) Programmnamen in der Runner-Konfiguration auf feste Aufrufe abbilden, .bat/.cmd verbieten, Windows nativ unterstützen. (b) Execute nur unter Linux (WSL oder CI). (c) Alles im Container.
Gewählt: b. Hält Execute schlicht: keine Programmauflösung, keine cmd.exe-Falle, ein Prozessmodell. Unter Windows bleiben Prüfung und Explain nutzbar.
Umkehrkosten: mittel. a ließe sich später als Schicht in der Runner-Konfiguration nachrüsten; Execute selbst bliebe gleich.

## D#30 Sicht großzügig, Prüfung streng, Datenfluss im Explain

Alternativen: (a) Scope nach D#9, Lücke dokumentiert. (b) Scope nach D#27, auch Ausgaben ohne Pfad gelten als ".". (c) Sicht pro Schritt im Flow markieren (braucht Verweise, D#5). (d) wie a, aber Explain zeigt pro Loch, was es sieht.
Gewählt: d. b bricht das Diagnose-Loch in Eval 6; c ist v2. Mit d gibt der Mensch den Datenfluss bewusst frei.
Umkehrkosten: niedrig. Eine Funktion (Scope.visible) und eine Zeile im Explain.
Nachtrag D#39: Ohne Pfadargument sind nur Workspace-Code und Befehle aus `pathless` (git log) pfadlos; grep, ls, git status zählen als ".".

## D#32 Execute und Replay über einen Kern mit Ports

Alternativen: (a) Execute als eigener Interpreter, Replay liest das Log getrennt nach. (b) Ein Kern (Engine), austauschbare Ports für Werkzeuge, Modell, Mensch, Uhr und Recorder.
Gewählt: b. Nur dann ist Replay(Record(t)) == Execute(t) ein Gesetz über Vollständigkeit des Logs und Determinismus des Kerns; dieselben Ports tragen die Tests ohne Dateisystem und den Modelladapter in Schritt 4.
Umkehrkosten: hoch. Engine, Replay, Tests und der Adapter hängen an den Port-Signaturen.

## D#25 Capabilities über Decoder und eingeschränkte Algebra

Alternativen: (a) feste Teil-Traits pro erlaubter Teilmenge (ReadOnlyAlg …). (b) `decoderFor(envelope)` plus `restrict(alg, envelope)` zur Laufzeit.
Gewählt: b. Fills kommen als JSON, der Compiler sieht sie nie; 2⁸ Teilmengen sind keine Traits. Die Sperre liegt trotzdem in der Struktur: Was der Decoder nicht annimmt, gibt es nicht.
Umkehrkosten: niedrig. Beide Funktionen sind lokal.

## D#55 Mehrrunden-Fill im Loch

Alternativen: (a) Nichts ändern; Pläne und Flows lesen vor dem Loch (D#51, D#53). (b) Ergebnisse eines Lochs bleiben für spätere Geschwister-Löcher sichtbar. (c) Ein Fill ohne Done führt zu einer weiteren Runde desselben Lochs, die die Ergebnisse der Runden davor sieht.
Gewählt: c. a geht für generische Flows nicht, wenn die Datei vorher unbekannt ist (Eval 6, Release-Flow); b heilt nicht, dass ein Loch lesen und dann per Compare-and-Swap ändern muss. c ändert weder Sprache noch Hüllen noch Freigabe: Jede Runde geht durch Decoder und Tor 2, alle Runden teilen das Budget des Lochs. Innerhalb der freigegebenen Hülle ist das eine begrenzte Per-Schritt-Schleife (vgl. D#1 a), der Plan bleibt statisch prüfbar.
Umkehrkosten: mittel. Engine, Record-Log (Version 3), Replay, Cost, Fill-Prompt und die Messbasis ab M#1 hängen daran.
