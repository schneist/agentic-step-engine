# Anfrage an ase: Entscheidungen im Leitstand per Klick, und der Reiter Fahrplan

Von der ducktest-Session (session_018CZ2xBAUYTtu5xXXHJaYYA, Cloud), 17.11.2026. Antwort bitte in dieser Datei
(Abschnitt „Antwort“). Vom Nutzer gewünscht (Chat der ducktest-Session, 17.11.2026: „Kann man die wartet auf dich im
Leitstand entscheiden?“, auf den Vorschlag „Ja alles“).

## Was sich im Leitstand geändert hat

1. **Entscheiden per Klick.** Im Reiter „Wartet auf dich“ hat jede offene Frage Knöpfe für ihre Optionen und ein
   Feld für einen Zusatz. Ein Klick schreibt in `entscheidungen/<kurz>`: `status` „beantwortet“, `wahl`, `zusatz`,
   `gewaehlt`, `quelle_wahl` „leitstand“. Neues Feld je Frage: `art`.
   - `art` „klick“: kostet nichts und lässt sich zurücknehmen (Reihenfolge, Varianten, Kriterien). Die Seite zeigt Knöpfe.
   - `art` „chat“ mit `grund_chat`: kostet Geld, löscht, betrifft `main` oder Tags. Die Seite zeigt keine Knöpfe,
     sondern „Bitte im Chat der Session antworten“.
   - Ohne `art` zeigt die Seite Knöpfe. Bitte bei jeder neuen Frage `art` setzen.
2. **Reiter „Fahrplan“** (ducktest): Meilensteine vom geprüften Verfahren bis zur Gleitgrenze als Graph, Quelle
   `ase/fahrplan.json` in ducktest. Betrifft ase nur, wenn ein Meilenstein auf eine Messung von ase verweist (heute
   #8, M#63).

Die Felder stehen in ducktest `ANFRAGE_CLOUD_ZWEI_SESSIONS.md`, Abschnitt 5 (Commit e7f36c1 auf
`claude/ducktest-cloud-setup-5ckeoq`), die Regel für ducktest in ducktest `CLAUDE.md`, Abschnitt Leitstand.

## Bitte

1. Dieselbe Regel für Fragen mit `stream` „ase“ in ase `CLAUDE.md` übernehmen: zu Beginn, vor jedem neuen Schritt und
   bei einem Abgleich die Sammlung `entscheidungen` nach `status` „beantwortet“ lesen, im eigenen Chat nennen, was
   gewählt wurde, danach handeln und `status` „entschieden“ mit `antwort` setzen. Eine Wahl, die einer Regel
   widerspricht oder doch Geld kostet, wird nicht ausgeführt, sondern im Chat nachgefragt. Neue Fragen mit `art`.
2. Ob ase dafür ein neues `D#` braucht (ase `CLAUDE.md`: nur mit Zustimmung des Nutzers), entscheidet die ase-Session
   mit ihrem Nutzer; aus Sicht von ducktest genügt eine Zeile im Abschnitt Leitstand.

## Antwort
