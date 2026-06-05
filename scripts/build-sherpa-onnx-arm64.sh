#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THIRD_PARTY_DIR="$ROOT_DIR/third_party"
SHERPA_DIR="$THIRD_PARTY_DIR/sherpa-onnx"
APP_JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"

mkdir -p "$THIRD_PARTY_DIR"

if [ ! -d "$SHERPA_DIR/.git" ]; then
  git clone --depth 1 https://github.com/k2-fsa/sherpa-onnx "$SHERPA_DIR"
fi

export ANDROID_NDK="${ANDROID_NDK:-/Users/user/Library/Android/sdk/ndk/28.2.13676358}"

if [ -n "${QNN_SDK_ROOT:-}" ] && [ -d "$QNN_SDK_ROOT" ]; then
  export SHERPA_ONNX_ENABLE_QNN=ON
  export SHERPA_ONNX_ENABLE_BINARY=ON
else
  export SHERPA_ONNX_ENABLE_QNN=OFF
  export SHERPA_ONNX_ENABLE_BINARY=OFF
fi

pushd "$SHERPA_DIR" >/dev/null
# Fix "CMake Error: The current CMakeCache.txt directory ... is different than the directory ..."
# This happens if the project was moved.
if [ -f "build-android-arm64-v8a/CMakeCache.txt" ]; then
  if ! grep -q "CMAKE_CACHEFILE_DIR:INTERNAL=$(pwd)/build-android-arm64-v8a" "build-android-arm64-v8a/CMakeCache.txt"; then
    echo "Stale CMakeCache.txt found (likely project was moved). Cleaning build directory..."
    rm -rf "build-android-arm64-v8a"
  fi
fi

./build-android-arm64-v8a.sh
popd >/dev/null

mkdir -p "$APP_JNI_DIR"
cp -fv "$SHERPA_DIR/build-android-arm64-v8a/install/lib/libonnxruntime.so" "$APP_JNI_DIR/"
