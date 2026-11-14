#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$ROOT/.sdk/ndk/28.2.13676358}"
ABI="${ABI:-arm64-v8a}"
API="${API:-26}"
JOBS="${JOBS:-$(nproc)}"

LLAMA_SRC="$ROOT/llama.cpp"
BUILD="$ROOT/build/native"
LIBS_OUT="$ROOT/app/src/main/jniLibs/$ABI"
LLAMA_BUILD="$BUILD/llama"

if [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
    echo "NDK not found at $NDK" >&2
    exit 1
fi
if [ ! -f "$LLAMA_SRC/include/llama.h" ]; then
    echo "llama.cpp sources not found at $LLAMA_SRC" >&2
    exit 1
fi

TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
COMMON=(
    -G Ninja
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN"
    -DANDROID_ABI="$ABI"
    -DANDROID_PLATFORM="android-$API"
    -DCMAKE_BUILD_TYPE=Release
)

mkdir -p "$BUILD" "$LIBS_OUT"
rm -f "$LIBS_OUT"/*.so

echo "==> Building llama.cpp for $ABI (API $API)"
cmake -S "$LLAMA_SRC" -B "$LLAMA_BUILD" "${COMMON[@]}" \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_OPENMP=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DGGML_CPU_ALL_VARIANTS=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DGGML_CPU_ARM_ARCH="armv8.2-a+dotprod+fp16" \
    -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_COMMON=OFF \
    -DLLAMA_BUILD_MTMD=ON
cmake --build "$LLAMA_BUILD" --target llama mtmd -j "$JOBS"

echo "==> Collecting shared libraries"
find "$LLAMA_BUILD" -name '*.so' -exec cp -v {} "$LIBS_OUT/" \;

echo "==> Building JNI wrapper"
cmake -S "$ROOT/app/src/main/cpp" -B "$BUILD/jni" "${COMMON[@]}" \
    -DLLAMA_INCLUDE_DIR="$LLAMA_SRC/include" \
    -DGGML_INCLUDE_DIR="$LLAMA_SRC/ggml/include" \
    -DLLAMA_LIB="$LIBS_OUT/libllama.so" \
    -DMTMD_INCLUDE_DIR="$LLAMA_SRC/tools/mtmd" \
    -DMTMD_LIB="$LIBS_OUT/libmtmd.so" \
    -DQUICKJS_DIR="$ROOT/third_party/quickjs"
cmake --build "$BUILD/jni" -j "$JOBS"

cp -v "$BUILD/jni/libllamaport.so" "$LIBS_OUT/"
cp -v "$BUILD/jni/libquickjs-jni.so" "$LIBS_OUT/"

STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
if [ -x "$STRIP" ]; then
    "$STRIP" --strip-unneeded "$LIBS_OUT/libllamaport.so" || true
    "$STRIP" --strip-unneeded "$LIBS_OUT/libquickjs-jni.so" || true
fi

echo "==> Done"
ls -la "$LIBS_OUT"
