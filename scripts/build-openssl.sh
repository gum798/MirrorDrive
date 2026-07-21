#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}"
HOST="$(uname | tr '[:upper:]' '[:lower:]')-x86_64"   # darwin-x86_64 even on Apple Silicon
TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST"
API=24
ROOT="$(pwd)"
rm -rf build/openssl && git clone --depth 1 -b openssl-3.5 https://github.com/openssl/openssl build/openssl
cd build/openssl
for pair in "android-arm64:arm64-v8a" "android-arm:armeabi-v7a"; do
  CONF="${pair%%:*}"; ABI="${pair##*:}"
  PREFIX="$ROOT/app/src/main/cpp/prebuilt/$ABI"
  make clean 2>/dev/null || true
  PATH="$TOOLCHAIN/bin:$PATH" ./Configure "$CONF" -D__ANDROID_API__=$API \
    no-shared no-tests no-apps --prefix="$PREFIX"
  PATH="$TOOLCHAIN/bin:$PATH" make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
  PATH="$TOOLCHAIN/bin:$PATH" make install_sw
done
echo "OpenSSL libcrypto.a built for all ABIs"
