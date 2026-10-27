# context.md

Das Warum und das Für-wen. Technik steht in SPEC.md, Entscheidungen in findings.md.

## 1. Zweck

Agenten auf Basis von Sprachmodellen sind nützlich, aber in der Praxis an einer Stelle unbrauchbar: Man kann ihnen Effekte nicht vorab ansehen. Sie rufen Werkzeuge einzeln auf, die Umgebung führt jeden Aufruf aus, und der Mensch hat die Wahl zwischen "jede Aktion abnicken" und "gar nicht hinschauen". Diese Engine ersetzt die Folge von Effekten durch ein Programm in einer kleinen statischen Sprache, das man prüfen, erklären, schätzen und freigeben kann, bevor es läuft. Das Unbekannte wird nicht verboten, sondern eingehegt: als Loch mit einer Hülle.

## 2. Für wen

Menschen, die eine wiederkehrende Aufgabe haben, bei der neunzig Prozent fest sind und zehn Prozent Urteil brauchen, und bei der ein Fehler teuer ist. Leute, die Workflow-Engines kennen, finden hier eine Prozessdefinition mit einer neuen Art von Task, bei der die Berechtigungen des Ausführenden zur Definition gehören.

## 3. Was es nicht ist

Kein allgemeiner Chat-Agent. Kein Ersatz für Code-Review. Kein Sandbox-Produkt; die Sicherheit kommt aus Prüfung und Hüllen, nicht aus Isolation, und ein Container drumherum bleibt sinnvoll.

## 4. Herkunft

Entstanden aus einem Gespräch über die Struktur von Sprachmodellen (Residual Stream, Superposition, Sparse Autoencoders), über Scaffolding, das mit der Zeit in die Modelle wandert, und über Tagless Final als Technik, ein Programm von seiner Deutung zu trennen. Das Vorgängerprojekt IoTTE (Tagless-Final-Grammatik für Arduino-Steuerung) liefert das Muster: Algebra, Interpreter, Runner, Marshalling.

## 5. Grenzen von v1

Nur Dateisystem und eine winzige Shell-Allowlist. Ein Modelladapter (Anthropic). Keine Autoren-Oberfläche für menschliche Flows; die werden als JSON geschrieben. Kein Constrained Decoding; die Prüfung passiert nach dem Sampling, nicht darin.

## 6. Offene Fragen

Siehe findings.md, Abschnitt "Offen". Werden beim OK zu Schritt 1 entschieden und dann als D# geführt.

## 7. Glossar

