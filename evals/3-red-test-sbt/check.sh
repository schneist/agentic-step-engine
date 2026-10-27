#!/usr/bin/env bash
# Eval 3 red-test-sbt: REPORT.md nennt Interest.scala und die Ganzzahl-Division;
# nichts Bestehendes geändert (Diagnose, kein Fix).
here=$(cd "$(dirname "$0")" && pwd)
. "$here/../lib.sh"
setup "$@"

[ "$outcome" = done ] || fail "Lauf endete mit $outcome, erwartet done"
[ -f "$work/REPORT.md" ] || fail "REPORT.md fehlt"
grep -qF "Interest.scala" "$work/REPORT.md" || fail "REPORT.md nennt Interest.scala nicht"
grep -qiE "ganzzahl|integer|int-division|ratePercent / 100" "$work/REPORT.md" \
  || fail "REPORT.md nennt die Ganzzahl-Division nicht"
unchanged_except
no_new_files_except REPORT.md
echo OK
