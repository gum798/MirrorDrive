#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}"
HOST="$(uname | tr '[:upper:]' '[:lower:]')-x86_64"
TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST"
TARGET=aarch64-linux-android; API=24
ROOT="$(pwd)"
rm -rf build/libplist && git clone --depth 1 -b 2.6.0 https://github.com/libimobiledevice/libplist build/libplist
cd build/libplist && ./autogen.sh || true   # generates configure; may warn on host
for pair in "aarch64-linux-android:arm64-v8a" "armv7a-linux-androideabi:armeabi-v7a"; do
  TGT="${pair%%:*}"; ABI="${pair##*:}"
  PREFIX="$ROOT/app/src/main/cpp/prebuilt/$ABI"
  make distclean 2>/dev/null || true
  ./configure --host="$TARGET" --prefix="$PREFIX" \
    --without-cython --disable-shared --enable-static \
    CC="$TOOLCHAIN/bin/clang --target=$TGT$API" \
    CXX="$TOOLCHAIN/bin/clang++ --target=$TGT$API" \
    AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)" && make install
done
echo "libplist-2.0.a built"
