#!/usr/bin/env bash
# Convenience wrapper around the project-local Gradle distribution.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export JAVA_HOME="$ROOT/toolchain/jdk"
export GRADLE_USER_HOME="$ROOT/.gradle-home"
export ANDROID_HOME="$ROOT/.sdk"
export ANDROID_SDK_ROOT="$ROOT/.sdk"
export ANDROID_USER_HOME="$ROOT/.android"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

exec "$ROOT/toolchain/gradle-8.9/bin/gradle" --no-daemon -p "$ROOT" "$@"
