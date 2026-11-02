#!/usr/bin/env bash
# 把 APK 推到所有在线设备（默认只装版本不一样的，避免白折腾无线调试）。
#
# ⚠⚠ 无线调试那几台（ESP / 一加）上跑着 Shizuku，它的服务是靠 adb 的 shell 会话起的：
#     `adb disconnect` / `kill-server` / `tcpip` / 反复 `connect` 都会把会话换掉 ⇒ Shizuku 掉线，
#     而且掉了只能上手机手动重启服务，主机这边救不回来。
#     所以本脚本的纪律：
#       ① 只装不改连接：装完不断开，绝不 kill-server/adb tcpip；
#       ② 一台设备最多补一次 connect（仅当它不在线），端口用 mdns 现找；
#       ③ 版本已经一样就跳过，不产生任何 install；
#       ④ 统一用同一套 adb.exe（混用不同 client 会让 server 重启，等于给所有人断线）。
set -u
export MSYS_NO_PATHCONV=1

ADB="D:/Dev/Toolchain/platform-tools/adb.exe"
AAPT="D:/Dev/Android/Sdk/build-tools/35.0.0/aapt2.exe"
APK="${1:-D:/Dev/Workspaces/WorkBuddy/SelfDeviceCheck/moto-port/apk-src/应用分身.apk}"
PKG="com.cubsync.cubclone"

[ -f "$APK" ] || { echo "找不到 APK: $APK"; exit 1; }
"$ADB" start-server >/dev/null 2>&1

WANT=$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -1)
[ -n "$WANT" ] || WANT="?"

# 无线调试：屏幕一黑就掉、端口还会变，只给「不在线」的补一次 connect
for ip in 10.5.9.51 10.5.9.104; do
  if ! "$ADB" devices | awk -v ip="$ip" '$1 ~ "^"ip":" { if ($2=="device") ok=1 } END { exit ok?0:1 }'; then
    port=$("$ADB" mdns services 2>/dev/null | grep "_adb-tls-connect" | grep "$ip" \
           | awk '{print $3}' | cut -d: -f2 | head -1)
    if [ -n "${port:-}" ]; then
      echo "重连 $ip:$port"
      "$ADB" connect "$ip:$port" >/dev/null 2>&1
    fi
  fi
done

mapfile -t DEVS < <("$ADB" devices | awk '$2=="device"{print $1}')
echo "在线设备：${DEVS[*]:-无}   目标版本 $WANT"

for d in "${DEVS[@]}"; do
  printf "%-24s " "$d"
  cur=$("$ADB" -s "$d" shell "dumpsys package $PKG | grep -m1 versionName" 2>/dev/null | tr -d '\r' | awk -F= '{print $2}')
  if [ "${cur:-}" = "$WANT" ]; then
    echo "已是 $cur，跳过"
    continue
  fi
  # ⚠ 一定要 --user 0：`pm install` 不给 --user 就是装给「所有用户」，
  #   于是每装一次我们的 APK，ESP/一加 的每个分身空间里也白得一份。
  #   增量安装（默认）会挂一条常驻 socket，主机侧一撤就带走整条 transport ⇒ Shizuku 掉，
  #   所以走 push + pm install --user 0。
  "$ADB" -s "$d" push "$APK" /data/local/tmp/wbclone-new.apk >/dev/null 2>&1
  "$ADB" -s "$d" shell "pm install -r --user 0 /data/local/tmp/wbclone-new.apk" 2>&1 | tail -1
  "$ADB" -s "$d" shell "rm -f /data/local/tmp/wbclone-new.apk" >/dev/null 2>&1
done

echo "--- 版本核对"
for d in "${DEVS[@]}"; do
  printf "%-24s " "$d"
  "$ADB" -s "$d" shell "dumpsys package $PKG | grep -m1 versionName" 2>/dev/null | tr -d '\r' | tail -1
done
