#!/usr/bin/env bash
# Eval-Harness (SPEC Schritt 5): eine Messserie je Eval, alle mit derselben Konfiguration und demselben Commit.
# Aufruf (unter Linux, D#29):
#   evals/harness.sh <n> <ausgabe-verzeichnis> [--config runner.json] [--evals "1 4 6"]
# Für jede Eval ruft es evals/series.sh auf, Eval 6 mit dem Referenzflow flows/red-test.json. <ausgabe>/harness.md
# sammelt die Zusammenfassungen, eine Zeile pro Eval im Format der M#-Zeile; die Läufe liegen unter <ausgabe>/eval-<nr>.
# Bricht eine Serie ab (etwa ModelUnavailable), steht das in ihrer Zeile, und die übrigen laufen weiter.
set -euo pipefail

usage="Aufruf: $0 <n> <ausgabe-verzeichnis> [--config datei] [--evals \"1 2 3 4 5 6\"]"
[ $# -ge 2 ] || { echo "$usage" >&2; exit 1; }
repo=$(cd "$(dirname "$0")/.." && pwd)
n=$1
mkdir -p "$2"
out=$(cd "$2" && pwd)
shift 2
config=()
config_name="evals/runner.json"
evals="1 2 3 4 5 6"
while [ $# -gt 0 ]; do
  case "$1" in
    --config) config=(--config "$2"); config_name=$2; shift 2 ;;
    --evals) evals=$2; shift 2 ;;
    *) echo "$usage" >&2; exit 1 ;;
  esac
done

echo "# Harness $(date -u +%Y-%m-%dT%H:%MZ) · Commit $(git -C "$repo" rev-parse --short HEAD) · $config_name · n=$n" > "$out/harness.md"
for e in $evals; do
  dir=$(ls -d "$repo"/evals/"$e"-*/ 2>/dev/null | head -1)
  [ -n "$dir" ] || { echo "Eval $e: gibt es nicht" >> "$out/harness.md"; continue; }
  ref=()
  [ "$e" = 6 ] && ref=(--reference "$repo/flows/red-test.json")
  echo "Eval $e: $(basename "$dir") …" >&2
  if "$repo/evals/series.sh" "$dir" "$n" "$out/eval-$e" "${config[@]}" "${ref[@]}" > "$out/eval-$e.txt" 2>&1; then
    tail -1 "$out/eval-$e.txt" >> "$out/harness.md"
  else
    echo "Eval $(basename "$dir"): Serie abgebrochen, siehe $out/eval-$e.txt" >> "$out/harness.md"
  fi
done
cat "$out/harness.md"
