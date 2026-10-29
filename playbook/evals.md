# Protokoll für Eval-Läufe

## Wann

- Nach jeder Änderung an Algebra, Hüllen, Werkzeugbeschreibungen oder Modelladapter.
- Vor jedem D#, das das Modellverhalten betrifft (Vergleich vorher/nachher).

## Wie

1. Commit-Hash notieren. Nur gegen einen sauberen Stand messen.
2. Fixture aus `evals/<nr>/` in ein frisches Arbeitsverzeichnis kopieren; nie in-place.
3. n = 10 Läufe pro Aufgabe (Live-Modus). Deterministischer Modus (Eval 6) läuft in `sbt test`.
4. Pro Lauf erfassen: Erfolg laut Prüfskript, Schritte, Modellaufrufe, Ablehnungen an Tor 1 und Tor 2, Ask erreicht, Tokens (Eingabe/Ausgabe), Fingerprint des Plans, Fingerprint jedes Fills, Code-Hinweise im Explain des Plans (D#62).
5. Aggregieren: Erfolgsquote, Mittel und Streuung der Tokens, Anteil der Pläne mit Referenz-Fingerprint (wo ein Flow in `flows/` existiert), Anteil der Pläne mit Code-Hinweis.

`evals/series.sh` macht das für eine Eval, `evals/harness.sh <n> <ausgabe> --config <datei>` für alle sechs mit derselben Konfiguration (D#64).

## Wie dokumentiert

Eine Zeile pro Serie in `findings.md`, Abschnitt Messungen:

```
- M#<n> <datum> Eval <nr> · Modell <name> · Commit <hash> · n=10 · Erfolg 8/10 · Tor1 2 · Tor2 3 · Ask 1 · Tokens ø 18.4k · Referenz-Fingerprint 6/10 · Code-Hinweis 0/10 · Anmerkung
```

Rohdaten (Record-Logs) bleiben lokal in `record/`, nicht im Repo. Wer die Zahl später prüfen will, braucht Commit, Modellname und Fixture; alles drei steht in der Zeile.

## Was nicht zählt

Ein Einzellauf. Ein Lauf gegen einen unsauberen Stand. Ein Lauf, bei dem das Fixture vorher verändert wurde.
