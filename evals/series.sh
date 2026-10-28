#!/usr/bin/env bash
# Eine Messserie nach playbook/evals.md: n Live-Läufe einer Eval, jeder in einer frischen Kopie des Fixtures.
# Aufruf (unter Linux, D#29; ANTHROPIC_API_KEY gesetzt):
#   evals/series.sh <eval-verzeichnis> <n> <ausgabe-verzeichnis> [--config runner.json]
# Pro Lauf: ase plan, Freigabe des gezeigten Hashes, ase run --no-ask, check.sh. Ergebnis: <ausgabe>/runs.tsv und
# eine Zusammenfassung auf stdout. Die Ausgabe gehört nach record/ (nicht im Repo).
set -euo pipefail

[ $# -ge 3 ] || { echo "Aufruf: $0 <eval-verzeichnis> <n> <ausgabe-verzeichnis> [--config datei]" >&2; exit 1; }
repo=$(cd "$(dirname "$0")/.." && pwd)
eval_dir=$(cd "$1" && pwd)
n=$2
mkdir -p "$3"
out=$(cd "$3" && pwd)
shift 3
# Standard: evals/runner.json (Skripte wie ./run-tests.sh, D#46)
config=(--config "$repo/evals/runner.json")
[ "${1:-}" = --config ] && config=(--config "$(cd "$(dirname "$2")" && pwd)/$(basename "$2")")

# Nur gegen einen sauberen Stand messen (playbook/evals.md).
if [ -n "$(git -C "$repo" status --porcelain)" ] && [ "${ALLOW_DIRTY:-}" != 1 ]; then
  echo "Arbeitsbaum nicht sauber; erst committen (oder ALLOW_DIRTY=1 für einen Probelauf, der nicht zählt)." >&2
  exit 1
fi
commit=$(git -C "$repo" rev-parse --short HEAD)

cp=$(cd "$repo" && sbt -batch -error "export runner/Runtime/fullClasspath" | tail -1)
ase() { java -cp "$cp" ase.runner.Main "$@"; }

num() { sed -n "s/^  \"$1\" : \([0-9]*\).*/\1/p" "$2" 2>/dev/null | head -1; }
str() { sed -n "s/^  \"$1\" : \"\(.*\)\".*/\1/p" "$2" 2>/dev/null | head -1; }

printf 'lauf\toutcome\tcheck\tschritte\tplan\tfill\trunden\ttor1\ttor2\tohne_term\task\ttokens\tplan_fingerprint\n' > "$out/runs.tsv"
for i in $(seq 1 "$n"); do
  run="$out/run-$i"
  mkdir -p "$run"
  tmp=$(mktemp -d)
  work="$tmp/work"
  cp -a "$eval_dir/fixture" "$work"

  set +e
  ase plan --task "$eval_dir/task.md" --envelope "$eval_dir/envelope.json" --out "$run/plan.json" "${config[@]}" > "$run/plan.txt" 2>&1
  pc=$?
  outcome=error
  if [ $pc -eq 0 ]; then
    hash=$(sed -n 's/^hash      \([0-9a-f]\{64\}\)$/\1/p' "$run/plan.txt" | head -1)
    ase run --plan "$run/plan.json" --envelope "$eval_dir/envelope.json" --workspace "$work" --approve "$hash" \
      --record "$run/record.log" --summary "$run/summary.json" --no-ask "${config[@]}" > "$run/run.txt" 2>&1
    case $? in 0) outcome=done ;; 2) outcome=ask ;; 3) outcome=aborted ;; esac
  elif [ $pc -eq 3 ]; then
    # kein Plan hat Tor 1 bestanden: nichts ausgeführt
    outcome=aborted
  fi
  check=fail
  if [ "$outcome" != error ] && bash "$eval_dir/check.sh" "$work" "$outcome" > "$run/check.txt" 2>&1; then check=ok; fi
  set -e

  s="$run/summary.json"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$i" "$outcome" "$check" \
    "$(num steps "$s")" "$(num planCalls "$s")" "$(num fillCalls "$s")" "$(num holeRounds "$s")" "$(num gate1Rejections "$s")" \
    "$(num gate2Rejections "$s")" "$(num unusableAnswers "$s")" "$(sed -n 's/^  "asked" : \(.*\),/\1/p' "$s" 2>/dev/null)" \
    "$(num tokens "$s")" "$(str planFingerprint "$s" | cut -c1-12)" >> "$out/runs.tsv"
  echo "Lauf $i: $outcome, check $check"
  rm -rf "$tmp"
done

ok=$(awk -F'\t' 'NR > 1 && $3 == "ok"' "$out/runs.tsv" | wc -l)
echo "Eval $(basename "$eval_dir") · Commit $commit · n=$n · Erfolg $ok/$n · Details in $out/runs.tsv"
