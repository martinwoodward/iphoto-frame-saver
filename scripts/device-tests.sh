#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
TOOLS="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-34/android.jar"
ADB="${ADB:-adb}"
if [[ ! -f build/photo-frame-saver-debug.apk ]]; then echo "Build the debug app first." >&2; exit 1; fi
mkdir -p build/device-test/classes build/device-test/dex
"$TOOLS/aapt2" link -o build/device-test/resources.apk -I "$JAR" --manifest tests/android/AndroidManifest.xml
find tests/android -name '*.java' | sort > build/device-test/sources.txt
javac -source 8 -target 8 -Xlint:-options -cp "$JAR:build/classes" \
  -d build/device-test/classes @build/device-test/sources.txt
find build/device-test/classes -name '*.class' | sort > build/device-test/classes.txt
"$TOOLS/d8" --release --lib "$JAR" --classpath build/photo-frame-saver-debug.apk \
  --min-api 23 --output build/device-test/dex @build/device-test/classes.txt
cp build/device-test/resources.apk build/device-test/unsigned.apk
(cd build/device-test/dex && zip -q -u ../unsigned.apk classes*.dex)
"$TOOLS/zipalign" -f 4 build/device-test/unsigned.apk build/device-test/aligned.apk
"$TOOLS/apksigner" sign --ks build/signing/local.jks --ks-pass pass:changeit \
  --out build/device-test/tests.apk build/device-test/aligned.apk
"$ADB" install -r build/photo-frame-saver-debug.apk
"$ADB" install -r build/device-test/tests.apk
"$ADB" shell am start -n io.github.martinwoodward.iphotoframesaver.tests/io.github.martinwoodward.iphotoframesaver.GrantSyntheticStorage >/dev/null
sleep 1
OUTPUT="$("$ADB" shell am instrument -w -r io.github.martinwoodward.iphotoframesaver.tests/io.github.martinwoodward.iphotoframesaver.DeviceTests)"
printf '%s\n' "$OUTPUT"
if ! grep -q 'PASS:' <<< "$OUTPUT"; then exit 1; fi
