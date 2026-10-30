#!/usr/bin/env bash
# Eine Messserie nach playbook/evals.md: n Live-Läufe einer Eval, jeder in einer frischen Kopie des Fixtures.
# Aufruf (unter Linux, D#29; ANTHROPIC_API_KEY gesetzt):
#   evals/series.sh <eval-verzeichnis> <n> <ausgabe-verzeichnis> [--config runner.json] [--reference flow.json] [--flow flow.json] [--parallel]
# Vor dem ersten Lauf hält `ase series begin` Schlüssel und Bedingungen fest (D#77): Fassung, Hülle, Fixture als
# Tree-Hash, check.sh, Modell und Effort aus der Konfiguration, Commit, geänderte Dateien, n, Parallelität.
# Pro Lauf: ase plan, Freigabe des gezeigten Hashes, ase run --no-ask, check.sh; Ausgang, Prüfung und Code-Hinweise
# gehen nach run-<i>/run.json. Läuft nichts (kein Plan besteht Tor 1, oder bei bestOfN keine Mehrheit: Ausgang ask),
# kommen die Kennzahlen aus plan-summary.json (O#23). codeHints zählt die Löcher, die laut Explain Code ändern dürfen,
# der im Lauf ausgeführt wird (O#28). Am Ende baut `ase series end` den Datensatz <ausgabe>/series.json und druckt
# die M#-Zeile als letzte Zeile; wer die Serie als M# übernimmt, kopiert den Datensatz nach measurements/M<n>.json.
# --reference: Referenzflow; gezählt wird, wie viele Pläne dessen Fingerprint haben (playbook/evals.md). --flow: statt
# eines Modellplans aus task.md läuft jedes Mal dieser Flow (ase plan --flow, kein Planaufruf); gemessen werden dann
# nur die Fills. --parallel: alle Läufe gleichzeitig; die Dauer ist dann nur mit parallelen Serien vergleichbar.
# Die Ausgabe gehört nach record/ (nicht im Repo).
set -euo pipefail

usage="Aufruf: $0 <eval-verzeichnis> <n> <ausgabe-verzeichnis> [--config datei] [--reference flow.json] [--flow flow.json] [--parallel]"
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
flow=""
parallel=()
while [ $# -gt 0 ]; do
  case "$1" in
    --config) config=(--config "$(abs "$2")"); shift 2 ;;
    --reference) reference=$(abs "$2"); shift 2 ;;
    --flow) flow=$(abs "$2"); shift 2 ;;
    --parallel) parallel=(--parallel); shift ;;
    *) echo "$usage" >&2; exit 1 ;;
  esac
done

# Nur gegen einen sauberen Stand messen (playbook/evals.md). Mit ALLOW_DIRTY=1 läuft die Serie trotzdem; der
# Datensatz nennt dann die geänderten Dateien und zählt nicht.
# Erst lesen, dann schreiben: Läge die Ausgabe im Repository, sähe git status sonst die eigene Datei.
status=$(git -C "$repo" status --porcelain)
if [ -n "$status" ] && [ "${ALLOW_DIRTY:-}" != 1 ]; then
  echo "Arbeitsbaum nicht sauber; erst committen (oder ALLOW_DIRTY=1 für einen Probelauf, der nicht zählt)." >&2
  exit 1
fi
printf '%s' "$status" > "$out/git-status.txt"
commit=$(git -C "$repo" rev-parse --short HEAD)
source=(--task "$eval_dir/task.md")
[ -z "$flow" ] || source=(--flow "$flow")

cp=$(cd "$repo" && sbt -batch -error "export runner/Runtime/fullClasspath" | tail -1)
ase() { java -cp "$cp" ase.runner.Main "$@"; }

# Fingerprint des Referenzflows: ase plan --flow ruft kein Modell
ref=()
if [ -n "$reference" ]; then
  ase plan --flow "$reference" --envelope "$eval_dir/envelope.json" --out "$out/reference-plan.json" "${config[@]}" > "$out/reference.txt" 2>&1
  ref_fp=$(sed -n 's/^fingerprint \([0-9a-f]*\)$/\1/p' "$out/reference.txt")
  [ -n "$ref_fp" ] || { echo "Referenzflow ohne Fingerprint, siehe $out/reference.txt" >&2; exit 1; }
  ref=(--reference "$ref_fp")
fi

flow_arg=()
[ -z "$flow" ] || flow_arg=(--flow "$flow")
ase series begin --eval "$eval_dir" "${flow_arg[@]}" --commit "$commit" --status "$out/git-status.txt" --n "$n" \
  "${parallel[@]}" "${ref[@]}" "${config[@]}" --out "$out/series-input.json" >&2

one_run() {
  local i=$1
  local run="$out/run-$i"
  mkdir -p "$run"
  local tmp
  tmp=$(mktemp -d)
  local work="$tmp/work"
  cp -a "$eval_dir/fixture" "$work"

  set +e
  ase plan "${source[@]}" --envelope "$eval_dir/envelope.json" --out "$run/plan.json" --summary "$run/plan-summary.json" \
    "${config[@]}" > "$run/plan.txt" 2>&1
  local pc=$?
  local outcome=error
  if [ $pc -eq 0 ]; then
    local hash
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
  local check=fail
  if [ "$outcome" != error ] && bash "$eval_dir/check.sh" "$work" "$outcome" > "$run/check.txt" 2>&1; then check=ok; fi
  local hints
  hints=$(grep -c "Hinweis: darf Code ändern" "$run/plan.txt")
  set -e

  printf '{"outcome":"%s","check":"%s","codeHints":%d}\n' "$outcome" "$check" "${hints:-0}" > "$run/run.json"
  echo "Lauf $i: $outcome, check $check" >&2
  rm -rf "$tmp"
}

for i in $(seq 1 "$n"); do
  if [ ${#parallel[@]} -gt 0 ]; then one_run "$i" & else one_run "$i"; fi
done
wait

# Datensatz und M#-Zeile (playbook/evals.md); die Zeile steht zuletzt, harness.sh liest sie mit tail -1
ase series end --input "$out/series-input.json" --out "$out/series.json" "$out"
