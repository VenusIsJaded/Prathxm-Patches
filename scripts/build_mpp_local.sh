#!/bin/bash
# Build a patches .mpp locally WITHOUT the Morphe Gradle plugin (which needs GitHub
# Packages credentials).
#
# The patch bytecode (patches/src/main/kotlin) of this release is unchanged, so the compiled
# patch classes are reused from the official release bundle. What is rebuilt:
#   - extensions/extension.mpe  (all Java extension code in this repo, compiled with javac + D8)
#   - stockfish binaries        (Stockfish 19, checksum verified)
#
# Usage: scripts/build_mpp_local.sh [version]
set -euo pipefail

VERSION="${1:-1.14.0}"
BASE_RELEASE="v1.13.1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK_DIR:-$HOME/mpp-build}"
OUT="$ROOT/out/patches-${VERSION}.mpp"

mkdir -p "$WORK" "$ROOT/out"
cd "$WORK"

fetch() { [ -s "$2" ] || curl -fsSL --retry 3 -o "$2" "$1"; }

echo "== Fetching tools"
fetch https://github.com/Sable/android-platforms/raw/master/android-30/android.jar android.jar
fetch https://dl.google.com/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar r8.jar
fetch https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar baksmali.jar
fetch https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar smali.jar
fetch "https://github.com/PrathxmOp/Prathxm-Patches/releases/download/${BASE_RELEASE}/patches-${BASE_RELEASE#v}.mpp" base.mpp

echo "== Fetching Stockfish 19"
mkdir -p sf && cd sf
fetch https://github.com/official-stockfish/Stockfish/releases/download/sf_19/stockfish-android-arm64-universal.tar.gz a64.tar.gz
fetch https://github.com/official-stockfish/Stockfish/releases/download/sf_19/stockfish-android-armv7-neon.tar.gz a7.tar.gz
echo "ebb24051aa4a222b4daaf049b882ecf1163d370c128fe02316602643f4d5e426  a64.tar.gz" | sha256sum -c -
echo "47c34963f1cdf4a6af34c1b5294f7a4e2679b3eb52698c324855af2c6486024b  a7.tar.gz" | sha256sum -c -
tar -xzf a64.tar.gz stockfish/stockfish-android-arm64-universal
tar -xzf a7.tar.gz stockfish/stockfish-android-armv7-neon
cd ..

echo "== Unpacking base bundle"
rm -rf bundle && mkdir bundle && (cd bundle && unzip -q ../base.mpp)

echo "== Keeping Kotlin runtime + R classes from the base extension"
rm -rf basesmali keep && java -jar baksmali.jar d bundle/extensions/extension.mpe -o basesmali
mkdir -p keep/app/prathxm/chess/extension
cp -r basesmali/kotlin basesmali/org keep/
cp basesmali/app/prathxm/chess/extension/R*.smali keep/app/prathxm/chess/extension/
java -jar smali.jar a keep -o keep.dex --api 26

echo "== Compiling extension"
rm -rf bc cls d8out && mkdir -p bc/app/prathxm/chess/extension cls d8out
cat > bc/app/prathxm/chess/extension/BuildConfig.java <<EOF
package app.prathxm.chess.extension;
public final class BuildConfig {
  public static final boolean DEBUG = false;
  public static final String APPLICATION_ID = "app.prathxm.chess.extension";
  public static final String BUILD_TYPE = "release";
  public static final int VERSION_CODE = -1;
  public static final String VERSION_NAME = "";
  public static final String PATCH_VERSION = "${VERSION}";
}
EOF
javac -nowarn --release 11 -cp android.jar -d cls \
  bc/app/prathxm/chess/extension/BuildConfig.java \
  $(find "$ROOT/extensions/extension/src/main/java" -name "*.java")
java -cp r8.jar com.android.tools.r8.D8 --release --min-api 26 --lib android.jar \
  --output d8out $(find cls -name "*.class") keep.dex

echo "== Verifying every extension method called by the patches exists"
rm -rf patchsmali newsmali
java -jar baksmali.jar d bundle/classes.dex -o patchsmali
java -jar baksmali.jar d d8out/classes.dex -o newsmali
missing=0
while read -r sig; do
  sig="${sig%n}"; sig="${sig%\\}"; cls="${sig%%;->*}"; m="${sig#*;->}"
  grep -qF " ${m%%(*}(${m#*(}" "newsmali/${cls#L}.smali" 2>/dev/null || { echo "MISSING: $sig"; missing=1; }
done < <(grep -rhoE 'Lapp/prathxm/chess/extension/[A-Za-z/]*;->[A-Za-z]*\([^)]*\)(\[*L[A-Za-z/$]*;|\[*[VZBSCIJFD])' patchsmali | sort -u)
[ "$missing" = 0 ] || { echo "Extension API mismatch"; exit 1; }

echo "== Assembling bundle"
cp d8out/classes.dex bundle/extensions/extension.mpe
cp sf/stockfish/stockfish-android-arm64-universal bundle/stockfish/arm64-v8a/stockfish
cp sf/stockfish/stockfish-android-armv7-neon bundle/stockfish/armeabi-v7a/stockfish
chmod +x bundle/stockfish/*/stockfish
sed -i "s/^Version: .*/Version: ${VERSION}\r/; s/^Timestamp: .*/Timestamp: $(date +%s)000\r/" bundle/META-INF/MANIFEST.MF

rm -f "$OUT"
(cd bundle && zip -q -X -r -D "$OUT" META-INF/MANIFEST.MF META-INF app classes.dex extensions stockfish util)
ls -la "$OUT"
echo "Built $OUT"
