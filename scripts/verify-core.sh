#!/usr/bin/env bash
# Compiles and tests Ciel's pure-Kotlin modules with a bare Kotlin compiler (no Gradle, no
# Android SDK). Used where Maven/Google repositories are unreachable. CI uses Gradle instead.
set -euo pipefail
KOTLINC_HOME="${KOTLINC_HOME:?set KOTLINC_HOME to an unpacked kotlin-compiler directory}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/build/verify}"
rm -rf "$OUT"; mkdir -p "$OUT"
LIB="$KOTLINC_HOME/lib"
CP="$LIB/kotlinx-coroutines-core-jvm.jar"
MODULES="core ai-providers task-engine skill-system memory-engine research-engine communication game-engine core-ai"
SRC=""
for m in $MODULES; do SRC="$SRC $ROOT/$m/src/main/kotlin"; done
echo "Compiling main sources..."
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -cp "$CP" -d "$OUT/main.jar" $SRC 2>&1 | grep -v "^warning" || true
test -f "$OUT/main.jar"
echo "Compiling tests..."
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -cp "$CP:$OUT/main.jar" -d "$OUT/test.jar" "$ROOT/testing/src/main/kotlin" 2>&1 | grep -v "^warning" || true
test -f "$OUT/test.jar"
echo "Running tests..."
java -cp "$LIB/kotlin-stdlib.jar:$CP:$OUT/main.jar:$OUT/test.jar" dev.ciel.testing.RunAllKt
