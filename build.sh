#!/usr/bin/env bash
# Builds both APKs and prints where they are. JDK 17 and Android SDK 34 needed; see BUILD.md.
set -euo pipefail
cd "$(dirname "$0")"

# The source may ship as lean-tv-source.zip, with launcher/ and phone/ at its top level.
if [ ! -f launcher/app/build.gradle ] || [ ! -f phone/app/build.gradle ]; then
    if [ -f lean-tv-source.zip ]; then
        echo "unpacking lean-tv-source.zip"
        if command -v unzip >/dev/null 2>&1; then
            unzip -q -o lean-tv-source.zip
        else
            python3 -m zipfile -e lean-tv-source.zip .
        fi
    else
        echo "error: no launcher/ or phone/ here, and no lean-tv-source.zip to unpack" >&2
        exit 1
    fi
fi

[ -n "${JAVA_HOME:-}" ] || echo "note: JAVA_HOME is empty, using java from PATH"
[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] || echo "note: ANDROID_HOME is empty, so local.properties must carry sdk.dir"

echo "== Lean TV launcher (TV) =="
( cd launcher && sh ./gradlew --no-daemon assembleRelease )
echo
echo "== Lean TV Remote (phone) =="
( cd phone && sh ./gradlew --no-daemon assembleRelease )

echo
echo "== APKs =="
for f in launcher/app/build/outputs/apk/release/app-release.apk \
         phone/app/build/outputs/apk/release/app-release.apk; do
    if [ -f "$f" ]; then
        size=$(stat -c %s "$f" 2>/dev/null || stat -f %z "$f")
        echo "$size B  $f"
    else
        echo "missing  $f"
    fi
done
