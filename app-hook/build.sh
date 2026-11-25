#!/usr/bin/env bash
# 构建 CubClone Hook（LSPosed 模块 APK，libxposed API 102）
# 不依赖 Gradle，纯 aapt2 / javac / d8 / apksigner。
#
# 用法：bash app-hook/build.sh [版本号] [versionCode]
#   bash app-hook/build.sh              → 1.0.0 / 100
#   bash app-hook/build.sh 1.0.1 101
# 产物：dist/CubCloneHook-<版本>.apk
#
# 注意：这个包的版本号与主应用**无关**，它是独立发布的模块。
# 主应用只在 assets 里内嵌这一份产物；模块不更新时主应用发版不用重出它。
set -eu
cd "$(dirname "${BASH_SOURCE[0]}")"

SDK="${ANDROID_HOME:-D:/Dev/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-36/android.jar"
JAVA_HOME="${JAVA_HOME:-D:/Dev/Java/Env/jdk-21.0.12.1+1}"
PYEXE="${PYTHON_EXE:-D:/Dev/Python/Env/Python312/python.exe}"
export JAVA_HOME

VER="${1:-1.0.0}"
CODE="${2:-100}"
OUT=../build/hook
DIST=../dist

[ -f "$AJ" ] || { echo "缺 android.jar: $AJ"; exit 1; }
[ -f "libs/api-102.jar" ] || { echo "缺编译桩 libs/api-102.jar"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen" "$DIST"

# 模块版本常量：hook 启动时上报 sys.cubclone.hook.ver，主应用据此显示「运行中 1.0.7」。
# 重装 APK 但没重启时，运行中的还是旧值 —— 界面能据此提示「重启后生效」。
mkdir -p "$OUT/gen/com/cubsync/cubclone/hook"
cat > "$OUT/gen/com/cubsync/cubclone/hook/BuildVer.java" <<EOF
package com.cubsync.cubclone.hook;

/** build.sh 生成，勿手改 */
final class BuildVer {
    static final String V = "$VER";
    private BuildVer() { }
}
EOF

echo "[1/6] aapt2 compile"
"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2.exe" link -o "$OUT/base.apk" -I "$AJ" \
  --manifest AndroidManifest.xml -R "$OUT/res.zip" \
  --auto-add-overlay \
  --java "$OUT/gen" \
  --version-code "$CODE" --version-name "$VER" \
  --min-sdk-version 29 --target-sdk-version 35

echo "[3/6] javac"
# Windows 的 javac 用 ; 分隔 classpath
CP="$AJ;libs/api-102.jar;libs/annotation-androidx.jar;libs/annotation-libxposed.jar"
"$JAVA_HOME/bin/javac.exe" -J-Dfile.encoding=UTF-8 -source 17 -target 17 -nowarn \
  -cp "$CP" -d "$OUT/classes" $(find src "$OUT/gen" -name "*.java")

echo "[4/6] d8"
"$JAVA_HOME/bin/jar.exe" cf "$OUT/classes.jar" -C "$OUT/classes" .
# api-102.jar 只参与编译，运行期由框架提供，这里不喂给 d8
"$BT/d8.bat" --lib "$AJ" --min-api 29 --output "$OUT/dex" "$OUT/classes.jar"

echo "[5/6] 打包（classes.dex + META-INF/xposed）+ 对齐"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
# META-INF/xposed 必须在 APK 根目录（不是 assets/），所以不能走 aapt2 的 -A
"$PYEXE" - "$OUT/unaligned.apk" "$OUT/dex/classes.dex" "resources" <<'PY'
import os, sys, zipfile
apk, dex, resroot = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    for base, _dirs, files in os.walk(resroot):
        for name in files:
            full = os.path.join(base, name)
            arc = os.path.relpath(full, resroot).replace(os.sep, "/")
            z.write(full, arc)
            print("  +", arc)
PY
"$BT/zipalign.exe" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
PROPS="${CUBCLONE_SIGNING:-D:/Dev/!Key/cubclone-signing.properties}"
[ -f "$PROPS" ] || { echo "缺签名凭据: $PROPS"; exit 1; }
KS=$(sed -n 's/^storeFile=//p' "$PROPS")
SP=$(sed -n 's/^storePassword=//p' "$PROPS")
AL=$(sed -n 's/^keyAlias=//p' "$PROPS")
KP=$(sed -n 's/^keyPassword=//p' "$PROPS")
[ -n "$KS" ] && [ -f "$KS" ] || { echo "keystore 不存在: $KS"; exit 1; }
APK="$DIST/CubCloneHook-$VER.apk"
"$BT/apksigner.bat" sign --ks "$KS" --ks-key-alias "$AL" \
  --ks-pass "pass:$SP" --key-pass "pass:$KP" \
  --v4-signing-enabled false --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner.bat" verify --print-certs "$APK" | head -3
ls -lh "$APK"
echo OK
