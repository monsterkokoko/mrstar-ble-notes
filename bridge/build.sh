#!/usr/bin/env bash
# Builds ble-bridge.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Toolchain lives outside the repo; override with ANDROID_BUILD=<dir with jdk17/ and sdk/>.
set -euo pipefail
cd "$(dirname "$0")"

TOOLS="${ANDROID_BUILD:-$HOME/android-build}"
# .bat wrappers in the SDK break on paths with spaces, so use the 8.3 short path on Windows.
command -v cygpath >/dev/null && TOOLS="$(cygpath -u "$(cygpath -d "$TOOLS")")"
export JAVA_HOME="$TOOLS/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$TOOLS/sdk/build-tools/34.0.0"
AJ="$TOOLS/sdk/platforms/android-27/android.jar"
KS="$TOOLS/ble-bridge.keystore"
EXE=""; [[ -f "$BT/aapt2.exe" ]] && EXE=".exe"
SEP=":"
if [[ -n "$EXE" ]]; then  # Windows (Git Bash): javac needs native paths in a ;-separated list
  SEP=";"; BT="$(cygpath -w "$BT")"; AJ="$(cygpath -w "$AJ")"; KS="$(cygpath -w "$KS")"
fi
BAT=""; [[ -f "$BT/d8.bat" ]] && BAT=".bat"

rm -rf build && mkdir -p build/classes build/dex

"$BT/aapt2$EXE" compile --dir res -o build/res.zip
"$BT/aapt2$EXE" link -o build/unsigned.apk -I "$AJ" --manifest AndroidManifest.xml build/res.zip

javac -source 8 -target 8 -bootclasspath "$AJ$SEP$BT/core-lambda-stubs.jar" -Xlint:-options -encoding UTF-8 \
  -d build/classes $(find src -name '*.java')

"$BT/d8$BAT" --release --min-api 26 --lib "$AJ" --output build/dex $(find build/classes -name '*.class')

(cd build/dex && "$BT/aapt$EXE" add -k ../unsigned.apk classes.dex >/dev/null)

"$BT/zipalign$EXE" -f 4 build/unsigned.apk build/aligned.apk

if [[ ! -f "$KS" ]]; then
  keytool -genkeypair -keystore "$KS" -alias bridge -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BLE Bridge" >/dev/null
fi
"$BT/apksigner$BAT" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out build/ble-bridge.apk build/aligned.apk

ls -la build/ble-bridge.apk
