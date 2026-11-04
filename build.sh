#!/usr/bin/env bash
# Builds both APKs and prints where they are. JDK 17 and Android SDK 34 needed; see BUILD.md.
set -euo pipefail
cd "$(dirname "$0")"

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
