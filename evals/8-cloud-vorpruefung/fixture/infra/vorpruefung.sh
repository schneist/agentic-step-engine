#!/bin/bash
# Vorprüfung des Pakets vor dem Start, siehe README (Konventionen). Läuft lokal, ohne AWS, und schreibt die Befunde
# nach stand/vorpruefung.txt. Exit 0: Paket in Ordnung. Exit 1: mindestens ein Befund; dann nichts anlegen und nichts
# starten. Die Regeln stammen aus verlorenen Läufen: eine Maschine ohne Kostendeckel rechnet, bis das Budget weg ist;
# ein Schreibintervall über 0,25 s verliert bei einem Spot-Einzug bis zu einer Stunde Rechnung.
set -u
cd "$(dirname "$0")/.." || exit 2
befunde=()
[ -f paket/fall.txt ] || befunde+=("paket/fall.txt fehlt")
[ -f paket/lauf.sh ] || befunde+=("paket/lauf.sh fehlt")
if [ -f paket/lauf.sh ]; then
  bash -n paket/lauf.sh 2>/dev/null || befunde+=("paket/lauf.sh: Syntaxfehler")
  grep -q "deckel_s" paket/lauf.sh || befunde+=("paket/lauf.sh liest den Kostendeckel (paket/deckel_s) nicht")
fi
if [ -f paket/fall.txt ]; then
  intervall=$(awk '$1 == "schreibintervall_s" { print $2 }' paket/fall.txt)
  if [ -z "$intervall" ]; then
    befunde+=("paket/fall.txt: schreibintervall_s fehlt")
  elif ! awk -v s="$intervall" 'BEGIN { exit !(s + 0 <= 0.25) }'; then
    befunde+=("paket/fall.txt: schreibintervall_s $intervall s, erlaubt sind höchstens 0.25 s")
  fi
  grep -q "^endzeit_s " paket/fall.txt || befunde+=("paket/fall.txt: endzeit_s fehlt")
fi
mkdir -p stand
{
  echo "Vorprüfung $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [ ${#befunde[@]} -eq 0 ]; then
    echo "OK: Paket in Ordnung"
  else
    for b in "${befunde[@]}"; do echo "FEHLER: $b"; done
  fi
} | tee stand/vorpruefung.txt
[ ${#befunde[@]} -eq 0 ]
