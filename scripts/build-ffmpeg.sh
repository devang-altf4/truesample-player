#!/usr/bin/env bash
# Builds a small, decode-only, audio-only FFmpeg (LGPL-2.1) for Android arm64 and
# installs its headers and static libraries into third_party/ffmpeg/android/arm64-v8a.
#
# Usage: scripts/build-ffmpeg.sh [work-dir]
#   work-dir must not contain spaces (FFmpeg's configure does not support them).
# Needs: ANDROID_HOME with NDK 28.2.13676358, bash, curl, tar/xz, perl. On Windows,
# run from Git Bash; the NDK's own make.exe is used. No host tools are built, so the
# NDK clang also stands in as the host compiler for configure's checks.
set -euo pipefail

FFMPEG_VERSION=7.1.2
NDK_VERSION=28.2.13676358
API=26

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:-${TMPDIR:-/tmp}/ffmpeg-android}"
case "$WORK" in *" "*) echo "work dir must not contain spaces: $WORK" >&2; exit 1 ;; esac

SDK="${ANDROID_HOME:?set ANDROID_HOME}"
NDK="$SDK/ndk/$NDK_VERSION"
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) HOST=windows-x86_64; EXE=.exe; MAKE="$NDK/prebuilt/windows-x86_64/bin/make.exe" ;;
    Darwin) HOST=darwin-x86_64; EXE=; MAKE=make ;;
    *) HOST=linux-x86_64; EXE=; MAKE=make ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST"
TARGET="aarch64-linux-android$API"
PREFIX="$WORK/install"
OUT="$ROOT/third_party/ffmpeg/android/arm64-v8a"

mkdir -p "$WORK/tmp"
export TMPDIR="$WORK/tmp"  # configure needs a POSIX temp path (Windows TEMP uses backslashes)
cd "$WORK"
if [ ! -d "ffmpeg-$FFMPEG_VERSION" ]; then
    curl -sSfL -o "ffmpeg-$FFMPEG_VERSION.tar.xz" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
    tar xf "ffmpeg-$FFMPEG_VERSION.tar.xz"
fi
cd "ffmpeg-$FFMPEG_VERSION"

DEMUXERS=aac,aiff,ape,caf,dsf,flac,iff,matroska,mov,mp3,ogg,tak,w64,wav,wv
# Float MP3 decoders: the fixed-point ones output 16-bit ints that look lossless.
DECODERS=aac,alac,ape,dsd_lsbf,dsd_lsbf_planar,dsd_msbf,dsd_msbf_planar,flac,mp1float,mp2float,mp3float,opus,tak,vorbis,wavpack
DECODERS=$DECODERS,pcm_f32be,pcm_f32le,pcm_f64be,pcm_f64le,pcm_s16be,pcm_s16le,pcm_s24be,pcm_s24le
DECODERS=$DECODERS,pcm_s32be,pcm_s32le,pcm_u8
PARSERS=aac,flac,mpegaudio,opus,vorbis

./configure \
    --prefix="$PREFIX" \
    --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
    --cc="$TC/bin/clang$EXE" --cxx="$TC/bin/clang++$EXE" \
    --ar="$TC/bin/llvm-ar$EXE" --nm="$TC/bin/llvm-nm$EXE" --ranlib="$TC/bin/llvm-ranlib$EXE" \
    --strip="$TC/bin/llvm-strip$EXE" \
    --sysroot="$TC/sysroot" \
    --extra-cflags="--target=$TARGET -fPIC -O2" --extra-ldflags="--target=$TARGET" \
    --host-cc="$TC/bin/clang$EXE" --host-cflags="--target=$TARGET --sysroot=$TC/sysroot" \
    --host-ld="$TC/bin/clang$EXE" --host-ldflags="--target=$TARGET --sysroot=$TC/sysroot" \
    --enable-static --disable-shared --enable-pic \
    --disable-programs --disable-doc --disable-network --disable-autodetect \
    --disable-avdevice --disable-avfilter --disable-swscale --disable-postproc \
    --disable-everything \
    --enable-demuxer="$DEMUXERS" --enable-decoder="$DECODERS" --enable-parser="$PARSERS"

"$MAKE" -j"$(nproc)"
"$MAKE" install

rm -rf "$OUT"
mkdir -p "$OUT"
cp -r "$PREFIX/include" "$PREFIX/lib" "$OUT/"  # swresample is needed by the Opus decoder
rm -rf "$OUT/lib/pkgconfig"
cp COPYING.LGPLv2.1 "$ROOT/third_party/ffmpeg/COPYING.LGPLv2.1"
echo "FFmpeg $FFMPEG_VERSION built for arm64-v8a -> $OUT"
ls -la "$OUT/lib"
