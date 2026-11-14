#!/usr/bin/env bash
# Installs the debug APK, copies a GGUF model into the app storage and launches it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="$ROOT/.sdk/platform-tools/adb"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
MODEL="${1:-$ROOT/dl/qwen2.5-0.5b-instruct-q4_k_m.gguf}"
PKG="com.llamaport.app"

if [ ! -f "$APK" ]; then
    echo "APK not found: $APK (run scripts/build-apk.sh)" >&2
    exit 1
fi

"$ADB" wait-for-device
"$ADB" install -r "$APK"

if [ -f "$MODEL" ]; then
    name="$(basename "$MODEL")"
    echo "==> Copying $name to the device"
    "$ADB" push "$MODEL" /data/local/tmp/llamaport-model.gguf
    "$ADB" shell run-as "$PKG" mkdir -p files/models
    "$ADB" shell "run-as $PKG cp /data/local/tmp/llamaport-model.gguf files/models/$name"
    "$ADB" shell rm -f /data/local/tmp/llamaport-model.gguf
fi

"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/.MainActivity"
sleep 2
mkdir -p "$ROOT/build"
"$ADB" exec-out screencap -p > "$ROOT/build/screenshot.png"
echo "Screenshot: $ROOT/build/screenshot.png"
