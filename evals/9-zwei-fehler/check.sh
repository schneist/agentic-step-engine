#!/usr/bin/env bash
# Eval 9 zwei-fehler: Build und Tests grün, genau eine Zeile in src/Greeter.scala und eine in src/Farewell.scala
# geändert, REPORT.md nennt beide Dateien, sonst nichts geändert.
here=$(cd "$(dirname "$0")" && pwd)
. "$here/../lib.sh"
setup "$@"

[ "$outcome" = done ] || fail "Lauf endete mit $outcome, erwartet done"
(cd "$work" && bash ./run-tests.sh > /dev/null) || fail "run-tests.sh ist weiterhin rot"
for f in src/Greeter.scala src/Farewell.scala; do
  changed=$(diff "$fixture/$f" "$work/$f" | grep -c '^[<>]')
  [ "$changed" -eq 2 ] || fail "$f: erwartet genau eine geänderte Zeile"
done
[ -f "$work/REPORT.md" ] || fail "REPORT.md fehlt"
grep -qF "Greeter.scala" "$work/REPORT.md" || fail "REPORT.md nennt Greeter.scala nicht"
grep -qF "Farewell.scala" "$work/REPORT.md" || fail "REPORT.md nennt Farewell.scala nicht"
unchanged_except src/Greeter.scala src/Farewell.scala
no_new_files_except REPORT.md
echo OK
