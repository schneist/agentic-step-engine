#!/usr/bin/env python3
"""Flows für den Leitstand aufbereiten (CLAUDE.md, Abschnitt Leitstand).

Liest Flow-Dateien (JSON mit "term") und schreibt je Flow ein JSON-Dokument für die Sammlung `flows`:
Name, Zweck, Parameter, Zählung und die Schritte als flache Liste mit Tiefe. Ein If, dessen einer Zweig nur ein Done
ist, wird als Tor dargestellt („weiter, wenn …“, sonst Ende), damit die üblichen Ketten aus Schritt und Prüfung
lesbar bleiben. Eine Stufenfolge (*.folge.json mit "stufen", D#112) wird ein eigener Eintrag: je Stufe eine Zeile
„Stufe“ mit Flow, Hülle und Konfiguration, dazwischen ein Tor „weiter, wenn … mit done endet“; die Zählung summiert
die Flows der Stufen. Schreibt nichts außer den JSON-Dateien; hochgeladen wird mit ArtifactData (batch, file_path).

  python3 tools/leitstand_flows.py --repo ducktest --root ~/ducktest --commit <sha> --out DIR ase/pruefkette.json …
"""
import argparse, json, os, sys

def kurz(s, n):
    s = " ".join(str(s).split())
    return s if len(s) <= n else s[: n - 1] + "…"

def pred(p):
    t = p.get("type")
    if t == "LastExitCode": return f"Exit = {p.get('code')}"
    if t == "LastOutputEmpty": return "Ausgabe leer"
    if t == "LastOutputContains": return f"Ausgabe enthält „{kurz(p.get('text', ''), 60)}“"
    if t == "FileExists": return f"{p.get('path')} existiert"
    if t == "Not":
        q = p.get("p", {})
        if q.get("type") == "LastExitCode": return f"Exit ≠ {q.get('code')}"
        if q.get("type") == "LastOutputEmpty": return "Ausgabe nicht leer"
        if q.get("type") == "LastOutputContains": return f"Ausgabe ohne „{kurz(q.get('text', ''), 60)}“"
        return "nicht (" + pred(q) + ")"
    if t == "And": return pred(p.get("p", {})) + " und " + pred(p.get("q", {}))
    return t or "?"

def negate(p):
    return p["p"] if p.get("type") == "Not" else {"type": "Not", "p": p}

def huelle(e):
    if not e: return ""
    ops = ", ".join(e.get("allowedOps", []))
    progs = sorted({" ".join([a["binary"]] + a.get("requiredPrefixArgs", [])) for a in e.get("shellAllowlist", [])})
    s = f"darf {ops} unter {e.get('pathPrefix', '.')}, höchstens {e.get('maxSteps', '?')} Schritte"
    if progs: s += "; Programme: " + ", ".join(progs)
    return kurz(s, 400)

class Flach:
    def __init__(self):
        self.rows, self.nr = [], 0
        self.z = {"schritte": 0, "agent": 0, "fragen": 0, "aws": 0}

    def step(self, row, zaehlt=True):
        if zaehlt:
            self.nr += 1; row["n"] = self.nr; self.z["schritte"] += 1
        self.rows.append(row)

    def walk(self, t, d):
        k = t.get("type")
        if k == "Seq":
            for s in t.get("steps", []): self.walk(s, d)
        elif k == "Shell":
            self.step({"t": "shell", "x": kurz(" ".join([t.get("cmd", "")] + t.get("args", [])), 220), "d": d})
            if t.get("cmd") == "aws": self.z["aws"] += 1
        elif k in ("WriteFile", "EditFile", "ReadFile", "ListDir"):
            verb = {"WriteFile": "schreibt", "EditFile": "ändert", "ReadFile": "liest", "ListDir": "listet"}[k]
            self.step({"t": "datei", "x": f"{verb} {t.get('path', '')}", "d": d})
        elif k == "Ask":
            self.z["fragen"] += 1
            self.step({"t": "ask", "x": kurz(t.get("question", ""), 400), "d": d})
        elif k == "Hole":
            self.z["agent"] += 1
            row = {"t": "agent", "x": kurz(t.get("goal", ""), 500), "h": huelle(t.get("envelope")), "d": d}
            if t.get("verify"): row["v"] = "mit Abnahme"
            self.step(row)
        elif k == "Done":
            self.step({"t": "done", "x": kurz(t.get("summary", ""), 240), "d": d}, zaehlt=False)
        elif k == "Abort":
            self.step({"t": "abbruch", "x": kurz(t.get("reason", ""), 240), "d": d}, zaehlt=False)
        elif k == "If":
            th, el, p = t.get("thenT", {}), t.get("elseT", {}), t.get("pred", {})
            ende = ("Done", "Abort")
            def text(e):  # Abbruch (D#111) im Leitstand als solcher kenntlich
                return ("Abbruch: " + e.get("reason", "")) if e.get("type") == "Abort" else e.get("summary", "")
            if el.get("type") in ende and th.get("type") not in ende:
                self.step({"t": "tor", "x": "weiter, wenn " + pred(p), "e": kurz(text(el), 240), "d": d}, False)
                self.walk(th, d)
            elif th.get("type") in ende and el.get("type") not in ende:
                self.step({"t": "tor", "x": "weiter, wenn " + pred(negate(p)), "e": kurz(text(th), 240), "d": d}, False)
                self.walk(el, d)
            else:
                self.step({"t": "wenn", "x": "wenn " + pred(p), "d": d}, False)
                self.step({"t": "zweig", "x": "dann", "d": d}, False); self.walk(th, d + 1)
                self.step({"t": "zweig", "x": "sonst", "d": d}, False); self.walk(el, d + 1)
        elif k == "RepeatUntil":
            self.step({"t": "wiederhole", "x": f"wiederholt bis {pred(t.get('until', {}))}, höchstens {t.get('max', '?')}-mal", "d": d}, False)
            self.walk(t.get("body", {}), d + 1)
        else:
            self.step({"t": "sonst", "x": k or "?", "d": d}, False)

