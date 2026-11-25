#!/usr/bin/env bash
# 构建 APK（无需 gradle）
# 中间件：build/app/   交付物：dist/CubClone-<versionName>.apk
set -eu
cd "$(dirname "${BASH_SOURCE[0]}")"

# 按 ABI 出包：assets/zstd 只有一份 zstd 会被打进包，别的架构带上也跑不了、白占 300~400 KB
# 用法：bash app/build.sh [arm64-v8a|universal|armeabi-v7a|x86_64]，不传 = arm64-v8a
#   业界惯例就两个包：arm64-v8a（绝大多数设备）+ universal（全架构一份，给老机/模拟器）
ABI="${1:-${ABI:-arm64-v8a}}"
case "$ABI" in all|universal) ABI=universal ;; esac

SDK="${ANDROID_HOME:-D:/Dev/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-36/android.jar"
JAVA_HOME="${JAVA_HOME:-D:/Dev/Java/Env/jdk-21.0.12.1+1}"
export JAVA_HOME

OUT=../build/app
DIST=../dist
JAR=../build/cubclone.jar

[ -f "$AJ" ] || { echo "缺 android.jar: $AJ"; exit 1; }
[ -f "$JAR" ] || { echo "缺 CLI jar: $JAR（先跑 proto/build.sh）"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/assets" "$OUT/classes" "$OUT/dex" "$OUT/gen"

cp -r assets/. "$OUT/assets/"
cp -f "$JAR" "$OUT/assets/cubclone.jar"

# 解锁模块（LSPosed APK）内嵌进主包，Root 功能页点「安装解锁模块」时直接 pm install。
# 模块与主应用版本解耦：dist/ 下取最新的那个，模块没更新时主应用发版不用重出它。
HOOK_APK=$(ls -t ../dist/CubCloneHook-*.apk 2>/dev/null | head -1)
if [ -n "$HOOK_APK" ]; then
  cp -f "$HOOK_APK" "$OUT/assets/CubCloneHook.apk"
  echo "    内嵌模块：$(basename "$HOOK_APK")"
else
  echo "    ⚠ 没有 dist/CubCloneHook-*.apk，解锁数量上限功能在本包里不可用"
fi

if [ "$ABI" != universal ]; then
  for f in "$OUT/assets/zstd-"*; do
    [ "$(basename "$f")" = "zstd-$ABI" ] || rm -f "$f"
  done
  [ -f "$OUT/assets/zstd-$ABI" ] || { echo "assets 里没有 zstd-$ABI"; exit 1; }
fi

echo "[0/7] 自检：UI 配色 + 中英资源"
# 背景色由应用自己按深浅色画，系统主题给的 Button/CheckBox 默认色不会跟着变；
# 这类「深色下按钮变黑字」的问题反复出现，编译前直接拦住（tools/check-ui-colors.py）。
PYEXE="${PYTHON_EXE:-D:/Dev/Python/Env/Python312/python.exe}"
if [ -f "$PYEXE" ]; then
  "$PYEXE" tools/check-ui-colors.py
  "$PYEXE" tools/check-i18n.py || exit 1
else
  echo "  跳过：找不到 python（$PYEXE），设 PYTHON_EXE 指过去再编"
fi

echo "[1/7] aapt2 compile"
"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"
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

# 构建标记：只给预发布/开发期包加 SemVer §5 的构建元数据（+<短提交号>，有未提交改动带 -dirty），
# 用来认出「设备上这一版是哪次提交」。正式版必须保持干净版本号——用户点「关于」看到的只能是 0.7.0。
if [ -n "$PRE" ]; then
  SHA=$(git -C .. rev-parse --short HEAD 2>/dev/null || echo nogit)
  # 提交哈希看不出先后，加一个单调递增的构建号：b167 一定比 b166 新。
  # 它只是 SemVer §5 的 build metadata，不占版本号、不影响 versionCode 与优先级。
  CNT=$(git -C .. rev-list --count HEAD 2>/dev/null || echo 0)
  if ! git -C .. diff --quiet 2>/dev/null; then SHA="$SHA-dirty"; fi
  BUILDV="$VNAME+b$CNT.$SHA"
  echo "    构建标记 $BUILDV"
else
  BUILDV="$VNAME"
  echo "    正式版，不加构建标记"
fi

# 源码里的清单只写干净版本号；带构建标记的副本只落在 build/，不污染 AndroidManifest.xml
sed "s|android:versionName=\"$VNAME\"|android:versionName=\"$BUILDV\"|" \
  AndroidManifest.xml > "$OUT/AndroidManifest.xml"

echo "[2/7] aapt2 link（出 base.apk + R.java）"
"$BT/aapt2.exe" link -o "$OUT/base.apk" -I "$AJ" \
  --manifest "$OUT/AndroidManifest.xml" -A "$OUT/assets" -R "$OUT/res.zip" \
  --auto-add-overlay \
  --java "$OUT/gen" \
  --version-code "$VCODE" --version-name "$BUILDV" \
  --min-sdk-version 29 --target-sdk-version 33

echo "[3/7] javac"
# Windows 的 javac 用 ; 分隔 classpath
CP="$AJ;libs/api-classes.jar;libs/provider-classes.jar"
# libs/gen 是 aidl 生成的 moe.shizuku.server 接口（ShizukuProvider 依赖）
"$JAVA_HOME/bin/javac.exe" -J-Dfile.encoding=UTF-8 -source 8 -target 8 -nowarn \
  -cp "$CP" -d "$OUT/classes" $(find src libs/gen "$OUT/gen" -name "*.java")

echo "[4/7] d8"
# class 文件逐个传参会超 Windows 命令行长度，先打包成 jar 再喂 d8
"$JAVA_HOME/bin/jar.exe" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8.bat" --lib "$AJ" --min-api 29 --output "$OUT/dex" \
  "$OUT/classes.jar" libs/api-classes.jar libs/provider-classes.jar

echo "[5/7] 塞 dex"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
"$JAVA_HOME/bin/jar.exe" uf "$OUT/base.apk" -C "$OUT" classes.dex

echo "[6/7] zipalign"
"$BT/zipalign.exe" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

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
mkdir -p "$DIST"
# 产物名与 versionName 完全一致：正式版 CubClone-0.7.0-arm64-v8a.apk，测试包带 +<短提交号>。
# 出新包就把同版本的旧测试包删掉，dist/ 里始终只有一份在用的包。
SUFFIX="-$ABI"
rm -f "$DIST"/CubClone-"$VNAME"+*"$SUFFIX".apk "$DIST"/CubClone-"$VNAME"+*"$SUFFIX".apk.idsig
APK="$DIST/CubClone-$BUILDV$SUFFIX.apk"
"$BT/apksigner.bat" sign --ks "$KS" --ks-key-alias "$AL" \
  --ks-pass "pass:$SP" --key-pass "pass:$KP" --out "$APK" "$OUT/aligned.apk"

ls -lh "$APK"
echo OK
