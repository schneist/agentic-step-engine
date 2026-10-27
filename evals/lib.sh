# Gemeinsame Prüfungen für check.sh, per source eingebunden.
# Vertrag: check.sh WORKDIR OUTCOME
#   WORKDIR  Arbeitsverzeichnis nach dem Lauf (frische Kopie von fixture/)
#   OUTCOME  Ausgang des Laufs laut Runner: done | ask | aborted
# Exit 0 = Erfolg. Build-Verzeichnisse (target/, .bsp/) werden ignoriert.

fail() { echo "FAIL: $*"; exit 1; }

setup() {
  [ $# -eq 2 ] || { echo "Aufruf: check.sh WORKDIR OUTCOME (done|ask|aborted)"; exit 2; }
  work=$(cd "$1" && pwd) || exit 2
  outcome=$2
  fixture="$here/fixture"
}

# Jede Datei des Fixtures ist unverändert vorhanden, außer den genannten (relativ).
unchanged_except() {
  local f rel a
  while IFS= read -r f; do
    rel=${f#"$fixture"/}
    for a in "$@"; do [ "$rel" = "$a" ] && continue 2; done
    cmp -s "$f" "$work/$rel" || fail "verändert oder gelöscht: $rel"
  done < <(find "$fixture" -type f)
}

# Keine Datei im Arbeitsverzeichnis, die nicht im Fixture ist, außer den genannten.
no_new_files_except() {
  local f rel a
  while IFS= read -r f; do
    rel=${f#./}
    for a in "$@"; do [ "$rel" = "$a" ] && continue 2; done
    [ -e "$fixture/$rel" ] || fail "neue Datei: $rel"
  done < <(cd "$work" && find . -type f -not -path '*/target/*' -not -path './.bsp/*')
}
