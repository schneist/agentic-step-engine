#!/usr/bin/env bash
# Eval 1 todos: TODO.md listet genau die fünf TODO-Kommentare unter src/.
here=$(cd "$(dirname "$0")" && pwd)
. "$here/../lib.sh"
setup "$@"

[ "$outcome" = done ] || fail "Lauf endete mit $outcome, erwartet done"
[ -f "$work/TODO.md" ] || fail "TODO.md fehlt"
expected=(
  src/main/scala/notes/Main.scala:3
  src/main/scala/notes/Store.scala:4
  src/main/scala/notes/Store.scala:7
  src/main/scala/notes/Store.scala:11
  src/main/scala/notes/util/Text.scala:5
)
for e in "${expected[@]}"; do
  grep -qF -- "- $e " "$work/TODO.md" || fail "Eintrag fehlt: $e"
done
n=$(grep -c '^- ' "$work/TODO.md")
[ "$n" -eq "${#expected[@]}" ] || fail "$n Einträge, erwartet ${#expected[@]}"
unchanged_except
no_new_files_except TODO.md
echo OK
