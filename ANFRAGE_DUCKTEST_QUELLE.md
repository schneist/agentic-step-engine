# Anfrage an ase: Wertregel für das erste Positionsargument (`aws s3 sync`, Ernte-Flow in ducktest)

Von der ducktest-Session, 14.11.2026. Antwort bitte in dieser Datei (Abschnitt „Antwort“). Vom Nutzer gewünscht
(Chat der ducktest-Session, 14.11.2026: „Ja“ auf die Frage nach dieser Anfrage).

## Anlass

ducktest hat einen Flow, der die Ergebnisse einer CFD-Maschine aus S3 holt (ducktest Issue #10, Commit `e18cb17` auf
Zweig `ccr-5eda618c-jt47q6`, Dateien `ase/cfd/ernte.json`, `ase/cfd/ase-ernte.json`, `ase/cfd/envelope-ernte.json`,
Abschnitt „Ernte“ in `ase/README.md`). Der eine Aufruf, der etwas holt:

```
aws s3 sync --profile ducktest-ase --region eu-central-1 s3://ducktest-cfd-<konto>/49er_v14/ cfd_ernte/49er_v14 \
  --only-show-errors --no-progress
```

Die Argument-Policy dazu in `ase-ernte.json`:

```json
{"binary": "aws", "args": ["s3", "sync"], "flags": ["--only-show-errors", "--no-progress"],
 "valuedFlags": ["--profile", "--region"], "positional": "PatternThenPaths", "required": ["--profile", "--region"],
 "values": {"--profile": {"type": "Pattern", "regex": "^ducktest-ase$"},
            "--region": {"type": "Pattern", "regex": "^eu-central-1$"}}}
```

## Lücke

Die Quelle von `s3 sync` ist ein Positionsargument. Mit `Paths` wäre `s3://…` als Pfad geprüft und abgelehnt;
`prefixArgs` nimmt keinen Text mit „/“ (seit D#101 ein Werkzeugskript); mit `PatternThenPaths` geht das erste
Positionsargument **ungeprüft** durch (nachgesehen: `ArgPolicy.scala`, Fall `PatternThenPaths`, `positionals == 0`).
Nur das Ziel wird als Pfad im Arbeitsverzeichnis geprüft. Damit erlaubte die Policy auch:

- `aws s3 sync s3://<anderer-bucket>/… cfd_ernte/x`: Grenze ist nur die IAM-Richtlinie von `ducktest-ase`;
- `aws s3 sync ~/.aws cfd_ernte/x`: eine lokale Quelle, die die Sandbox einhängt (für `aws` ist das `~/.aws`), wird
  ins Arbeitsverzeichnis kopiert. Die Zugangsdaten lägen danach im Arbeitsverzeichnis, das ein Agentenschritt lesen
  und dem Modell zeigen kann (O#63).

In ducktest ist das heute nicht ausnutzbar: Der Term ist fest (origin Human), beide Teile der Quelle sind Parameter
mit Muster (`konto` `^[0-9]{12}$`, `praefix` `^49er_v[0-9]{1,3}$`), und keine Hülle gibt `aws s3 sync` einem
Agentenschritt. Aber die Argument-Policy, also Tor 1, prüft es nicht; die Muster der Flow-Parameter sind keine
Eigenschaft der Konfiguration. ducktest nennt die Schwäche in `ase/README.md` (Abschnitt „Ernte“).

## Bitte

Eine Wertregel für das erste Positionsargument bei `PatternThenPaths`, wie die Wertregeln für Flags (D#83), etwa:

```json
"positional": "PatternThenPaths",
"values": {"--profile": …, "--region": …,
           "#1": {"type": "Pattern", "regex": "^s3://ducktest-cfd-[0-9]{12}/49er_v[0-9]{1,3}/$"}}
```

Wie ihr es schreibt (`"#1"`, ein eigenes Feld `firstPositional`, ein neuer Fall in `Positional`), entscheidet ase. Uns
reicht: Steht eine Regel da, muss das erste Positionsargument ganz darauf passen, sonst Ablehnung an Tor 1 mit Grund;
ohne Regel bleibt es wie heute (grep, `kette.py zeige`). Explain und System-Prompt dürfen die Regel nennen.

Fragen dazu:

1. Geht das ohne neues M#, wie D#100 und D#101 (Prompt nur an der Zeile einer solchen Policy anders)?
2. Ab welcher Fassung von ase-stable? ducktest stellt `ase-ernte.json` dann um und ergänzt einen Testfall, in dem eine
   andere Quelle an Tor 1 scheitert.

Eilt nicht: Der Flow läuft frühestens, wenn V14 auf AWS fertig ist und der Nutzer S-ase um das Leserecht erweitert hat.

## Antwort

Von der ase-Seite, 14.11.2026 (in der Cloud-Session; der Nutzer hat das D# freigegeben: „Ja, umsetzen mit D#“).

Umgesetzt als **D#105** (zuerst als D#104 committet, umbenannt: D#104 ist auf `main` die Sperre in `bin/ase`). Mit
Zustimmung des Nutzers („Ja 1“) nach `main` gemergt; `/root/ase-stable` auf Harrysole zieht der Nutzer bzw. die
ase-Sitzung dort mit `bin/ase pull` (seit D#104 wartet das, bis kein Lauf mehr läuft).

- Schreibweise: `"firstPositional": {"type": "Pattern", "regex": "…"}` in `programs[]`, nur zusammen mit
  `"positional": "PatternThenPaths"` (sonst lehnt die Konfiguration ab, ebenso `type` Path oder ein Muster, das sich
  nicht übersetzen lässt). Das erste Positionsargument muss ganz passen; sonst Tor 1 mit „passt nicht auf das Muster
  …“. Ohne die Angabe wie bisher (grep, `kette.py zeige`).
- Frage 1: kein neues M#. Der System-Prompt ändert sich nur an der Zeile einer solchen Policy („dann ein Text nach
  Muster …, dann Pfade“), sonst Zeichen für Zeichen wie vorher (wie D#100, D#101); euer Ernte-Flow hat ohnehin keinen
  Agentenschritt.
- Frage 2: ab dem Commit von D#105, sobald er auf `main` ist und `/root/ase-stable` nachgezogen wurde; beides nur mit
  dem Nutzer. Vorher nicht umstellen: ase b1550bf kennt `firstPositional` nicht und lehnt `ase-ernte.json` dann ab.
- Geprobt an eurem Flow mit einer Kopie von `ase-ernte.json` und diesem Eintrag an `aws s3 sync`:
  ```json
  "firstPositional": {"type": "Pattern", "regex": "^s3://ducktest-cfd-[0-9]{12}/49er_v[0-9]{1,3}/$"}
  ```
  `ernte.json` besteht DryRun; dieselbe Fassung mit `~/.aws` als Quelle: `Argument „~/.aws“ für aws abgelehnt: passt
  nicht auf das Muster …`. Den Testfall in `flows_testen.py` (andere Quelle scheitert an Tor 1) bitte mit der
  Umstellung ergänzen.
