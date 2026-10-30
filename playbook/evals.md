# Protokoll für Eval-Läufe

## Wann

- Nach jeder Änderung an Algebra, Hüllen, Werkzeugbeschreibungen oder Modelladapter.
- Vor jedem D#, das das Modellverhalten betrifft (Vergleich vorher/nachher).

## Wie

1. Nur gegen einen sauberen, committeten Stand messen. `evals/series.sh` bricht sonst ab; mit `ALLOW_DIRTY=1` läuft die Serie trotzdem, zählt aber nicht.
2. Fixture aus `evals/<nr>/` in ein frisches Arbeitsverzeichnis kopieren; nie in-place. series.sh macht das je Lauf.
3. n je Eval: `<eval>/series.json` mit `{"n": 5}`, ohne die Datei 10. Live-Modus; der deterministische Modus (Eval 6) läuft in `sbt test`.
4. Pro Lauf erfassen: Erfolg laut Prüfskript, Schritte, Modellaufrufe, Ablehnungen an Tor 1 und Tor 2, Ask erreicht, Tokens, Dauer, Fingerprint des Plans, Code-Hinweise im Explain des Plans (D#62), Ausgangsstand (D#78). series.sh schreibt run.json, `ase run` summary.json.
5. Aggregieren: `ase series end` baut daraus den Datensatz (D#77) und druckt die M#-Zeile: Erfolgsquote mit 95-%-Intervall, Tokens und Dauer als Mittel, Median und Spanne, Anteil der Pläne mit Referenz-Fingerprint (wo ein Flow in `flows/` existiert), Anteil der Pläne mit Code-Hinweis.

`evals/series.sh <eval> <n> <ausgabe> [--config C] [--flow F] [--reference F] [--parallel]` macht das für eine Eval, `evals/harness.sh <n> <ausgabe> --config <datei>` für alle sechs mit derselben Konfiguration (D#64). Schlüssel und Bedingungen hält `ase series begin` vor dem ersten Lauf fest: Fassung von Aufgabe oder Flow, Hülle, Fixture als Tree-Hash, check.sh, Anbieter, Modell und Effort aus der Konfiguration, Commit, geänderte Dateien, n und Parallelität.

## Wie dokumentiert

1. Datensatz übernehmen: `<ausgabe>/series.json` nach `measurements/M<n>.json` kopieren und committen. Er enthält Kennzahlen und Hashes, keine Inhalte aus Record-Logs und keine Pfade.
2. Zeile rendern: `ase series show measurements/M<n>.json` und die Zeile in `findings.md`, Abschnitt Messungen, eintragen. Von Hand kommt nur die Anmerkung dazu, hinter dem letzten `·`.

```
- M#<n> <datum> Eval <name>, Aufgabe task.md · Modell <name>, effort <stufe>, über <anbieter> · Commit <hash> · n=10 · Erfolg 8/10 (95 %: 49 bis 94 %) · Tor1 2 · Tor2 3 · Ask 1 · Tokens ø 18.4k (Median 18.1k, 16.0k bis 21.3k) · Dauer Median 42 s, 35 s bis 61 s · Referenz-Fingerprint 6/10 · Code-Hinweis 0/10 · Schlüssel 1a2b3c4d · zählt · Anmerkung
```

Rohdaten (Record-Logs, Laufverzeichnisse) bleiben lokal in `record/`, nicht im Repo.

## Wann vergleichbar

Zwei Serien sind vergleichbar, wenn ihr Schlüssel gleich ist (Fassung, Hülle, Fixture, Prüfskript, Anbieter, Modell, Effort), ihr Datensatz dieselbe Version hat und beide zählen. Dann unterscheidet sie im Wesentlichen der ase-Commit. Sonst ist es kein Vergleich, sondern eine neue Basis.

`ase compare BASIS KANDIDAT` prüft das und nennt, was sich unterscheidet. Für Erfolg, Tokens und Dauer urteilt es „besser“ oder „schlechter“ nur, wenn sich die Intervalle oder Spannen nicht überlappen; sonst „Rauschen“. Rauschen ist der Normalfall: 10 von 10 heißt nur, dass die wahre Quote über etwa 72 % liegt, 5 von 5 nur über etwa 57 %. Die Dauer wird nur bei gleicher Parallelität verglichen.

## Was nicht zählt

`ase series end` prüft das und schreibt es in den Datensatz; eine Serie, die nicht zählt, wird markiert, nicht weggelassen.

- Ein Lauf gegen einen unsauberen Stand (`DirtyTree`).
- Weniger Läufe als verlangt, also auch ein Einzellauf (`TooFewRuns`).
- Andere Läufe als angekündigt, etwa ausgelassene (`RunsNotAsPlanned`).
- Ein Fixture, das sich während der Serie geändert hat (`FixtureChanged`), oder ein Lauf, der nicht mit dem Fixture begann (`StartNotFixture`).
- Läufe aus einer anderen Fassung der Aufgabe oder des Flows in derselben Serie (`MixedKeys`).
