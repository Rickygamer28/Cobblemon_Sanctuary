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

# Water types and how they move (to check which ones count as "lives only in water")
mkdir -p "$WORK/species"
unzip -o -q "$JAR" 'data/cobblemon/species/*' -d "$WORK/species" 2>/dev/null || true
python3 - "$WORK/species" > "$OUT/water-movers.txt" <<'PY' || true
import json, os, sys
rows = []
for root, _, files in os.walk(sys.argv[1]):
    for f in files:
        if not f.endswith('.json'): continue
        try: d = json.load(open(os.path.join(root, f)))
        except Exception: continue
        types = [d.get('primaryType'), d.get('secondaryType')]
        if 'water' not in types: continue
        m = (d.get('behaviour') or {}).get('moving') or {}
        w = m.get('walk') or {}; s = m.get('swim') or {}; fl = m.get('fly') or {}
        canWalk = w.get('canWalk', True); avoidsLand = w.get('avoidsLand', False)
        canSwim = s.get('canSwimInWater', True); avoidsWater = s.get('avoidsWater', False); canFly = fl.get('canFly', False)
        landless = (not canWalk) or avoidsLand
        rows.append((d.get('name', f), '/'.join(t for t in types if t), landless, canFly, canSwim and not avoidsWater))
rows.sort()
print('name | types | landless | canFly | swims | old-rule | new-rule')
for n, t, l, fly, sw in rows:
    if l or fly:
        print(f"{n} | {t} | {l} | {fly} | {sw} | {l} | {l and sw and not fly}")
PY

# how Cobblemon's tumblestone and gem clusters are set up (blockstates, models, classes)
mkdir -p "$OUT/raw/clusters"
unzip -o -q "$JAR" 'assets/cobblemon/blockstates/*tumblestone*' 'assets/cobblemon/blockstates/*gem_cluster*' -d "$OUT/raw/clusters" 2>/dev/null || true
grep -E 'block/[A-Za-z]*(Tumblestone|Cluster|Gem)' "$OUT/jar-classes.txt" > "$OUT/raw/clusters/classes.txt" || true

# other mods to look at for compatibility (Modrinth slugs, NeoForge 1.21.1 builds): their classes decompiled whole
if [ -f .ci/compat-mods.txt ]; then
  while IFS= read -r slug; do
    [ -z "$slug" ] && continue
    case "$slug" in \#*) continue;; esac
    # a line is a search: the first mod found is used
    Q=$(printf '%s' "$slug" | jq -sRr @uri)
    PID=$(curl -fsSL "https://api.modrinth.com/v2/search?query=$Q&facets=%5B%5B%22project_type%3Amod%22%5D%5D" | jq -r '.hits[0].project_id')
    echo "$slug -> $PID ($(curl -fsSL "https://api.modrinth.com/v2/project/$PID" | jq -r '.slug + " / " + .title'))" >> "$OUT/compat.log"
    URL=$(curl -fsSL "https://api.modrinth.com/v2/project/$PID/version?game_versions=%5B%221.21.1%22%5D" | jq -r '([.[] | select(.loaders | index("neoforge"))] + .)[0].files[0].url')
    slug=$(printf '%s' "$slug" | tr ' ' '-' | tr 'A-Z' 'a-z')
    [ -z "$URL" ] || [ "$URL" = "null" ] && { echo "$slug: no build" >> "$OUT/compat.log"; continue; }
    D=$(mktemp -d); curl -fsSL -o "$D/mod.jar" "$URL"
    mkdir -p "$D/classes" "$OUT/compat/$slug"
    unzip -Z1 "$D/mod.jar" > "$OUT/compat/$slug/files.txt"
    unzip -o -q "$D/mod.jar" '*.class' -d "$D/classes" 2>/dev/null
    # big mods: only the classes whose path matches the filter line in compat-filter-<slug>.txt, if there is one
    if [ -f ".ci/compat-filter-$slug.txt" ]; then
      find "$D/classes" -name '*.class' | grep -v -E -f ".ci/compat-filter-$slug.txt" | xargs -r rm -f
    fi
    unzip -o -q "$D/mod.jar" '*.json' '*.toml' -x 'assets/*' 'data/*/loot_table/*' -d "$OUT/compat/$slug/meta" 2>/dev/null
    timeout 300 java -jar "$WORK/vineflower.jar" -dgs=1 -log=ERROR "$D/classes" "$OUT/compat/$slug/src" >> "$OUT/compat.log" 2>&1 || echo "$slug: vineflower exit $?" >> "$OUT/compat.log"
    echo "$slug: $URL" >> "$OUT/compat.log"
  done < .ci/compat-mods.txt
fi
