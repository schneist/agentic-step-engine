#!/usr/bin/env bash
# Eval 6 red-test: Tests grün, genau eine Zeile in src/Greeter.scala geändert,
# REPORT.md nennt Greeter.scala, sonst nichts geändert.
here=$(cd "$(dirname "$0")" && pwd)
. "$here/../lib.sh"
setup "$@"

[ "$outcome" = done ] || fail "Lauf endete mit $outcome, erwartet done"
(cd "$work" && bash ./run-tests.sh > /dev/null) || fail "run-tests.sh ist weiterhin rot"
changed=$(diff "$fixture/src/Greeter.scala" "$work/src/Greeter.scala" | grep -c '^[<>]')
[ "$changed" -eq 2 ] || fail "Greeter.scala: erwartet genau eine geänderte Zeile"
[ -f "$work/REPORT.md" ] || fail "REPORT.md fehlt"
grep -qF "Greeter.scala" "$work/REPORT.md" || fail "REPORT.md nennt Greeter.scala nicht"
unchanged_except src/Greeter.scala
no_new_files_except REPORT.md
echo OK
