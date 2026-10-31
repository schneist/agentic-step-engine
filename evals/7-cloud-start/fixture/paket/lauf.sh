#!/bin/bash
# Läuft auf der Maschine: rechnet den Fall aus fall.txt, liest jede Minute den Kostendeckel und lädt Ergebnisse hoch.
# In diesem Eval nur Teil des Pakets, er wird nicht ausgeführt.
set -u
echo "lauf: $(head -n 1 fall.txt)"
