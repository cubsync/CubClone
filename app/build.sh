#!/usr/bin/env bash
# 构建 分身空间.apk —— 不用 gradle
set -eu
cd "$(dirname "${BASH_SOURCE[0]}")"

SDK="${ANDROID_HOME:-D:/Dev/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-36/android.jar"
JAVA_HOME="${JAVA_HOME:-D:/Dev/Java/Env/jdk-21.0.12.1+1}"
export JAVA_HOME

[ -f "$AJ" ] || { echo "缺 android.jar: $AJ"; exit 1; }

mkdir -p assets
cp -f ../proto/cubclone.jar assets/cubclone.jar
rm -rf out
mkdir -p out/classes out/dex out/gen out/res

echo "[1/7] aapt2 compile"
"$BT/aapt2.exe" compile --dir res -o out/res.zip

# 版本号唯一真源＝AndroidManifest 的 versionName；versionCode 由它推导，见 docs/VERSIONING.md
VNAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
VCODE=$(printf '%s' "$VNAME" | awk -F. '{maj=$1+0; min=$2+0; pat=($3==""?0:$3+0);
  printf "%d", maj*1000000 + min*10000 + pat*100}')
[ -n "$VNAME" ] && [ -n "$VCODE" ] || { echo "读不到 versionName: [$VNAME]"; exit 1; }
echo "    版本 $VNAME  versionCode $VCODE（由 versionName 推导）"

echo "[2/7] aapt2 link（出 base.apk + R.java）"
"$BT/aapt2.exe" link -o out/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml -A assets -R out/res.zip \
  --auto-add-overlay \
  --java out/gen \
  --version-code "$VCODE" --version-name "$VNAME" \
  --min-sdk-version 29 --target-sdk-version 33

echo "[3/7] javac"
# Windows 的 javac 用 ; 分隔 classpath
CP="$AJ;libs/api-classes.jar;libs/provider-classes.jar"
# libs/gen 是 aidl 生成的 moe.shizuku.server 接口（ShizukuProvider 依赖）
"$JAVA_HOME/bin/javac.exe" -J-Dfile.encoding=UTF-8 -source 8 -target 8 -nowarn \
  -cp "$CP" -d out/classes $(find src libs/gen out/gen -name "*.java")

echo "[4/7] d8"
# class 文件逐个传参会超 Windows 命令行长度，先打包成 jar 再喂 d8
"$JAVA_HOME/bin/jar.exe" cf out/classes.jar -C out/classes .
"$BT/d8.bat" --lib "$AJ" --min-api 29 --output out/dex \
  out/classes.jar libs/api-classes.jar libs/provider-classes.jar

echo "[5/7] 塞 dex"
cp out/dex/classes.dex out/classes.dex
"$JAVA_HOME/bin/jar.exe" uf out/base.apk -C out classes.dex

echo "[6/7] zipalign"
"$BT/zipalign.exe" -f 4 out/base.apk out/aligned.apk

echo "[7/7] 签名"
# 签名凭据在本机（⛔不进仓库）：storeFile/storePassword/keyAlias/keyPassword
# 默认 D:/Dev/!Key/cubclone-signing.properties，可用环境变量 CUBCLONE_SIGNING 覆盖
PROPS="${CUBCLONE_SIGNING:-D:/Dev/!Key/cubclone-signing.properties}"
[ -f "$PROPS" ] || { echo "缺签名凭据: $PROPS"; exit 1; }
KS=$(sed -n 's/^storeFile=//p' "$PROPS")
SP=$(sed -n 's/^storePassword=//p' "$PROPS")
AL=$(sed -n 's/^keyAlias=//p' "$PROPS")
KP=$(sed -n 's/^keyPassword=//p' "$PROPS")
[ -n "$KS" ] && [ -f "$KS" ] || { echo "keystore 不存在: $KS"; exit 1; }
"$BT/apksigner.bat" sign --ks "$KS" --ks-key-alias "$AL" \
  --ks-pass "pass:$SP" --key-pass "pass:$KP" --out "分身空间.apk" out/aligned.apk

ls -lh "分身空间.apk"
echo OK
