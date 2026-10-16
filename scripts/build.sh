#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" || ! -f "$SDK/platforms/android-34/android.jar" ]]; then
  echo "Set ANDROID_SDK_ROOT to an SDK with platforms;android-34 and build-tools;36.0.0." >&2
  exit 1
fi
TOOLS="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-34/android.jar"
MODE="${1:-release}"
if [[ "$MODE" != release && "$MODE" != debug ]]; then echo "Usage: scripts/build.sh [release|debug]" >&2; exit 1; fi
mkdir -p build/res build/classes build/dex build/signing
find build/res build/classes build/dex -type f -delete
cp app/src/main/AndroidManifest.xml build/AndroidManifest.xml
if [[ "$MODE" == debug ]]; then
  sed 's/<application /<application android:debuggable="true" /' app/src/main/AndroidManifest.xml > build/AndroidManifest.xml
fi
"$TOOLS/aapt2" compile --dir app/src/main/res -o build/res/
"$TOOLS/aapt2" link -o build/resources.apk -I "$JAR" --manifest build/AndroidManifest.xml \
  --java build/generated --auto-add-overlay build/res/*.flat
find app/src/main/java build/generated -name '*.java' | sort > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -Xlint:unchecked -classpath "$JAR" \
  -d build/classes @build/sources.txt
find build/classes -name '*.class' | sort > build/classes.txt
"$TOOLS/d8" --release --lib "$JAR" --min-api 23 --output build/dex @build/classes.txt
cp build/resources.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes*.dex)
"$TOOLS/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
KEYSTORE="${FRAME_KEYSTORE:-build/signing/local.jks}"
ALIAS="${FRAME_KEY_ALIAS:-frame}"
if [[ ! -f "$KEYSTORE" ]]; then
  if [[ -n "${FRAME_KEYSTORE:-}" ]]; then echo "Signing key not found." >&2; exit 1; fi
  keytool -genkeypair -keystore "$KEYSTORE" -storepass changeit -keypass changeit -alias "$ALIAS" \
    -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Photo Frame Saver local build" >/dev/null 2>&1
fi
export FRAME_SIGNING_PASSWORD="${FRAME_SIGNING_PASSWORD:-changeit}"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" --ks-pass env:FRAME_SIGNING_PASSWORD \
  --key-pass env:FRAME_SIGNING_PASSWORD --out "build/photo-frame-saver-$MODE.apk" build/aligned.apk
"$TOOLS/apksigner" verify --min-sdk-version 23 "build/photo-frame-saver-$MODE.apk"
echo "Built build/photo-frame-saver-$MODE.apk"
