#!/usr/bin/env bash
# Eval 4 readme: README.md nennt Name, Build, alle drei Optionen und die Lizenz;
# Umfang 10 bis 200 Zeilen; nichts Bestehendes geändert.
here=$(cd "$(dirname "$0")" && pwd)
. "$here/../lib.sh"
setup "$@"

[ "$outcome" = done ] || fail "Lauf endete mit $outcome, erwartet done"
r="$work/README.md"
[ -f "$r" ] || fail "README.md fehlt"
for s in wc-lite sbt --lines --words --chars MIT; do
  grep -qF -- "$s" "$r" || fail "README.md nennt $s nicht"
done
n=$(wc -l < "$r")
[ "$n" -ge 10 ] && [ "$n" -le 200 ] || fail "README.md hat $n Zeilen, erwartet 10 bis 200"
unchanged_except
no_new_files_except README.md
echo OK
