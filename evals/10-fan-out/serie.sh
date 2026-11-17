#!/bin/bash
# Eval 10 (fan-out, O#68): n Pläne je Stufe N, nur ase plan, Ergebnis je Plan als JSON-Zeile in ergebnis.jsonl.
#   evals/10-fan-out/serie.sh <runner.json> <ausgabe-dir> [n=5] [Stufen="10 30 70 100"]
set -u
CFG=$1; OUT=$2; N=${3:-5}; STUFEN=${4:-"10 30 70 100"}
E=$(cd "$(dirname "$0")" && pwd); mkdir -p "$OUT"
eins() {
  k=$1; i=$2; D=$OUT/n$k-$i; mkdir -p $D; cp -r $E/fixture $D/ws
  (cd $D && timeout 1800 ~/bin/ase plan --task $E/task-$k.md --envelope $E/envelope.json --config $CFG --workspace $D/ws \
     --out plan.json --summary summary.json > plan.txt 2>&1; echo "exit $?" >> plan.txt)
  python3 $E/auswerten.py $k $D/plan.json $D/summary.json >> $OUT/ergebnis.jsonl
}
for k in $STUFEN; do
  for i in $(seq 1 $N); do eins $k $i & sleep 5; done
  wait
done
touch $OUT/fertig
