#!/usr/bin/env bash
# Build und Tests ohne sbt, damit die Eval in Sekunden läuft (grep-basiert, führt src/ nicht aus).
# Wie bei sbt laufen die Tests erst, wenn alles kompiliert.
cd "$(dirname "$0")" || exit 2
if grep -qF '$nmae' src/Greeter.scala; then
  echo "[info] compiling 2 Scala sources to target/classes ..."
  echo "[error] src/Greeter.scala:2:45: Not found: nmae"
  echo "[error]   def greet(name: String): String = s\"Hello, \$nmae!\""
  echo "[error] one error found"
  echo "[error] (Compile / compileIncremental) Compilation failed"
  exit 1
fi
failed=0
out=""
grep -qF 's"Hello, $name!"' src/Greeter.scala || { failed=$((failed+1)); out="$out
  greet: erwartet \"Hello, Mara!\" (test/GreeterTest.scala:3)"; }
grep -qF 's"Goodbye, $name!"' src/Farewell.scala || { failed=$((failed+1)); out="$out
  bye: erwartet \"Goodbye, Mara!\" (test/FarewellTest.scala:3)"; }
echo "[info] compiling 2 Scala sources to target/classes ..."
echo "Tests: 2 tests, $failed failed$out"
[ "$failed" -eq 0 ]