- **Term**: Ein Programm in der statischen Sprache der Engine. Daten, kein Code. Serialisierbar als JSON.
- **Algebra** (`AgentAlg[F]`): Die Operationen, ohne Festlegung, was sie bedeuten. Tagless Final.
- **Interpreter**: Eine Deutung der Algebra. DryRun, Cost, Explain, Execute, Replay. Lesen den Term, verändern ihn nie.
- **ToTerm / fromTerm**: Die Brücke zwischen Programm-als-Code und Programm-als-Daten. `ToTerm` schreibt einen Term, `fromTerm` spielt ihn in jeden Interpreter ein.
- **Loch** (`Hole`): Ein Knoten im Term, an dem das Modell erst zur Laufzeit entscheidet. Trägt ein Ziel und eine Hülle.
- **Hülle** (`Envelope`): Was in einem Loch maximal passieren darf: erlaubte Operationen, Pfadpräfix, maximale Schritte, maximale Tiefe, Shell-Allowlist.
- **Attenuation**: Die Regel, dass eine innere Hülle nie weiter ist als die äußere. Nach Mark Miller (Object Capabilities).
- **Envelope-Monotonie**: Dasselbe wie Attenuation, als Gesetz formuliert und per Property-Test geprüft.
- **Plan**: Der erste Modellaufruf: Aufgabe hinein, Term mit Löchern heraus.
- **Fill**: Ein Modellaufruf für ein Loch: Ziel, Hülle und Kontext hinein, Teilterm heraus.
- **Scope**: Die Ergebnisse der vorangegangenen Schritte, die ein Loch beim Fill als Kontext bekommt.
- **Tor 1 / Tor 2**: DryRun über den Plan beziehungsweise über einen Fill-Teilterm gegen die Hülle seines Lochs.
- **CheckedTerm**: Ein Term, der DryRun bestanden hat, mit Wurzel-Hülle und Hash. Nur DryRun kann ihn erzeugen. Noch nicht freigegeben (D#11).
- **ApprovedTerm**: Ein CheckedTerm, dessen Hash ein Mensch (oder in Tests der Auto-Approver) bestätigt hat. Nur `Approval.approve` kann ihn erzeugen; nur ihn nimmt Execute an (D#11).
- **CheckedFill**: Ein Fill-Teilterm, der Tor 2 bestanden hat. Intern in Execute; braucht keine eigene Freigabe, weil er in einem ApprovedTerm liegt (D#6).
- **Auto-Approver**: Freigabe in Tests: ruft `approve` mit dem Hash aus der Explain-Ausgabe auf, durchläuft also denselben Weg wie ein Mensch.
- **OpKind**: Die Operationsarten, die eine Hülle erlauben kann: ReadFile, ListDir, WriteFile, EditFile, MoveFile, Shell, Ask, Hole (D#8, D#18). Seq, If, RepeatUntil, Done sind immer erlaubt.
- **WorstCaseSteps**: Obere Schranke der Schritte eines Terms, statisch berechnet (D#14).
- **Tiefe**: Schlechteste Loch-Verschachtelung; ein Loch auf oberster Ebene zählt 1 plus sein maxDepth (D#14).
- **Verschachtelungsgrenze**: Maximale syntaktische Schachtelung von Knoten (Seq in If in …), bis zu der das Schema ausgerollt ist; nicht dasselbe wie maxDepth (D#10).
- **Ausrollen**: Ein rekursives Schema als endliche Folge von Ebenen schreiben, Term_k verweist nur auf Term_{k-1} (D#10).
- **Well-Formedness**: Ein Loch ist lösbar beschränkt: maxSteps ≥ 1, allowedOps nicht leer, maxDepth ≥ 0.
- **Argument-Policy**: Pro erlaubtem Shell-Programm die erlaubten Flags; alle anderen Argumente sind Pfade und werden wie Dateipfade geprüft (D#12).
- **Workspace**: Das Arbeitsverzeichnis eines Laufs. Kein Pfad darf es verlassen.
- **Marshalling**: Aufbereitung von Ergebnissen für das Modell: Kürzung mit Hinweis, Fehler als Handlungsanweisung; baut auch den Scope.
- **Outcome**: Ausgang eines Laufs: done, ask oder aborted. Geht an `check.sh` (D#17).
- **Fixture**: Vorlage des Arbeitsverzeichnisses einer Eval in `evals/<nr>-<slug>/fixture/`; vor jedem Lauf frisch kopiert.
- **Skript-Modell**: Test-Modelladapter mit festen Antworten, für den deterministischen Modus von Eval 6.
- **Hinweis des Agenten**: Die Done-Zusammenfassung eines Lochs, im Lauf-Log ausgegeben (D#15).
- **termHash**: SHA-256 über das kanonische JSON (JCS) von Term plus Wurzel-Hülle, origin eingeschlossen. Bindet die Freigabe an genau diesen Term (D#23).
- **Fingerprint** (`structuralFingerprint`): Ein Term ohne seine Prosa-Felder und ohne origin. Zwei Pläne mit anderem Wortlaut oder anderer Herkunft, aber gleicher Struktur haben denselben Fingerprint (D#19).
- **Record / Replay**: Execute zeichnet Werkzeugergebnisse und Modellantworten auf; Replay spielt sie ohne Dateisystem und ohne Modell ab.
- **AdversarialFill**: Ein Test-Modelladapter, der Löcher absichtlich mit Ausbruchsversuchen füllt.
- **Origin**: Herkunft eines Term-Knotens, `Human` oder `Model(callId)`; callId benennt den Modellaufruf (plan-n, fill-n). Zählt für den Hash, nicht für den Fingerprint (D#19).
- **Flow**: Ein von Menschen geschriebener Term mit Löchern, in `flows/`. Als Datei ein Wrapper aus description, params, envelope und term (D#22).
- **Flow-Parameter**: Platzhalter `${name}` in String-Feldern eines Flows, mit Regex deklariert, nach dem Decodieren und vor DryRun gebunden (D#22).
- **MoveFile**: Verschiebt eine existierende Datei an einen Pfad, der noch nicht existiert; beide unter pathPrefix (D#18).
- **JCS**: JSON Canonicalization Scheme, RFC 8785; Grundlage des termHash (D#23).
- **explainDenials**: Liste in der Runner-Konfiguration mit Befehlen, die Explain als „nicht erlaubt, u. a.“ nennt (D#24).
- **decoderFor / restrict**: Hüllenabhängiger Decoder, der nicht erlaubte Knoten beim Parsen ablehnt, und eingeschränkte Algebra, die nur erlaubte Operationen weiterreicht (D#25).
- **Kontrolloperationen**: `seq`, `branch`, `repeatUntil` in der Algebra; statische Interpreter sehen alle Zweige, Execute wählt (D#20).
- **Violation**: Ein Verstoß, den DryRun meldet, mit Ort im Term (`Location`, etwa `$.steps[2].envelope`) und einer Handlungsanweisung an das Modell.
- **DecodeError**: Ein Fehler beim Lesen von JSON, mit Ort; ebenfalls an das Modell gerichtet.
- **OriginMode**: `Required` für Flows und Aufzeichnungen (origin steht im JSON), `Stamp(origin)` für Modellantworten (die Engine setzt es, D#26).
- **CheckConfig**: Verschachtelungsgrenze und Argument-Policies, mit denen DryRun prüft.
- **Ergebnisform** (`Shape`): Welche Felder das Ergebnis eines Schritts haben kann (Exit-Code, Ausgabe); damit erkennt DryRun Bedingungen, die nie wahr werden (D#20).
- **Konstanter Funktor**: Ein `F[_]`, das sein Argument ignoriert, etwa `Cost.Of[A] = Cost`. So deuten Cost, Explain, DryRun und ToTerm die Algebra, ohne etwas auszuführen.
- **Sicht** (`Scope.visible`): Welche früheren Ergebnisse ein Loch beim Fill bekommt. Explain zeigt sie vorab als Obergrenze „kann Ergebnisse von Schritt … sehen“, Execute wendet dieselbe Regel auf aufgelöste Pfade an (D#9, D#30, D#39, D#42). Pfadlos ist ein Ergebnis ohne Pfad, das jedes Loch sehen kann: Workspace-Code, Befehle aus `pathless`, Antworten, Zusammenfassungen von Löchern.
- **WSL**: Windows Subsystem for Linux. Dort läuft Execute auf dem eigenen Rechner (D#29).
- **Port**: Schnittstelle von Execute zur Außenwelt: `Tools` (Werkzeuge), `FillModel` (Modell beim Fill), `AskHandler` (Mensch bei Ask), `Clock` (Zeitlimit), `Recorder` (Ereignislog). Execute und Replay unterscheiden sich nur in den Ports.
- **Engine**: Der gemeinsame Kern von Execute und Replay, eine Deutung der Algebra über die Ports.
- **LocalTools**: Die echten Werkzeuge unter Linux: Pfadprüfung zur Laufzeit, Datei-Operationen, Shell mit Timeout, gehärtetes git.
- **Submission**: Die Antwort des Modells, so wie submit_term sie liefert: `{"term": …}` als Text.
- **StepId**: Nummer eines Schritts im Log: auf oberster Ebene wie im Explain, im Loch `Loch.Versuch.Schritt` (etwa `4.2.1`).
- **Reply / touched**: Ergebnis eines Werkzeugaufrufs; `touched` nennt die aufgelösten Pfade, an denen ein Syscall stattfand. Leer heißt: vor jedem Syscall abgelehnt.
- **Record-Log**: Das versionierte Ereignislog eines Laufs (JSON Lines, Feld `v`); erstes Ereignis ist der Start mit termHash.
- **Lauf-Log** (`RunLog`): Die Darstellung des Record-Logs für Menschen, im Format des README.
- **ToolCheck**: Die reine Prüfung eines Werkzeugaufrufs gegen seine Hülle (Operation, Pfade, Allowlist, Policy). LocalTools ruft sie vor jedem Syscall, Replay vor jedem abgespielten Aufruf (D#32).
- **Aufruf eines Lochs**: Jede Ausführung eines Hole-Knotens, auch jede in einem weiteren Durchlauf von RepeatUntil; jeder Aufruf hat sein eigenes Budget (D#34).
- **Mutationsprobe**: Einen behobenen Fehler absichtlich wieder einbauen und prüfen, ob ein Gesetz ihn findet. Belegt, dass ein Gesetz prüft, was sein Name behauptet.
- **Ausschnitt** (`Excerpt`): Gekürzte Ausgabe für das Modell: erste und letzte Zeilen, Gesamtzahl, Hinweis.
- **Golden-Test**: Ein Test, der eine Ausgabe Zeichen für Zeichen mit einer festgehaltenen vergleicht (Explain für red-test).
- **CAS** (Compare-and-Swap): `EditFile` ersetzt nur, wenn `expectedOld` genau einmal vorkommt.

## 8. Warum

Ein Eintrag pro Entwurfsentscheidung. Geschrieben für Leser, die Workflow-Engines kennen, aber keine Sprachmodelle.

### Warum Terme statt roher Werkzeugaufrufe

Ein Sprachmodell ist innen nicht lesbar. Seine Zwischenzustände sind Vektoren, in denen tausende Konzepte übereinandergelegt sind (Superposition), und die Interpretierbarkeitsforschung versucht mit erheblichem Aufwand, daraus nachträglich unabhängige, lesbare Merkmale herauszuschneiden. Was man am Rand des Modells sieht, sind Tokens, und bei einem gewöhnlichen Agenten sind diese Tokens einzelne Werkzeugaufrufe, die sofort ausgeführt werden. Es gibt kein Objekt, auf das man zeigen und sagen könnte: Das wird gleich passieren. Terme stellen dieses Objekt her. Das Modell schreibt ein Programm als Daten, und mehrere unabhängige Leser können es prüfen, erklären und schätzen, bevor irgendetwas läuft. Die Lesbarkeit, die das Modell innen nicht hat, wird am Rand zurückgeholt.

### Warum Löcher

Ein Modell kann Fortsetzungen nicht vorab schreiben. Was nach dem Lesen einer Datei passieren soll, hängt vom Inhalt ab, und den kennt das Modell beim Planen nicht. Dazu kommt eine Eigenschaft der Erzeugung: Ein einmal ausgegebenes Token kann nicht zurückgenommen werden; der Text ist die einzige Schleife, die ein Modell hat. Ein vollständiger Plan vorab wäre also entweder trivial oder erfunden. Löcher lösen das, indem sie das Unbekannte markieren statt es zu verstecken: Der Plan sagt, hier wird später entschieden, und die Hülle sagt, wie weit diese Entscheidung reichen darf. Der prüfbare Teil wird prüfbar, der dynamische Teil bekommt eine Grenze. Das ist etwas anderes als die übliche Schleife, in der das Modell nach jedem Schritt neu gefragt wird; dort gibt es keinen Plan, nur Schritte.

### Warum Hüllen als Capabilities (D#25)

Man kann eine Grenze auf zwei Arten durchsetzen: prüfen, ob eine Aktion erlaubt ist, oder dafür sorgen, dass die verbotene Aktion gar nicht formulierbar ist. Das zweite ist stärker, weil es keine Liste von Verboten gibt, die unvollständig sein könnte: Was nicht vorgesehen ist, kann nicht vorkommen. Mark Millers Object-Capability-Modell beschreibt das: Wer eine Fähigkeit nicht in der Hand hat, kann die Operation nicht einmal aufrufen, und Fähigkeiten lassen sich nur abgeschwächt weitergeben (Attenuation). Übertragen auf die Engine (D#25): Ein Fill kommt als JSON zur Laufzeit; der Compiler sieht ihn nie. Die Sperre liegt deshalb im Parser: `decoderFor(envelope)` kennt für ein Loch mit `allowedOps = {ReadFile, Shell}` schlicht keinen `EditFile`-Knoten, und was nicht decodiert wird, existiert nicht als Term. Beim Ausführen wird der Teilterm zusätzlich gegen `restrict(alg, envelope)` gefaltet, eine Algebra, die verbotene Operationen nicht weiterreicht. Nur für Programme, die in Scala geschrieben sind (etwa gegen einen Trait `ReadOnlyAlg`), ist es wirklich das Typsystem. DryRun bleibt die Verteidigung für alles, was die Struktur nicht ausdrückt (Pfade, Schrittzahlen, Argumente).

### Warum Interpreter nie zurückschreiben

Die Interpreter dieser Engine ähneln Attention-Köpfen in einem Transformer: Mehrere Leser schauen auf dasselbe Objekt und holen Verschiedenes heraus. Der Unterschied ist entscheidend. Attention-Köpfe schreiben ihr Ergebnis in den gemeinsamen Zustand zurück, und der nächste Kopf liest einen veränderten Zustand; deshalb ist ein einzelner Kopf nur im Zusammenhang mit allen anderen zu verstehen, und deshalb ist das Modell so schwer zu analysieren. Interpreter hier lesen den Term und lassen ihn unverändert. Dadurch ist jeder Interpreter einzeln testbar, ihr Produkt ist kommutativ, und was DryRun gesehen hat, ist genau das, was Execute ausführt. Sobald ein Interpreter den Term "optimieren" oder "normalisieren" dürfte, wäre diese Garantie weg. Transformationen gibt es trotzdem, aber als eigene Funktionen mit eigenem DryRun danach.

### Warum zwei DryRun-Tore

Das Modell entscheidet zweimal: einmal beim Plan, einmal bei jedem Loch. Beide Entscheidungen können falsch oder böswillig sein, also brauchen beide ein Tor. Das erste Tor prüft den Plan gegen die Wurzel-Hülle, das zweite jeden Fill-Teilterm gegen die Hülle seines Lochs. Der Mensch gibt nur den Plan frei, und zwar mit der Hülle jedes Lochs im Klartext vor Augen. Danach vertritt ihn die Hülle (D#6). Das ist die Stelle, an der die Engine ihren Nutzen zieht: Ein Mensch kann vorab beantworten, welcher Bereich erlaubt ist, aber nicht, welche einzelne Aktion. Die Frage wird von "darf diese Aktion?" zu "darf dieser Bereich?" verschoben, und die zweite Frage ist die, die ein Mensch einmal, vorab, in einer Datei beantworten kann.

### Warum ApprovedTerm ein Typ ist und kein Flag

"Parse, don't validate" (Alexis King): Statt einen Wert zu prüfen und ein Flag zu setzen, erzeugt die Prüfung einen neuen Typ, den nur sie erzeugen kann. `Execute` nimmt keinen `Term`, sondern einen `ApprovedTerm`, und `ApprovedTerm` hat keinen öffentlichen Konstruktor. Damit kann der Compiler beweisen, dass kein Codepfad existiert, der Execute ohne DryRun erreicht. Ein Flag könnte jemand versehentlich setzen; einen Typ nicht. Der Hash im ApprovedTerm bindet außerdem die Freigabe an genau diesen Term: Was Explain gezeigt hat, ist das, was läuft.

### Warum zwei Typen: geprüft und freigegeben (D#11)

Das Versprechen der Engine ruht auf zwei Säulen: Die Maschine hat geprüft, und ein Mensch hat zugestimmt. Ein einziger Typ, den DryRun erzeugt, beweist nur die erste. Die Frage „Freigeben?“ stünde dann im Runner, und ein Fehler dort oder eine Abkürzung für Tests würde nicht auffallen. Mit zwei Typen ist jede Säule ein eigener Schritt, den der Compiler erzwingt: `CheckedTerm` kann nur DryRun bauen, `ApprovedTerm` nur `approve`, und `approve` verlangt den Hash, den Explain gezeigt hat. Ändert sich der Term zwischen Anzeige und Freigabe, scheitert die Freigabe. Für Workflow-Engines ist das der Unterschied zwischen „Prozessdefinition validiert“ und „Prozessdefinition deployed“. D#6 hängt daran: Fills laufen ohne Rückfrage, weil ein Mensch die Hülle gesehen und bestätigt hat, und das muss beweisbar passiert sein.

### Warum Ask eine erlaubte Operation ist (D#8)

Ein Loch ist die Stelle, an der der Mensch nicht dabei sein muss. Darf es trotzdem fragen, holt es ihn zu einem Zeitpunkt zurück, den er beim Freigeben nicht vorhersehen konnte. Ganz verbieten geht aber auch nicht: Manche Löcher merken erst beim Arbeiten, dass die Aufgabe nicht in die Hülle passt (Eval 5), und dann ist Fragen besser als Raten oder ein stiller Umweg. Die Lösung ist dieselbe wie für jede andere Fähigkeit: `Ask` steht in `allowedOps` oder nicht, Explain zeigt es an, und die Attenuation gilt auch hier. Standard ist aus.

### Warum der Scope gefiltert wird (D#9)

Ein Loch braucht Kontext, oft von weiter oben (das Changelog-Loch braucht die Ausgabe von `git log`). Deshalb sieht es alle umschließenden Seqs. Die Hülle begrenzt aber bisher nur, was ein Loch tut, nicht, was es sieht. Ohne Filter hätte ein Loch mit `pathPrefix = docs/` den Inhalt einer Datei im Kontext, die ein früherer Schritt außerhalb von docs/ gelesen hat, und könnte ihn in eine Datei unter docs/ schreiben. Der Filter lässt nur Ergebnisse ohne Pfad (Shell-Aufrufe ohne Pfadargument, Exit-Codes, Zusammenfassungen früherer Löcher) und solche, deren Pfade alle im eigenen pathPrefix liegen, durch. Welche Pfade ein Shell-Aufruf berührt, weiß die Engine aus der Argument-Policy (D#12); `grep -r token secrets/` gilt also als Ergebnis über `secrets/`. Zusammenfassungen früherer Löcher sind Prosa des Modells und könnten Inhalte transportieren; dieser Kanal bleibt offen, weil sonst Diagnose- und Reparatur-Loch nicht zusammenarbeiten können.

### Warum das Schema ausgerollt wird (D#10)

Mit `strict: true` garantiert die API, dass die Antwort zum Schema passt; das Modell kann kein ungültiges JSON mehr liefern, und Versuche werden nicht für Klammerfehler verbraucht. Strict unterstützt aber keine rekursiven Schemas. Also wird die Rekursion als endliche Leiter geschrieben: Ebene 5 darf Ebene-4-Knoten enthalten, Ebene 4 Ebene-3-Knoten, und so weiter. Die Grenze ist syntaktisch (wie tief Seq, If, RepeatUntil ineinander stecken), nicht dasselbe wie maxDepth (wie tief Löcher ineinander stecken). Dazu kommt eine Eigenheit des aktuellen Modells: Ein bestimmtes Tool lässt sich nicht erzwingen. Das Modell wird im Prompt angewiesen, und eine Antwort ohne `submit_term` zählt als Fehlversuch.

### Warum Shell-Argumente eine Policy brauchen (D#12)

`AllowedCommand("grep", [])` sagt, welches Programm laufen darf, aber nicht, worauf. `grep -r token /` liest das ganze Dateisystem, `git diff --output=…` schreibt Dateien, `git diff --ext-diff` startet fremde Programme. Eine Liste verbotener Flags ist nie vollständig. Deshalb gilt umgekehrt: Pro Programm sind bestimmte Flags erlaubt, und alles andere, was kein Flag ist, wird als Pfad behandelt und genauso geprüft wie bei ReadFile. `cat` fällt weg, weil ReadFile dasselbe mit Prüfung und Kürzung tut. Programme, die Code aus dem Workspace ausführen (Skripte, `sbt test`), bleiben erlaubt, aber Explain sagt es, und wenn ein Loch diesen Code vorher ändern darf, steht es als Hinweis da. Die Engine kann nicht versprechen, was ein Kindprozess tut; sie verspricht, was sie selbst tut.

### Warum ein Prädikat LastOutputEmpty (D#13)

Der Release-Flow will abbrechen, wenn das Arbeitsverzeichnis nicht sauber ist. `git status --porcelain` meldet das über eine nicht leere Ausgabe, nicht über den Exit-Code. Mit den vorhandenen Prädikaten ließ sich „leer“ nicht ausdrücken; der Versuch `Not(LastOutputContains(""))` ist immer falsch, weil jeder Text den leeren Text enthält. Ein Loch dafür wäre ein Modellaufruf für eine Ja/Nein-Frage. Das neue Prädikat ist statisch auswertbar und klein. Die Regel, dass jede Algebra-Änderung eine Messung braucht, greift erst ab der ersten Messung; davor gibt es nichts zu vergleichen.

### Warum Schritte so gezählt werden (D#14)

Zahlen, die Explain vor der Freigabe zeigt, müssen nachrechenbar sein, sonst sind sie Dekoration. Gezählt wird, was etwas tut: jede Blattoperation einmal, Kontrollknoten gar nicht. Ein Loch zählt seinen Modellaufruf plus sein Budget, damit Modellaufrufe im Budget sichtbar sind. Abgelehnte Fills tun nichts; sie zählen gegen das Versuchslimit, nicht gegen Schritte.

### Warum Gelaufenes gelaufen bleibt (D#15)

Ein Fill kann zur Laufzeit scheitern, obwohl er Tor 2 bestanden hat, etwa wenn `expectedOld` nicht passt. Zurückrollen hieße, Effekte rückgängig zu machen, die die Engine nicht vollständig kennt. Stattdessen bekommt das Modell den Fehler als Handlungsanweisung und füllt neu, im selben Budget. Das Log zeigt beide Versuche. `Done` in einem Fill beendet nur das Loch; sonst könnte ein Loch den ganzen Flow abkürzen, den der Mensch freigegeben hat.

### Warum Replay ohne Dateisystem trotzdem den Endzustand prüft (D#16)

Replay spielt Ereignisse ab, ohne Dateisystem und ohne Modell; das macht es schnell genug für Property-Tests. „Gleicher Endzustand“ heißt dann: Die aufgezeichneten Schreibvorgänge, auf eine frische Kopie des Fixtures angewandt, ergeben denselben Dateibaum wie der echte Lauf. So prüft man, dass das Log vollständig ist: Fehlt ein Schreibvorgang, weicht der Baum ab.

### Warum check.sh den Ausgang des Laufs bekommt (D#17)

Bei den meisten Evals sagt der Dateibaum, ob die Aufgabe gelöst ist. Bei Eval 5 nicht: Dort ist Erfolg, dass nichts passiert und der Lauf mit einer Frage oder einem Abbruch endet. Ein unveränderter Baum allein könnte auch heißen, dass die Engine abgestürzt ist. Deshalb bekommt jedes Prüfskript neben dem Arbeitsverzeichnis den Ausgang des Laufs.

### Warum MoveFile, aber kein Löschen (D#18)

Dateien umbenennen ist eine der häufigsten Aufräumarbeiten, und mit Anlegen und Ändern allein geht es nicht: Die alte Datei bliebe liegen. Verschieben ist harmlos, solange nichts überschrieben wird; deshalb darf das Ziel nicht existieren. Löschen dagegen vernichtet Inhalt, und ein Fehler wäre nur aus einem Backup zu beheben. In v1 löscht der Mensch.

### Warum der Fingerprint die Herkunft ignoriert, der Hash nicht (D#19)

Der Fingerprint beantwortet: Planen Modell und Mensch dasselbe? Dafür zählt nur die Struktur, nicht, wer sie geschrieben hat. Der Hash beantwortet: Ist das genau der Term, den der Mensch freigegeben hat? Dafür zählt alles, was Explain zeigt, auch die Herkunft. Und weil auch ein Plan ein Modellaufruf ist, aber kein Fill, heißt die Kennung callId statt fillId.

### Warum If und RepeatUntil Operationen der Algebra sind (D#20)

Applicative kann Schritte nebeneinanderstellen, aber nicht abhängig von einem Ergebnis den einen oder den anderen nehmen. Monad könnte das, würde aber Funktionen in den Term bringen, und Funktionen lassen sich weder prüfen noch serialisieren. Die Lösung ist, Verzweigung und Wiederholung selbst zu Operationen zu machen, die beide Möglichkeiten als Werte bekommen. Wer prüft, schätzt oder erklärt, sieht beide Zweige; nur wer ausführt, wählt einen. Für Workflow-Leser: das ist ein Gateway in der Prozessdefinition, kein Code im Task. Damit die Bedingungen eindeutig sind, ist festgelegt, worauf sich „das letzte Ergebnis“ bezieht, und DryRun lehnt Bedingungen ab, die nie wahr werden können.

### Warum Shell-Befehle über Präfixe abgeschwächt werden (D#21)

`git` mit beliebigen Argumenten ist mehr Recht als `git status`. Ein inneres Loch soll also `git status` bekommen dürfen, wenn das äußere `git` hat. Eine reine Mengenprüfung sieht das nicht, weil die beiden Einträge verschieden sind. Die Regel vergleicht deshalb Präfixe: Je länger die Pflicht-Argumente, desto enger das Recht. Und ein Loch, das Shell darf, aber keinen einzigen Befehl, kann nichts davon nutzen; es ist unlösbar beschränkt und wird abgelehnt.

### Warum Flows Parameter nur nach dem Parsen bekommen (D#22)

Ein Release-Flow braucht die Versionsnummer, die erst beim Start bekannt ist. Würde man sie als Text ins JSON setzen, könnte ein präparierter Wert neue Knoten einschleusen, genau die Art Lücke, die SQL-Injection heißt. Deshalb wird erst decodiert und dann nur in Textfeldern ersetzt, und jeder Wert muss zu einem deklarierten Muster passen. Die Hülle des Flows steht in der Datei selbst und darf die Hülle des Runners nur verkleinern.

### Warum kanonisches JSON nach RFC 8785 (D#23)

Der Hash bindet die Freigabe an einen Term. Ergibt derselbe Term zwei verschiedene Hashes, weil Schlüssel anders sortiert oder Zeichen anders maskiert sind, ist die Bindung wertlos. RFC 8785 legt das eindeutig fest. Weil der Term keine Kommazahlen enthält, bleibt davon wenig übrig, was schiefgehen kann; Tests gegen die Beispiele aus dem RFC sichern den Rest.

### Warum der Codec von Hand geschrieben ist und das Modell kein origin setzt (D#26)

Ein abgeleiteter Codec übersetzt treu, was im JSON steht, auch ein `"origin": "Human"`, das ein Modell in seinen Plan schreibt, um wie ein menschlicher Flow auszusehen. Deshalb kennt das Schema für das Modell kein origin, und der Decoder für Modellantworten setzt es selbst; steht doch eines darin, ist die Antwort ungültig. Von Hand geschrieben ist der Codec aus zwei weiteren Gründen: Er soll schon beim Lesen ablehnen, was die Hülle nicht erlaubt oder zu tief verschachtelt ist, und er soll Mengen immer in derselben Reihenfolge schreiben, damit derselbe Term immer denselben Hash hat. Das sind etwa zweihundert Zeilen ohne Magie.

### Warum ein Befehl ohne Pfad das ganze Arbeitsverzeichnis berührt (D#27)

`grep -r token` ohne Pfadangabe durchsucht alles, `git status` meldet alle Dateien, `sbt test` liest den ganzen Build. Würde die Prüfung nur Pfade ansehen, die ausdrücklich dastehen, könnte ein Loch mit `pathPrefix = docs/` einfach den Pfad weglassen. Deshalb zählt das Weglassen als „.“, und solche Befehle sind nur in Hüllen erlaubt, die das ganze Arbeitsverzeichnis umfassen.

### Warum fromTerm keinen Constraint braucht (D#28)

Solange If und RepeatUntil eigene Knoten außerhalb der Algebra waren, musste fromTerm Schritte selbst kombinieren und brauchte dafür Applicative. Seit D#20 sind alle Kontrollknoten Operationen der Algebra, und fromTerm ruft nur noch Operationen auf. Das ist die einfachste Form der Regel „keine Constraints in der Algebra“: Auch die Brücke zwischen Term und Programm braucht keinen.

### Warum Execute nur unter Linux läuft (D#29)

Die Engine startet Programme ohne Shell, Argumente als Liste. Unter Linux ist das der Normalfall. Unter Windows nicht: Ein Skript für bash lässt sich gar nicht direkt starten, und eine .bat- oder .cmd-Datei startet Java heimlich über cmd.exe, die Sonderzeichen in Argumenten deutet. Genau das, was die Engine verbietet, passierte dann unbemerkt. Statt eine Übersetzungsschicht für Windows zu bauen, läuft Execute nur unter Linux, auf dem eigenen Rechner im WSL. Prüfen, erklären und freigeben geht weiter überall. Die strengeren Pfadregeln gelten trotzdem auf allen Plattformen: Ein Flow soll nicht auf einem System gültig und auf einem anderen gefährlich sein.

### Warum ein Loch mehr sehen als tun darf, und warum das im Explain steht (D#30)

Prüfung und Sicht folgen verschiedenen Regeln. Für die Prüfung gilt ein Befehl ohne Pfad als Zugriff auf alles (D#27), damit ein Loch sich keinen Zugriff erschleicht. Für die Sicht zählen nur ausdrücklich genannte Pfade (D#9), sonst sähe das Diagnose-Loch nicht einmal den roten Test, den es erklären soll. Das lässt eine Lücke: Die Ausgabe eines Testskripts kann Inhalte von überall enthalten. Solche Befehle laufen aber nur auf oberster Ebene, im freigegebenen Plan. Damit der Mensch auch den Datenfluss freigibt und nicht nur die Schritte, nennt Explain unter jedem Loch die früheren Ergebnisse, die es sehen wird. Für Workflow-Leser: Das ist die Datenzuordnung eines Tasks, sichtbar in der Prozessdefinition.

### Warum die Korrekturen aus dem Review (D#31)

Ein Reviewer hat versucht, die Tore zu umgehen, und es in mehreren Fällen geschafft. Am lehrreichsten: Eine Hülle, die nur `git status` und das Ändern von Dateien erlaubt, reichte aus, um beliebige Programme zu starten. Der Fill schreibt eine Zeile in `.git/config`, und git führt sie beim nächsten `git status` aus. Das Tor hatte nichts falsch gemacht; es kannte nur nicht alle Wege, auf denen eine Datei zu Code wird. Ebenso grundsätzlich: Zahlen dürfen nicht überlaufen (eine Schleife mit 2^64 Durchläufen ergab „0 Schritte“), und was Explain dem Menschen zeigt, darf das Modell nicht gestalten können, sonst gibt der Mensch frei, was ihm untergeschoben wurde. Die Regel dahinter ist dieselbe wie bei den Pfaden: lieber eine Sache zu viel verbieten (`..`, Backslash, Steuerzeichen, `.git`) als eine Lücke lassen, die nur auf einer Plattform oder nur mit einem bestimmten Programm offen ist.

### Warum Execute und Replay denselben Kern haben (D#32)

Replay soll beweisen, dass ein Lauf vollständig aufgezeichnet ist und sich ohne Dateisystem und Modell wiederholen lässt. Hätte Replay einen eigenen Interpreter, der das Log nur nachliest, bewiese die Gleichheit wenig: Zwei Programme können sich in denselben Fehlern einig sein. Deshalb läuft beides durch denselben Kern, und nur die Anschlüsse nach außen (Werkzeuge, Modell, Mensch, Uhr) sind echt oder aus dem Log. Weicht der Kern beim Abspielen von der Aufzeichnung ab, ist entweder das Log unvollständig oder der Kern nicht deterministisch, und beides soll ein Test finden. Die Uhr ist dabei ein Anschluss wie jeder andere: Sie wird an festen Stellen gefragt, und Replay antwortet an genau der Stelle „abgelaufen“, an der der echte Lauf abgebrochen ist.

### Warum ein Lauf so endet, wie er endet (D#33)

Ein Loch hat ein Modell, das auf Fehler reagieren kann; der feste Teil des Plans hat keins. Scheitert dort ein Schritt, gibt es niemanden, der ihn reparieren könnte, also bricht der Lauf ab, statt zu raten. Ein Exit-Code ungleich 0 ist dagegen kein Fehler, sondern ein Ergebnis, auf das der Plan mit If reagieren kann (Tests sind rot). Fragt ein Lauf den Menschen, und niemand antwortet, endet er mit „ask“: ein ehrlicher Zustand für einen Lauf ohne Menschen daneben, und genau das, was Eval 5 erwartet.

### Warum Schritte zur Laufzeit selbst gezählt werden (D#34)

DryRun rechnet vorab eine obere Schranke. Execute verlässt sich nicht darauf, sondern zählt selbst und kappt hart; zwei unabhängige Zählungen fangen sich gegenseitig auf, wie der Überlauf aus dem ersten Review gezeigt hat. Ein Loch in einer Schleife bekommt in jedem Durchlauf sein Budget neu, so wie DryRun es auch berechnet hat; sonst würde ein freigegebener Plan an einer Stelle scheitern, die der Mensch für erlaubt hielt.

### Warum das Record-Log so aussieht (D#35)

Das Log ist das Beweisstück eines Laufs: Wer später prüfen will, was passiert ist, braucht den freigegebenen Term, seinen Hash, jede Modellantwort und jeden Syscall mit dem Pfad, an dem er wirklich stattfand. Deshalb trägt jedes Werkzeugergebnis die aufgelösten Pfade, nicht die, die im Term standen; ein Symlink kann beides auseinanderführen. Das Format hat eine Version, weil es sich mit der Algebra ändern wird; ein altes Log wird abgelehnt statt falsch gelesen.

### Warum Shell-Aufrufe Zusätze bekommen, die das Modell nicht sieht (D#36)

Programme wie git lesen Konfiguration, die Programme starten kann, und suchen ihr Repository auch oberhalb des Arbeitsverzeichnisses. Beides ist im Alltag praktisch und hier gefährlich. Die Engine setzt deshalb feste Umgebungsvariablen und Optionen, die diese Wege schließen, und gibt Kindprozessen nur eine kleine, bekannte Umgebung mit; ein API-Schlüssel in der Umgebung des Runners erreicht so kein Skript im Workspace. Das Modell sieht diese Zusätze nicht, weil sie nicht verhandelbar sind.

### Warum Dateien so geschrieben werden (D#37)

Die Prüfung muss gegen das laufen, was das Dateisystem tatsächlich tut, nicht gegen den Pfadtext. Ein Symlink auf `.git` umging im zweiten Review die Sperre aus D#31, ein Hardlink ließ `EditFile` eine Datei außerhalb ändern. Deshalb wird der aufgelöste Pfad geprüft, und Änderungen werden als neue Datei geschrieben und über die alte geschoben: Das trennt einen Hardlink, statt durch ihn hindurchzuschreiben, und hinterlässt bei einem Absturz keine halbe Datei.

### Warum Explain den vollen Hash zeigt (D#38)

Die Freigabe soll an genau den Term gebunden sein, den der Mensch gesehen hat. Eine Kurzform aus zehn Zeichen ist zum Lesen gut, zum Binden zu schwach, und im Test hatte der Auto-Approver den Hash gar nicht aus der Anzeige gelesen; die Freigabe konnte dort nie scheitern. Jetzt steht der volle Hash in einer eigenen Zeile, und genau diese Zeile wird bestätigt.

### Warum nur manche Befehle ohne Pfad pfadlos sind (D#39)

`grep -rn password` ohne Pfad liest den ganzen Workspace. Galt die Ausgabe als pfadlos, sah sie jedes Loch, auch eines, das nur REPORT.md schreiben darf; der Filter aus D#9 war damit umgangen. Ganz streng (D#27, alles ohne Pfad ist ".") sähe aber das Diagnose-Loch in Eval 6 den roten Test nicht und das Changelog-Loch im Release-Flow die Commits nicht. Die Grenze verläuft deshalb nach dem Inhalt der Ausgabe: Programme, die Workspace-Code ausführen, liefern das Ergebnis, um das es im Flow geht (Testausgabe), und `git log` liefert Metadaten, keine Dateiinhalte. Beide Listen stehen in der Prüfkonfiguration, nicht im Code.

### Warum der CheckedTerm seine Prüfkonfiguration trägt (D#40)

Explain und Execute bekamen die Prüfkonfiguration bisher getrennt vom geprüften Term. Nichts verhinderte, dass der Runner Explain mit anderen Policies aufruft als DryRun; dann zeigt die Freigabe etwas anderes, als geprüft wurde. Liegt die Konfiguration im CheckedTerm, ist es dieselbe, weil es keinen anderen Weg gibt. Gleiches gilt für die Flow-Parameter, die Explain anzeigt.

### Warum Explain Inhalte vollständig zeigt (D#41)

Eine Kürzung ist ein Versteck: Hinter sechzig Leerzeichen stand im Review ein `Process("curl …")`, und der Mensch sah nur „harmlos…“. Was ein statischer Schritt schreibt, gibt der Mensch frei, also muss er es lesen können. Der Rahmen „│ “ vor jeder Zeile sorgt dafür, dass ein Inhalt nicht wie eine Zeile des Explain aussehen kann.

### Warum Explain „kann sehen“ sagt (D#42)

Explain kennt nur die Pfade im Term, Execute die aufgelösten; ein Symlink kann einen Pfad unter `docs/` nach außen führen, dann fällt das Ergebnis zur Laufzeit aus der Sicht heraus. Execute zeigt also höchstens, was Explain angekündigt hat. Die Formulierung sagt genau das.

### Warum bekannte Grenzen bleiben (D#43)

Die verbleibenden Lücken (Zeitfenster zwischen Prüfung und Syscall, Lesen über vorhandene Hardlinks, Kindprozesse nach dem Ende, Replay ohne Schreibvorgänge der Kindprozesse) brauchen entweder Workspace-Code, den der Mensch ohnehin freigibt (D#12), oder Mittel des Betriebssystems wie Namespaces oder einen Container. Das wäre ein eigenes Vorhaben. Sie stehen hier, damit niemand mehr Sicherheit annimmt, als es gibt.

### Warum Explain eine konfigurierte Verbotsliste zeigt (D#24)

Was nicht erlaubt ist, ist unendlich. Für den Menschen beruhigend ist aber die Zeile „git push ist nicht dabei“. Welche Befehle für ihn wichtig sind, weiß die Engine nicht; das steht deshalb in der Konfiguration. Steht ein solcher Befehl doch in der Allowlist, ist das eine Warnung wert.

### Warum Record und Replay

Die zentrale Invariante gilt auch für Löcher, und Löcher werden vom Modell gefüllt, das beliebiges liefern kann. Man kann diese Invariante nur beweisen, wenn man beliebige Modellantworten einspielen kann, ohne ein Modell zu haben. Record zeichnet jeden Lauf als Ereignisfolge auf (Werkzeugergebnisse und Modellantworten), Replay spielt die Folge ohne Dateisystem und ohne Modell ab, und AdversarialFill erzeugt Antworten, die absichtlich ausbrechen wollen. Zusammen ergibt das einen Property-Test über hunderte zufällige Terme in Millisekunden. Nebenbei ist ein Agentenlauf damit Event Sourcing, mit allem, was man darüber weiß: Versionierung der Ereignisse, wenn sich die Algebra ändert, und der Hash des freigegebenen Terms als erstes Ereignis.

### Warum Evals vor Code

Die zentrale Komponente ist nichtdeterministisch. Derselbe Lauf schlägt in sieben Prozent der Fälle fehl, und man weiß nicht, welche. Intuition über Prompt- oder Algebra-Änderungen täuscht systematisch: Eine Änderung, die drei Fälle repariert, bricht fünf andere. Deshalb gibt es Aufgaben mit bekanntem Ergebnis, bevor es Code gibt, und deshalb zählt nur eine Serie über n Läufe als Messung (M#). Ohne Evals kann man am Ende nicht sagen, ob die Engine funktioniert oder nur kompiliert.

### Warum Werkzeugbeschreibungen Prompts sind

Jede Operation der Algebra bekommt einen Namen und eine Beschreibung, die das Modell sieht. Diese Beschreibung ist die Abbildung zwischen zwei Repräsentationsräumen: der formalen Semantik des Interpreters und dem Bedeutungsraum, den das Modell aus seinem Training mitbringt. Trifft der Name ein Konzept, das das Modell kennt, wählt es die richtige Operation; schneidet die Algebra Konzepte anders als das Modell, wird sie falsch benutzt, egal wie sauber ihre Typen sind. Das Design der Algebra ist deshalb zur Hälfte Typentheorie und zur Hälfte die Frage, wie das Modell die Welt ohnehin zerlegt. Das ist messbar: zwei Algebren gleicher Ausdruckskraft, verschiedene Zerlegungen, Erfolgsquote vergleichen.

### Warum origin pro Knoten

Was das Modell stabil plant, kann zum festen menschlichen Flow werden; was es variabel plant, bleibt Loch. Mit `origin` an jedem Knoten und dem Fingerprint über viele Pläne liefert die Engine die Daten dafür selbst: Wenn das Doku-Loch bei zehn Releases jedes Mal dasselbe Muster liefert, zieht man das Muster in den Flow und verkleinert das Loch. Das ist dieselbe Bewegung, die in der Branche Scaffolding nach innen in die Modelle wandern lässt, nur in die andere Richtung: vom Modell in die Prozessdefinition. Der Flow wird mit der Zeit starrer, wo das Modell verlässlich ist, und bleibt offen, wo es nicht ist.

### Warum ein statischer Baum und keine Variablen (D#5)

Ein JSON-Term kann keine Scala-Funktionen enthalten, also keine Fortsetzungen. Zwei Auswege: eine kleine Ausdruckssprache mit Variablen im Term, oder jede Datenabhängigkeit als Loch. Für v1 das zweite, weil es die Sprache klein hält, die Algebra nur `Applicative` verlangt und jede Datenabhängigkeit damit automatisch durch ein Tor läuft. Der Preis sind mehr Modellaufrufe. Variablen bleiben Kandidat für v2, wenn die Evals zeigen, dass zu viele Löcher nur Daten durchreichen.

### Warum das Schema von Hand (D#7)

`Term` ist ein rekursives ADT, und circe liefert Codecs, aber kein JSON-Schema. Generatoren scheitern an der Rekursion oder erzeugen rekursive `$defs`, die strict nicht annimmt. Elf Term-Knoten und sechs Prädikate sind etwa hundert Zeilen Schema pro Ebene, ohne Abhängigkeit; wie das mit strict und Rekursion zusammengeht, steht bei D#10. Der Test bleibt: Jeder generierte Term validiert gegen das Schema und überlebt den Codec-Roundtrip.

### Das Bild für Außenstehende

Der Term ist eine Partitur. Execute spielt sie, Explain liest sie vor, Cost zählt die Takte, DryRun prüft, ob alle Noten im Tonumfang der Instrumente liegen, Replay spielt die Aufnahme ab. Die Partitur ändert sich nie.
