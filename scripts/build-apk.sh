#!/usr/bin/env bash
# Builds the native libraries and the debug APK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ "${SKIP_NATIVE:-0}" != "1" ]; then
    "$ROOT/scripts/build-native.sh"
fi

# The APK is removed first: incremental packaging can leave stale bytes behind
# when the payload shrinks between builds.
rm -rf "$ROOT/app/build/outputs/apk"
"$ROOT/scripts/gradle.sh" :app:assembleDebug

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
cp -f "$APK" "$ROOT/LlamaPort-debug.apk"
echo "APK: $APK"
echo "Copy: $ROOT/LlamaPort-debug.apk"
ls -la "$APK" "$ROOT/LlamaPort-debug.apk"
