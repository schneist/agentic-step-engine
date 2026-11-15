# Anfrage an ase: Messung der Prüfkette mit dem Schritt segelmodell (V146)

Von der ducktest-Session (session_018CZ2xBAUYTtu5xXXHJaYYA, Cloud), 15.11.2026. Antwort bitte in dieser Datei
(Abschnitt „Antwort“). Vom Nutzer gewünscht (Chat der ducktest-Session, 15.11.2026: „Ja so angehen“ auf den Vorschlag,
das Segelmodell in die Kette zu nehmen, mit Messung über n Läufe).

## Anlass

ducktest hat ein Segelmodell (`segelmodell.py`, Befund 21, nach ORC VPP Documentation 2023, Kap. 5). Es ist seit
**V146** ein Schritt der Prüfkette. Der Flow hat sich damit geändert, und nach ducktest `CLAUDE.md` (Verbote) braucht
das eine Messung über n Läufe in ase (M#); ein Einzellauf zählt nicht.

## Was sich geändert hat (ducktest, Zweig `claude/ducktest-cloud-setup-5ckeoq`, Commit 8769e4d = V146)

- `ase/pruefkette.json`, `ase/pruefkette-vergleich.json`: ein Schritt `python3 ase/kette.py lauf segelmodell.py` nach
  `hydrostatik_reserve.py`; im Ziel der drei Agentenschritte zwei Kennzahlen mehr („Angleiten TWA 45 Rumpf günstig kn“,
  „Angleiten TWA 45 Rumpf ungünstig kn“). Worst case 39 statt 38 Schritte (maxSteps 40).
- `ase/kennzahlen.txt`, `ase/kette.py` (KETTE, Muster ANGLEITEN), `ase/check-bericht.sh`, `ase/stand.py`: die zwei Zeilen.
- `ase/ase-fassung.txt`: 8851c00 (vorher c1c8420; Lehren aus dem Umzug, D#110).
- `segelmodell.py` neu, rund 10 s Laufzeit, keine Netz- oder Dateizugriffe außer den Imports der Kette.

## Einzellauf hier (zählt nicht, nur zum Vergleich)

Prüfkette V146 gegen V145, `v=146 vorher=145 prev=97`, `pruefkette-vergleich.json`, ase 8851c00, Cloud-Konfiguration
`~/ase-cloud/ase.json`. Plan-Hash 78894a…c265. Exit 0, 27 Schritte, 1 Modellaufruf, 69,0k Tokens, Abnahme im ersten
Durchlauf. `check-bericht.sh … 146 145`: „ok (17 Kennzahlen, Stand V146)“. Die 15 bisherigen Kennzahlen gleich
`PRUEFUNG_V145.md`, neu 11.5 und 14.8 kn. Bericht: ducktest `PRUEFUNG_V146.md` (47ca2f7); Lauf in S3 unter
`laeufe/cloud/cloud-v146/`.

## Bitte

1. **Messung n=5** (ducktest `ase/series.json`) wie M#51: je ein frischer Klon von ducktest auf 8769e4d mit lokalem
   Tag V146 (der Tag steht noch nicht auf GitHub, weil diese Session nur auf ihren Zweig pushen darf; der Nutzer legt
   ihn an), `pruefkette-vergleich.json`, `v=146 vorher=145 prev=97`, mit der Fassung aus `ase/ase-fassung.txt`
   (8851c00).
2. **Kriterien, festgelegt vor der Messung:** in jedem Lauf Exit 0, `check-bericht.sh` ok (17 Kennzahlen),
   „Kette sauber“, die 15 bisherigen Kennzahlen gleich `PRUEFUNG_V145.md`, die zwei neuen 11.5 und 14.8. Dazu wie
   üblich Abnahme im ersten Durchlauf, Schritte, Fills und Tokens gegen M#51.
3. Ergebnis als M# in ase `findings.md` und hier unter „Antwort“. Erst danach geht V146 per PR nach `ase-pruefkette`
   (mit dem „ja“ des Nutzers).

## Antwort
