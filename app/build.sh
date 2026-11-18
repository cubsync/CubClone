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
# 正式版 0.7.0 -> 70000。预发布占用本版本号下 100 个槽之前的那段，保证「预发布 < 正式版」且单调：
#   dev.N 69901-69909 · alpha.N 69911-69929 · beta.N 69931-69959 · rc.N 69961-69989
VNAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
[ -n "$VNAME" ] || { echo "读不到 versionName"; exit 1; }
BASE="${VNAME%%-*}"; PRE=""
[ "$BASE" != "$VNAME" ] && PRE="${VNAME#*-}"
VCODE=$(printf '%s\n' "$BASE" | awk -v pre="$PRE" '{
  n = split($0, a, "[.]")
  maj = a[1]+0; min = (n>1 ? a[2]+0 : 0); pat = (n>2 ? a[3]+0 : 0)
  code = maj*1000000 + min*10000 + pat*100
  if (pre != "") {
    st = pre; sub(/\..*$/, "", st)
    k = match(pre, /[0-9]+$/) ? substr(pre, RSTART)+0 : 1
    if      (st == "dev")   { off = 100; lim = 9 }
    else if (st == "alpha") { off = 90;  lim = 19 }
    else if (st == "beta")  { off = 70;  lim = 29 }
    else if (st == "rc")    { off = 40;  lim = 29 }
    else { print "ERR" ; exit }
    if (k < 1 || k > lim) { print "ERR"; exit }
    code = code - off + k
  }
  printf "%d", code
}')
case "$VCODE" in ''|*[!0-9]*) echo "versionCode 计算失败: [$VCODE]（versionName=$VNAME，预发布后缀只认 dev/alpha/beta/rc）"; exit 1;; esac
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
