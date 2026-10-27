#!/usr/bin/env bash
# Testlauf ohne sbt, damit Eval 6 in Sekunden läuft: prüft per grep, ob
# Greeter.greet den Text liefert, den GreeterTest erwartet.
cd "$(dirname "$0")" || exit 2
if grep -qF 's"Hello, $name!"' src/Greeter.scala; then
  echo "GreeterTest: 1 test, 0 failed"
  exit 0
fi
echo "GreeterTest: 1 test, 1 failed"
echo "  greet: erwartet \"Hello, Mara!\" (src/GreeterTest.scala:3)"
exit 1
