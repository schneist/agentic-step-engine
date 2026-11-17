#!/usr/bin/env bash
# sbt in der Cloud-Session (L#12): findet sbt und ruft es mit einem PATH ohne Verzeichnisse hinter Links auf.
# bwrap scheitert sonst mit „Can't mkdir“ (O#66). Reihenfolge der Suche: $ASE_SBT, sbt im PATH, sbt unter
# /tmp/claude-*/…/scratchpad/sbt (Release-Archiv). Alle Argumente gehen an sbt, etwa:
#   tools/sbt-cloud.sh -batch test
# Exit 1, wenn kein sbt gefunden wird; sonst der Exit-Code von sbt.
set -euo pipefail

resolved=""
IFS=':' read -ra dirs <<< "${PATH:-}"
for d in "${dirs[@]}"; do
  [ -n "$d" ] && [ -d "$d" ] || continue
  r=$(readlink -f "$d")
  case ":$resolved:" in *":$r:"*) ;; *) resolved="${resolved:+$resolved:}$r" ;; esac
done

sbt=""
if [ -n "${ASE_SBT:-}" ]; then
  sbt="$ASE_SBT"
elif command -v sbt > /dev/null 2>&1; then
  sbt=$(command -v sbt)
else
  sbt=$(find /tmp -maxdepth 9 -path '*/sbt/bin/sbt' -type f 2> /dev/null | sort | head -n 1 || true)
fi
if [ -z "$sbt" ] || [ ! -x "$sbt" ]; then
  echo "sbt-cloud: kein sbt gefunden (ASE_SBT setzen oder das Release-Archiv ins Scratchpad entpacken)" >&2
  exit 1
fi
sbt=$(readlink -f "$sbt")

export PATH="$(dirname "$sbt")${resolved:+:$resolved}"
exec "$sbt" "$@"
