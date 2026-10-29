#!/usr/bin/env bash
# Eine Messserie nach playbook/evals.md: n Live-Läufe einer Eval, jeder in einer frischen Kopie des Fixtures.
# Aufruf (unter Linux, D#29; ANTHROPIC_API_KEY gesetzt):
#   evals/series.sh <eval-verzeichnis> <n> <ausgabe-verzeichnis> [--config runner.json] [--reference flow.json]
# Pro Lauf: ase plan, Freigabe des gezeigten Hashes, ase run --no-ask, check.sh. Läuft nichts (kein Plan besteht Tor 1,
# oder bei bestOfN keine Mehrheit: Ausgang ask), kommen die Kennzahlen aus plan-summary.json (O#23). code_hint zählt die
# Löcher, die laut Explain Code ändern dürfen, der im Lauf ausgeführt wird (O#28). Ergebnis: <ausgabe>/runs.tsv und
# eine Zusammenfassung auf stdout, im Format der M#-Zeile. --reference: Referenzflow; gezählt wird, wie viele Pläne
# dessen Fingerprint haben (playbook/evals.md). Die Ausgabe gehört nach record/ (nicht im Repo).
set -euo pipefail

usage="Aufruf: $0 <eval-verzeichnis> <n> <ausgabe-verzeichnis> [--config datei] [--reference flow.json]"
[ $# -ge 3 ] || { echo "$usage" >&2; exit 1; }
repo=$(cd "$(dirname "$0")/.." && pwd)
eval_dir=$(cd "$1" && pwd)
n=$2
mkdir -p "$3"
out=$(cd "$3" && pwd)
shift 3
abs() { echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
# Standard: evals/runner.json (Skripte wie ./run-tests.sh, D#46)
config=(--config "$repo/evals/runner.json")
reference=""
while [ $# -gt 0 ]; do
  case "$1" in
    --config) config=(--config "$(abs "$2")"); shift 2 ;;
    --reference) reference=$(abs "$2"); shift 2 ;;
    *) echo "$usage" >&2; exit 1 ;;
  esac
done

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

# Fingerprint des Referenzflows: ase plan --flow ruft kein Modell
ref_fp=""
if [ -n "$reference" ]; then
  ase plan --flow "$reference" --envelope "$eval_dir/envelope.json" --out "$out/reference-plan.json" "${config[@]}" > "$out/reference.txt" 2>&1
  ref_fp=$(sed -n 's/^fingerprint \([0-9a-f]*\)$/\1/p' "$out/reference.txt" | cut -c1-12)
  [ -n "$ref_fp" ] || { echo "Referenzflow ohne Fingerprint, siehe $out/reference.txt" >&2; exit 1; }
fi

printf 'lauf\toutcome\tcheck\tschritte\tplan\tfill\trunden\ttor1\ttor2\tohne_term\task\ttokens\tplan_fingerprint\tcode_hint\n' > "$out/runs.tsv"
for i in $(seq 1 "$n"); do
  run="$out/run-$i"
  mkdir -p "$run"
  tmp=$(mktemp -d)
  work="$tmp/work"
  cp -a "$eval_dir/fixture" "$work"

  set +e
  ase plan --task "$eval_dir/task.md" --envelope "$eval_dir/envelope.json" --out "$run/plan.json" --summary "$run/plan-summary.json" \
    "${config[@]}" > "$run/plan.txt" 2>&1
  pc=$?
  outcome=error
  if [ $pc -eq 0 ]; then
    hash=$(sed -n 's/^hash      \([0-9a-f]\{64\}\)$/\1/p' "$run/plan.txt" | head -1)
    ase run --plan "$run/plan.json" --envelope "$eval_dir/envelope.json" --workspace "$work" --approve "$hash" \
      --record "$run/record.log" --summary "$run/summary.json" --no-ask "${config[@]}" > "$run/run.txt" 2>&1
    case $? in 0) outcome=done ;; 2) outcome=ask ;; 3) outcome=aborted ;; esac
  elif [ $pc -eq 2 ]; then
    # keine Mehrheit unter den Plänen (bestOfN): der Mensch entscheidet, nichts ausgeführt
    outcome=ask
  elif [ $pc -eq 3 ]; then
    # kein Plan hat Tor 1 bestanden: nichts ausgeführt
    outcome=aborted
  fi
  check=fail
  if [ "$outcome" != error ] && bash "$eval_dir/check.sh" "$work" "$outcome" > "$run/check.txt" 2>&1; then check=ok; fi
  set -e

  s="$run/summary.json"
  [ -s "$s" ] || s="$run/plan-summary.json"
  hint=$(grep -c "Hinweis: darf Code ändern" "$run/plan.txt" || true)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$i" "$outcome" "$check" \
    "$(num steps "$s")" "$(num planCalls "$s")" "$(num fillCalls "$s")" "$(num holeRounds "$s")" "$(num gate1Rejections "$s")" \
    "$(num gate2Rejections "$s")" "$(num unusableAnswers "$s")" "$(sed -n 's/^  "asked" : \(.*\),/\1/p' "$s" 2>/dev/null)" \
    "$(num tokens "$s")" "$(str planFingerprint "$s" | cut -c1-12)" "$hint" >> "$out/runs.tsv"
  echo "Lauf $i: $outcome, check $check"
  rm -rf "$tmp"
done

# Zusammenfassung im Format der M#-Zeile (playbook/evals.md); Modell aus der Konfiguration ergänzen
summary=$(awk -F'\t' -v ref="$ref_fp" -v n="$n" '
  NR > 1 { ok += ($3 == "ok"); t1 += $8; t2 += $9; ask += ($11 == "true" || $2 == "ask"); tok += $12; same += (ref != "" && $13 == ref); code += ($14 > 0) }
  END {
    r = ref == "" ? "–" : same "/" n
    printf "n=%d · Erfolg %d/%d · Tor1 %d · Tor2 %d · Ask %d · Tokens ø %.1fk · Referenz-Fingerprint %s · Code-Hinweis %d/%d", n, ok, n, t1, t2, ask, tok / n / 1000, r, code, n
  }' "$out/runs.tsv")
echo "Eval $(basename "$eval_dir") · Commit $commit · $summary · Details in $out/runs.tsv"
