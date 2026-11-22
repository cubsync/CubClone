#!/usr/bin/env bash
# 装完包冒烟：把每个自绘页面都真实打开一遍，看有没有闪退。
#
# 为什么需要：构建只有 aapt2 + javac，没有 lint / 运行时检查。
# `setBackgroundResource(android.R.attr.xxx)` 这种「编译过、一跑就 Resources$NotFoundException」
# 的错误只能靠真机打开才发现（11-22 批量删除页就这么闪退过一次，还是用户点出来的）。
#
# 用法：bash app/tools/smoke.sh [设备序列号]
#   有输出的行 = 出问题；全部 OK 会打印 "全部通过"。
set -u

SERIAL="${1:-}"
ADB="adb"
[ -n "$SERIAL" ] && ADB="adb -s $SERIAL"
PKG="com.cubsync.cubclone"

# 需要参数的页面给一份最小参数，保证 onCreate 走完整流程
ACTIVITIES=(
  ".MainActivity"
  ".SettingsActivity"
  ".BackupActivity"
  ".BatchBackupActivity"
  ".RemoveClonesActivity"
  ".RootActivity"
)

$ADB shell "su -c 'logcat -c'" >/dev/null 2>&1 || $ADB logcat -c >/dev/null 2>&1

# 锁屏时 am start 照样报成功、界面却盖着锁屏，冒烟会白跑一遍还看不出问题
if ! $ADB shell "dumpsys window" | grep -q 'mDreamingLockscreen=false'; then
  echo "设备锁着，先解锁再跑（BFU/AFU 都可能是锁屏态）"
  exit 2
fi

fail=0
for A in "${ACTIVITIES[@]}"; do
  $ADB shell "su -c 'am start -W -n $PKG/$A'" >/dev/null 2>&1
  sleep 3
  # 1) 页面必须在最前
  top=$($ADB shell "dumpsys activity activities | grep -m1 'ResumedActivity'")
  case "$top" in
    *"$PKG/$A"*) ;;
    *)
      echo "✗ $A 没起来：$top"
      fail=$((fail + 1))
      # 打印这次启动的崩溃栈，直接指出行号
      $ADB shell "su -c 'logcat -d -b crash | tail -25'"
      continue
      ;;
  esac
  # 2) 崩溃缓冲区不能有新记录
  if $ADB shell "su -c 'logcat -d -b crash'" 2>/dev/null | grep -q "$PKG"; then
    echo "✗ $A 启动了但崩溃缓冲区有新记录："
    $ADB shell "su -c 'logcat -d -b crash | tail -25'"
    fail=$((fail + 1))
  else
    echo "✓ $A"
  fi
done

# 收尾：回到桌面并息屏，别把屏幕留在应用里
$ADB shell input keyevent KEYCODE_HOME >/dev/null 2>&1
$ADB shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1

if [ "$fail" -gt 0 ]; then
  echo "冒烟失败：$fail 个页面有问题"
  exit 1
fi
echo "全部通过"
