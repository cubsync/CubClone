#!/usr/bin/env bash
# 编 Zygisk 原生模块（四个 ABI），产物放 native-hook/zygisk/<abi>.so
# 用法：bash native-hook/build.sh
set -eu
cd "$(dirname "${BASH_SOURCE[0]}")"

NDK="${ANDROID_NDK_HOME:-D:/Dev/Android/Sdk/ndk/29.0.14206865}"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -d "$BIN" ] || { echo "缺 NDK: $BIN"; exit 1; }

mkdir -p zygisk
for pair in "arm64-v8a aarch64-linux-android29" "armeabi-v7a armv7a-linux-androideabi29" \
            "x86_64 x86_64-linux-android29" "x86 i686-linux-android29"; do
  abi=${pair%% *}
  target=${pair##* }
  echo "[$abi] $target"
  "$BIN/${target}-clang++.cmd" -shared -fPIC -O2 -std=c++17 -fvisibility=hidden \
    -static-libstdc++ -Wl,--build-id=none -Wl,-soname,libcubclone_native.so \
    -o "zygisk/$abi.so" jni/module.cpp -llog
done
ls -lh zygisk/*.so
