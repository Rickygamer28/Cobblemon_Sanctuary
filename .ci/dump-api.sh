#!/usr/bin/env bash
# Dumps parts of the Cobblemon jar so its API can be read without the source:
#  - javap signatures for classes matching .ci/javap-regex.txt (fast)
#  - Vineflower-decompiled source for classes listed in .ci/decompile-classes.txt
# Usage: dump-api.sh <out-dir>
set -u
OUT="$1"
mkdir -p "$OUT/javap" "$OUT/src"
JAR="$RUNNER_TEMP/cobblemon.jar"
curl -fsSL -o "$JAR" "$COBBLE_URL" || { echo "download failed" > "$OUT/error.txt"; exit 0; }
unzip -Z1 "$JAR" > "$OUT/jar-classes.txt"

# javap signatures
grep '\.class$' "$OUT/jar-classes.txt" | grep -v '\$\$' | grep -E -f .ci/javap-regex.txt | sed 's/\.class$//; s|/|.|g' > "$OUT/javap-list.txt"
wc -l < "$OUT/javap-list.txt" > "$OUT/javap-count.txt"
timeout 600 xargs -a "$OUT/javap-list.txt" -n 200 javap -p -cp "$JAR" > "$OUT/javap-all.txt" 2> "$OUT/javap-errors.txt" || true
# split per top-level class file
awk -v out="$OUT/javap" '
  /^Compiled from/ { next }
  /^(public |protected |private |)(abstract |final |static |sealed |non-sealed )*(class|interface|enum|@interface|record) / {
    name=$0; sub(/ (extends|implements|permits) .*/, "", name); sub(/ *\{$/, "", name); n=split(name, parts, " "); cls=parts[n]; sub(/<.*/, "", cls);
    top=cls; sub(/\$.*/, "", top); file=out "/" top ".txt"
  }
  { if (file != "") print >> file }
' "$OUT/javap-all.txt"
rm -f "$OUT/javap-all.txt"

# decompiled source for key classes
WORK=$(mktemp -d)
mkdir -p "$WORK/classes"
while IFS= read -r pat; do
  [ -z "$pat" ] && continue
  case "$pat" in \#*) continue;; esac
  unzip -o -q "$JAR" "$pat" -d "$WORK/classes" 2>/dev/null || true
done < .ci/decompile-classes.txt
find "$WORK/classes" -name '*.class' | wc -l > "$OUT/decompile-input-count.txt"
curl -fsSL -o "$WORK/vineflower.jar" https://repo1.maven.org/maven2/org/vineflower/vineflower/1.10.1/vineflower-1.10.1.jar
timeout 480 java -jar "$WORK/vineflower.jar" -dgs=1 -mpm=20 -log=ERROR "$WORK/classes" "$OUT/src" > "$OUT/decompile.log" 2>&1 || echo "vineflower exit $?" >> "$OUT/decompile.log"
find "$OUT/src" -type f | wc -l > "$OUT/src-count.txt"

# raw data files (behaviours, a few posers and species) to learn animation names and AI setup
mkdir -p "$OUT/raw"
unzip -o -q "$JAR" 'data/cobblemon/behaviours/*' 'assets/cobblemon/bedrock/pokemon/posers/0025_pikachu/*' 'assets/cobblemon/bedrock/pokemon/posers/0006_charizard/*' 'assets/cobblemon/bedrock/pokemon/posers/0133_eevee/*' 'assets/cobblemon/bedrock/pokemon/posers/0094_gengar/*' 'assets/cobblemon/bedrock/pokemon/posers/0130_gyarados/*' 'assets/cobblemon/bedrock/pokemon/posers/0001_bulbasaur/*' 'data/cobblemon/species/generation1/pikachu.json' 'data/cobblemon/species/generation1/charmander.json' 'data/cobblemon/species/generation1/eevee.json' -d "$OUT/raw" 2>/dev/null || true
grep -o 'animations/[^"]*' "$OUT/jar-classes.txt" | head -0 >/dev/null
# all animation names used by posers (unique, with counts)
mkdir -p "$WORK/posers"
unzip -o -q "$JAR" 'assets/cobblemon/bedrock/pokemon/posers/*' -d "$WORK/posers" 2>/dev/null || true
grep -rhoE '"(animations|quirks|poseAnimations)"|q\.bedrock(_quirk|_primary|_stateful)?\([^)]*\)' "$WORK/posers" | sed -E "s/'[a-z0-9_]+',//" | sort | uniq -c | sort -rn | head -300 > "$OUT/poser-animation-usage.txt"
