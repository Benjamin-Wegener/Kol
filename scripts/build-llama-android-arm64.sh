#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THIRD_PARTY_DIR="$ROOT_DIR/third_party"
LLAMA_DIR="$THIRD_PARTY_DIR/llama.cpp"
APP_JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
BUILD_DIR="$THIRD_PARTY_DIR/llama-build-android-arm64"
ANDROID_NDK="${ANDROID_NDK:-/Users/user/Library/Android/sdk/ndk/28.2.13676358}"

mkdir -p "$THIRD_PARTY_DIR"

if [ ! -d "$LLAMA_DIR/.git" ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp "$LLAMA_DIR"
fi

rm -rf "$BUILD_DIR"
cmake -S "$ROOT_DIR/app/src/main/cpp" -B "$BUILD_DIR" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DLLAMA_CPP_SOURCE_DIR="$LLAMA_DIR" \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_ACCELERATE=OFF \
  -DGGML_BLAS=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=OFF

cmake --build "$BUILD_DIR" --target llama-android -j"$(sysctl -n hw.ncpu)"

mkdir -p "$APP_JNI_DIR"
find "$BUILD_DIR" -name 'libllama*.so' -o -name 'libggml*.so' | while read -r lib; do
  cp -fv "$lib" "$APP_JNI_DIR/"
done
