#!/usr/bin/env bash
# Builds the native libraries and the signed, minified release APK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ "${SKIP_NATIVE:-0}" != "1" ]; then
    "$ROOT/scripts/build-native.sh"
fi

# The APK is removed first: incremental packaging can leave stale bytes behind
# when the payload shrinks between builds.
rm -rf "$ROOT/app/build/outputs/apk/release"
"$ROOT/scripts/gradle.sh" :app:assembleRelease

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
cp -f "$APK" "$ROOT/LlamaPort-release.apk"
echo "APK: $APK"
echo "Copy: $ROOT/LlamaPort-release.apk"
ls -la "$APK" "$ROOT/LlamaPort-release.apk"
