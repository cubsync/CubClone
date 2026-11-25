#!/usr/bin/env bash
# 递增 dev.N：每出一次包跑一次（N = 这个版本号下第几次包）。
# 用法：bash app/bump.sh
set -eu
cd "$(dirname "${BASH_SOURCE[0]}")"
V=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
case "$V" in
  *-dev.*) ;;
  *) echo "当前不是 dev 版本（$V），不递增"; exit 1 ;;
esac
N=${V##*-dev.}
NEW="${V%-dev.*}-dev.$((N + 1))"
sed -i "s|android:versionName=\"$V\"|android:versionName=\"$NEW\"|" AndroidManifest.xml
echo "$V -> $NEW"
