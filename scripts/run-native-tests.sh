#!/usr/bin/env bash
# Builds the UAC core unit tests with the NDK and runs them on the connected phone.
# Usage: scripts/run-native-tests.sh [adb serial]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
NDK="$SDK/ndk/28.2.13676358"
CMAKE_BIN="$SDK/cmake/3.22.1/bin"
BUILD="$ROOT/build/native-tests"
ADB=("$SDK/platform-tools/adb")
if [ $# -ge 1 ]; then ADB+=(-s "$1"); fi

"$CMAKE_BIN/cmake" -S "$ROOT/usbaudio/src/test/cpp" -B "$BUILD" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Debug >/dev/null
"$CMAKE_BIN/cmake" --build "$BUILD"

LOCAL_BIN="$BUILD/uac_tests"
command -v cygpath >/dev/null && LOCAL_BIN="$(cygpath -w "$LOCAL_BIN")"
MSYS_NO_PATHCONV=1 "${ADB[@]}" push "$LOCAL_BIN" /data/local/tmp/uac_tests >/dev/null
MSYS_NO_PATHCONV=1 "${ADB[@]}" shell "chmod 755 /data/local/tmp/uac_tests && /data/local/tmp/uac_tests"
