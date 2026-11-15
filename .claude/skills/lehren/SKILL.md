---
name: lehren
description: Lehren am Ende einer Sitzung oder eines Schritts aus SPEC.md ableiten und festhalten (L# in findings.md, je Lehre genau eine Folge). Aufrufen am Ende jeder Sitzung, nach jedem Schritt aus SPEC.md, und wenn der Nutzer nach Lehren, Learnings, Retro oder „was lernen wir“ fragt.
---

# Lehren ableiten (D#108, zentral D#109)

Ziel: Was in dieser Sitzung anders lief als erwartet, wird zu einer Änderung, die beim nächsten Mal greift. Eine
Lehre ohne Folge ist nur eine Erinnerung.

## 0. Lehren des Nutzers ableiten

Der Nutzer trägt Lehren, die ihm auffallen, im Leitstand ein (<https://claude.ai/artifact/UNWXg1fbSdzGRgb3VueAWV>,
Reiter „Lehren“). Zuerst mit `ArtifactData` die Sammlung `lehren` lesen: Einträge mit `quelle: "nutzer"`,
`status: "neu"` und `bereich` gleich `ase` oder `beide`. Jeden davon wie eine Abweichung aus Schritt 1 behandeln
(Beleg suchen, Ursache, Folge, Schritte 2 bis 4) und danach den Eintrag des Nutzers aktualisieren, auf seine Version
gepinnt: `ursache`, `folge`, `status` (`abgeleitet`, `umgesetzt` oder `keine Folge`) und `abgeleitet_in`
(etwa `ase L#7`). Betrifft er `beide`, leitet jede Session ihren Teil ab; wer zuerst schreibt, setzt `abgeleitet_in`,
die andere hängt ihren Teil an (`ase L#7; ducktest L#3`). Ohne Beleg im Repository: zurückfragen statt raten.

## 1. Abweichungen sammeln

Aus dem Chat dieser Sitzung, `git log` seit ihrem Beginn in ase (und in ducktest, wenn dort gearbeitet wurde) und dem
Leitstand (`verlauf`, `entscheidungen`). Eine Abweichung ist alles, was nicht wie erwartet lief:

- ein Lauf, Test oder Skript scheiterte, oder meldete Erfolg, obwohl ein Teil scheiterte
- eine Behauptung musste zurückgenommen oder berichtigt werden (eigene oder die einer anderen Session)
- Arbeit wurde doppelt getan, eine Nummer doppelt vergeben, ein Eintrag doppelt angelegt
- der Nutzer musste etwas von Hand übertragen, nachfragen oder korrigieren
- eine Rückfrage an den Nutzer brachte nichts Neues (wiederkehrendes „ja“ zur selben Art von Schritt)
- etwas hing an einem Rechner, einem Zugang oder einer Datei, die gerade nicht erreichbar war

Nichts erfinden: Jede Abweichung hat einen Beleg (Commit, Log, Datei, Leitstand-Eintrag, Zitat aus dem Chat).

## 2. Je Abweichung: erwartet, gekommen, Ursache

Die Ursache eine Ebene unter dem Symptom: nicht „AWS lehnte ab“, sondern „die Richtlinie stand im Repo, wurde aber
nie angewendet, und nichts prüfte die Wirkung“. Keine Schuld, keine Personen; es geht um Regeln, Werkzeuge und Abläufe.

## 3. Je Lehre genau eine Folge

Die stärkste Folge, die passt, in dieser Reihenfolge:

1. eine Prüfung, die es erzwingt (Test, Prüfung in einem Skript, Tor in einem Flow)
2. eine Änderung an einem Werkzeug oder Skript
3. eine Regel in `CLAUDE.md`
4. ein `O#` mit Issue, wenn es eine Entscheidung braucht; eine `ANFRAGE_*.md`, wenn es ducktest betrifft
5. bewusst keine Folge, mit Grund

Vorher `grep -n "^- L#" findings.md`: Gab es dieselbe Lehre schon, war die Folge damals zu schwach. Dann eine Stufe
stärker wählen und im neuen Eintrag auf den alten verweisen.

## 4. Festhalten

Je Lehre ein Eintrag im Abschnitt `## Lehren` von `findings.md`, fortlaufend nummeriert:

```
- L#<n> <JJJJ-MM-TT> <Kurztitel>. Anlass: … (Beleg). Ursache: … Folge: … (Commit, O#, Anfrage oder „keine, weil …“).
```

`knowledge/context.yaml` bekommt je Lehre eine Zeile `{ id: "L#<n>", type: lesson, … }`. Neue Begriffe ins Glossar.

Jede Lehre zusätzlich zentral in den Leitstand, Sammlung `lehren`, Dokument `ase:L<n>`: `quelle: "ase"`, `nr`,
`titel`, `anlass`, `ursache`, `folge`, `status`, `zeit`. Ändert sich der Stand einer Lehre später (eine angefragte
Folge ist umgesetzt), den Eintrag nachführen. Die Datei in git bleibt maßgeblich; der Leitstand sammelt beide Repos
und die Einträge des Nutzers an einer Stelle.

## 5. Vorlegen und umsetzen

Dem Nutzer kurz vorlegen: je Lehre ein Satz und die vorgeschlagene Folge. Nach seinem „ja“ umsetzen. Was nur Doku
und Werkzeuge ändert, darf nach D#107 ohne weitere Rückfrage nach `main`; ein neues `D#` und alles am Code nicht.
Im Leitstand ein Eintrag in `verlauf` („Lehren L#… bis L#…“).

## Grenzen

- Keine Lehre ohne Beleg, keine ohne Folge oder Begründung.
- Keine Schlüssel, Kontonummern, Personendaten oder Laufdaten im Volltext in den Einträgen.
- Höchstens sechs Lehren je Sitzung; das Wichtigste zuerst. Kleineres als eine Zeile „weitere“ im letzten Eintrag.
