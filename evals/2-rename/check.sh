#!/usr/bin/env bash
# Eval 2 rename: Ergebnis ist byteidentisch mit dem Fixture, in dem docs/setup.md
# nach docs/installation.md verschoben und in README.md, docs/faq.md, docs/index.md
# jedes "setup.md" durch "installation.md" ersetzt wurde. CHANGELOG.md bleibt.
here=$(cd "$(dirname "$0")" && pwd)
. "$here/../lib.sh"
setup "$@"

[ "$outcome" = done ] || fail "Lauf endete mit $outcome, erwartet done"
[ ! -e "$work/docs/setup.md" ] || fail "docs/setup.md existiert noch"
expected=$(mktemp -d)
trap 'rm -rf "$expected"' EXIT
cp -r "$fixture/." "$expected"
mv "$expected/docs/setup.md" "$expected/docs/installation.md"
sed -i 's/setup\.md/installation.md/g' "$expected/README.md" "$expected/docs/faq.md" "$expected/docs/index.md"
while IFS= read -r f; do
  rel=${f#"$expected"/}
  cmp -s "$f" "$work/$rel" || fail "weicht ab oder fehlt: $rel"
done < <(find "$expected" -type f)
no_new_files_except docs/installation.md
echo OK