def eintrag_id(repo, rel):
    name = rel[:-5] if rel.endswith(".json") else rel
    return repo + ":" + name.replace("/", ":")

def gruppe(rel):
    return "CFD" if "/cfd/" in rel else ("Evals" if rel.startswith("flows/") else "Prüfen")

def folge(a, rel, d):
    """Eine Stufenfolge als Eintrag: Stufen der Reihe nach, Tor dazwischen, Zählung über die Flows der Stufen."""
    basis = os.path.dirname(rel)
    rows, z, ids = [], {"schritte": 0, "agent": 0, "fragen": 0, "aws": 0, "stufen": 0}, []
    stufen = d.get("stufen", [])
    for i, st in enumerate(stufen):
        flow_rel = os.path.normpath(os.path.join(basis, st.get("flow", "")))
        ids.append(eintrag_id(a.repo, flow_rel))
        try:
            with open(os.path.join(a.root, flow_rel), encoding="utf-8") as f: fd = json.load(f)
            fl = Flach(); fl.walk(fd.get("term", {}), 0)
            for k in ("schritte", "agent", "fragen", "aws"): z[k] += fl.z[k]
            info = f"{fl.z['schritte']} Schritt" + ("" if fl.z["schritte"] == 1 else "e")
        except (OSError, ValueError) as e:
            info = f"Flow nicht lesbar: {e.__class__.__name__}"
        z["stufen"] += 1
        werte = ", ".join(f"{k}={v}" for k, v in sorted(st.get("werte", {}).items()))
        rows.append({"t": "stufe", "n": i + 1, "d": 0,
                     "x": kurz(f"{st.get('name', '?')}: Flow {flow_rel} ({info})", 240),
                     "h": kurz(f"{st.get('envelope', '?')}, Konfiguration {st.get('config', 'Standard')}" + (f"; Werte {werte}" if werte else ""), 400)})
        if i + 1 < len(stufen):
            rows.append({"t": "tor", "d": 0, "x": f"weiter, wenn Stufe {st.get('name', '?')} mit done endet",
                         "e": "Abbruch: die Folge hält an, die übrigen Stufen laufen nicht"})
    name = rel[:-5] if rel.endswith(".json") else rel
    return {"repo": a.repo, "pfad": rel, "name": os.path.basename(name), "art": "folge", "gruppe": gruppe(rel),
            "zweck": kurz(d.get("description", ""), 900), "params": sorted(d.get("params", {}).keys()),
            "zaehlung": z, "schritte": rows, "stufen": ids, "commit": a.commit}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True); ap.add_argument("--root", required=True)
    ap.add_argument("--commit", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("flows", nargs="+")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for rel in a.flows:
        with open(os.path.join(a.root, rel), encoding="utf-8") as f: d = json.load(f)
        if "stufen" in d:
            doc = folge(a, rel, d)
            with open(os.path.join(a.out, eintrag_id(a.repo, rel) + ".json"), "w", encoding="utf-8") as f: json.dump(doc, f, ensure_ascii=False)
            print(eintrag_id(a.repo, rel), doc["zaehlung"], len(doc["schritte"]), "Zeilen (Folge)")
            continue
        if "term" not in d: continue
        fl = Flach(); fl.walk(d["term"], 0)
        name = rel[:-5] if rel.endswith(".json") else rel
        doc = {"repo": a.repo, "pfad": rel, "name": os.path.basename(name),
               "gruppe": "CFD" if "/cfd/" in rel else ("Evals" if rel.startswith("flows/") else "Prüfen"),
               "zweck": kurz(d.get("description", ""), 900), "params": sorted(d.get("params", {}).keys()),
               "zaehlung": fl.z, "schritte": fl.rows, "commit": a.commit}
        doc_id = a.repo + ":" + name.replace("/", ":")
        with open(os.path.join(a.out, doc_id + ".json"), "w", encoding="utf-8") as f: json.dump(doc, f, ensure_ascii=False)
        print(doc_id, fl.z, len(fl.rows), "Zeilen")

if __name__ == "__main__":
    main()
