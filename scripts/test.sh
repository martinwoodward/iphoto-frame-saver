#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/test-deps build/test-classes
JSON=build/test-deps/json-20240303.jar
if [[ ! -f "$JSON" ]]; then
  curl -fsSL https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$JSON"
fi
EXPECTED=3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
ACTUAL="$(shasum -a 256 "$JSON" | cut -d' ' -f1)"
if [[ "$ACTUAL" != "$EXPECTED" ]]; then echo "Test dependency checksum mismatch." >&2; exit 1; fi
find app/src/main/java/io/github/martinwoodward/iphotoframesaver/core tests/core -name '*.java' | sort > build/test-sources.txt
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$JSON" -d build/test-classes @build/test-sources.txt
java -ea -cp "build/test-classes:$JSON" io.github.martinwoodward.iphotoframesaver.core.CoreTests
