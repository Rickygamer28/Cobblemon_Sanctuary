#!/usr/bin/env bash
# Decompiles selected parts of the Cobblemon jar so the API can be read without the source.
# Usage: dump-api.sh <out-dir>   (patterns come from .ci/dump-patterns.txt)
set -u
OUT="$1"
mkdir -p "$OUT"
JAR="$RUNNER_TEMP/cobblemon.jar"
curl -fsSL -o "$JAR" "$COBBLE_URL" || JAR=""

echo "Cobblemon jar: $JAR" | tee "$OUT/jar.txt"
[ -z "$JAR" ] && exit 0
unzip -l "$JAR" > "$OUT/jar-listing.txt" 2>&1
unzip -p "$JAR" META-INF/neoforge.mods.toml > "$OUT/cobblemon-mods.toml" 2>/dev/null || true

WORK=$(mktemp -d)
mkdir -p "$WORK/classes"
while IFS= read -r pat; do
  [ -z "$pat" ] && continue
  case "$pat" in \#*) continue;; esac
  unzip -o -q "$JAR" "$pat" -d "$WORK/classes" 2>/dev/null || true
done < .ci/dump-patterns.txt

curl -sSL -o "$WORK/vineflower.jar" https://repo1.maven.org/maven2/org/vineflower/vineflower/1.10.1/vineflower-1.10.1.jar
mkdir -p "$OUT/src"
timeout 600 java -jar "$WORK/vineflower.jar" -dgs=1 -rsy=1 -log=WARN "$WORK/classes" "$OUT/src" > "$OUT/decompile.log" 2>&1 || true
find "$OUT/src" -type f | wc -l > "$OUT/src-count.txt"
