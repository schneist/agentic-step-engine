#!/usr/bin/env python3
"""Eval 10 (fan-out, O#68): bewertet nur den Plan. Zählt, wie viele der N Dateien der statische Term mit dem richtigen
Inhalt anlegt, welche fehlen, welche falsch oder doppelt sind, und was sonst im Plan steht.

  python3 evals/10-fan-out/auswerten.py <N> <plan.json> [<summary.json>]   -> eine JSON-Zeile
"""
import json, os, sys

def knoten(t):
    if isinstance(t, dict):
        yield t
        for v in t.values(): yield from knoten(v)
    elif isinstance(t, list):
        for v in t: yield from knoten(v)

def main():
    n, plan = int(sys.argv[1]), sys.argv[2]
    hier = os.path.dirname(os.path.abspath(__file__))
    namen = open(os.path.join(hier, "namen.txt"), encoding="utf-8").read().split()[:n]
    erg = {"n": n, "plan": plan}
    if not os.path.exists(plan):
        erg["fehler"] = "kein Plan"; print(json.dumps(erg)); return
    term = json.load(open(plan, encoding="utf-8"))["term"]
    writes = [k for k in knoten(term) if k.get("type") == "WriteFile"]
    andere = sorted({k.get("type") for k in knoten(term)} - {"WriteFile", "Seq", "Done"})
    pfade = [k.get("path", "") for k in writes]
    richtig = {x for x in namen if any(k.get("path") in (f"out/{x}.txt", f"./out/{x}.txt") and k.get("content") in (x + "\n", x) for k in writes)}
    angelegt = {p.removeprefix("./") for p in pfade}
    erg.update(writefiles=len(writes), richtig=len(richtig), fehlend=sorted(set(namen) - richtig),
               falsch=sorted(angelegt - {f"out/{x}.txt" for x in namen}), doppelt=len(pfade) - len(set(pfade)),
               vollstaendig=len(richtig) == n and len(writes) == n, andere_knoten=andere)
    if len(sys.argv) > 3 and os.path.exists(sys.argv[3]):
        s = json.load(open(sys.argv[3], encoding="utf-8"))
        erg.update({k: s.get(k) for k in ("outcome", "tokens", "seconds", "gate1Rejections", "planCalls", "promptSha")})
    print(json.dumps(erg, ensure_ascii=False))

if __name__ == "__main__":
    main()
