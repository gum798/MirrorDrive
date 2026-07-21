#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}"
HOST="$(uname | tr '[:upper:]' '[:lower:]')-x86_64"
TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST"
API=24; ROOT="$(pwd)"
rm -rf build/fdk-aac && git clone --depth 1 -b v2.0.3 https://github.com/mstorsjo/fdk-aac build/fdk-aac
cd build/fdk-aac && ./autogen.sh

# MirrorDrive: lpp_tran.cpp emits an Android SafetyNet diagnostic via
# android_errorWriteLog(), pulling in the platform-internal <log/log.h> that the NDK
# does not ship. Neutralize it for standalone NDK builds — it is a no-op diagnostic,
# not part of AAC decoding. (Only this one file is affected.)
sed -i '' -e '/#include "log\/log\.h"/d' \
          -e 's/android_errorWriteLog([^;]*);/(void)0;/g' \
          libSBRdec/src/lpp_tran.cpp

for pair in "aarch64-linux-android:arm64-v8a" "armv7a-linux-androideabi:armeabi-v7a"; do
  TGT="${pair%%:*}"; ABI="${pair##*:}"
  PREFIX="$ROOT/app/src/main/cpp/prebuilt/$ABI"
  make distclean 2>/dev/null || true
  ./configure --host=aarch64-linux-android --prefix="$PREFIX" --disable-shared --enable-static \
    CC="$TOOLCHAIN/bin/clang --target=$TGT$API" \
    CXX="$TOOLCHAIN/bin/clang++ --target=$TGT$API" \
    AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)" && make install
done
echo "libfdk-aac.a built"
