#!/usr/bin/env python3
"""Lehren aus dem Leitstand als Dateien für S3 (D#109, Nachtrag).

Liest die Dokumente der Sammlung `lehren`, wie ArtifactData sie mit `out_dir` ablegt (je Dokument eine JSON-Datei,
Dateiname = Dokument-ID), und schreibt zwei Dateien: `lehren.json` (alle Einträge mit ID, sortiert) und `LEHREN.md`
(zum Lesen, nach Status gruppiert). Hochgeladen wird danach mit der aws-CLI nach s3://ducktest-cfd-<konto>/lehren/.

  python3 tools/lehren_export.py <verzeichnis-mit-json> <ausgabe-verzeichnis>
"""
import json, os, sys

STATUS = ["neu", "abgeleitet", "umgesetzt", "keine Folge"]
TITEL = {"neu": "Neu, wartet auf Ableitung", "abgeleitet": "Folge in Arbeit", "umgesetzt": "Umgesetzt", "keine Folge": "Keine Folge"}

def main():
    if len(sys.argv) != 3:
        sys.exit("Aufruf: lehren_export.py <verzeichnis-mit-json> <ausgabe-verzeichnis>")
    src, out = sys.argv[1], sys.argv[2]
    docs = []
    for f in sorted(os.listdir(src)):
        if f.endswith(".json"):
            with open(os.path.join(src, f), encoding="utf-8") as h:
                d = json.load(h)
            d["id"] = f[:-5]
            docs.append(d)
    if not docs:
        sys.exit("keine Lehren gefunden in " + src)
    docs.sort(key=lambda d: (d.get("zeit", ""), d["id"]))
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "lehren.json"), "w", encoding="utf-8") as h:
        json.dump(docs, h, ensure_ascii=False, indent=1)
    zeilen = ["# Lehren ducktest und ase", "",
              "Erzeugt aus dem Leitstand (Sammlung `lehren`). Maßgeblich sind die Einträge `L#` in `findings.md` der",
              "beiden Repositories; Einträge „von dir“ kommen aus dem Formular im Leitstand.", ""]
    for st in STATUS + sorted({d.get("status", "") for d in docs} - set(STATUS)):
        teil = [d for d in docs if d.get("status", "") == st]
        if not teil:
            continue
        zeilen += [f"## {TITEL.get(st, st or 'ohne Status')} ({len(teil)})", ""]
        for d in teil:
            quelle = "von dir" if d.get("quelle") == "nutzer" else d.get("quelle", "?")
            kopf = f"- **{(d.get('nr', '') + ' ').lstrip()}{d.get('titel', '')}** ({quelle}, {d.get('zeit', '')[:10]})"
            zeilen.append(kopf)
            for feld in ("anlass", "ursache", "folge", "abgeleitet_in"):
                if d.get(feld):
                    zeilen.append(f"  - {feld.replace('_', ' ').capitalize()}: {d[feld]}")
        zeilen.append("")
    with open(os.path.join(out, "LEHREN.md"), "w", encoding="utf-8") as h:
        h.write("\n".join(zeilen))
    print(f"{len(docs)} Lehren -> {out}/lehren.json, {out}/LEHREN.md")

if __name__ == "__main__":
    main()
