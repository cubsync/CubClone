#!/system/bin/sh
# 一次性迁移：把老模块（2.x，关 sdcardfs 走纯 FUSE）改过的媒体文件属主改回 media_rw。
#
# 老模块的做法是 resetprop external_storage.sdcardfs.enabled=false，再把 /data/media 下
# 原本属于 media_rw(1023) 的文件 chown 成媒体存储进程的 uid。新版不再关 sdcardfs，
# 撤掉老模块后那些文件属主不对，sdcardfs 视图下应用读写会被拒。这里只做属主回迁，
# 不动内容、不动位置、不碰 Android/data 与 Android/obb（那是各应用自己的数据目录）。
#
# 只在「内核带 sdcardfs」且「跑过老模块」的机器上执行一次，其余情况直接写标记退出。

LOG=/data/adb/cubclone_sdcardfs_fix.log
MARK=/data/adb/cubclone_sdcardfs_fix.reverted
say() { echo "$(date '+%F %T') [migrate] $*" >> "$LOG"; }

# 版本标记：应用零权限 getprop 就能判断模块装没装、是不是最新
RP=""
for p in /data/adb/ap/bin/resetprop /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop; do
  [ -x "$p" ] && { RP="$p"; break; }
done
[ -z "$RP" ] && RP=$(command -v resetprop 2>/dev/null)
if [ -n "$RP" ]; then
  "$RP" sys.cubclone.module.code 9
  "$RP" sys.cubclone.module.version 3.0.0
else
  setprop sys.cubclone.module.code 9 2>/dev/null
  setprop sys.cubclone.module.version 3.0.0 2>/dev/null
fi

[ -f "$MARK" ] && exit 0

if ! grep -q sdcardfs /proc/filesystems 2>/dev/null; then
  say "无 sdcardfs，无需回迁"
  touch "$MARK"
  exit 0
fi

if [ ! -f /data/adb/cubclone-sdcardfs-fix.log ]; then
  say "未见老模块痕迹，无需回迁"
  touch "$MARK"
  exit 0
fi

n=0
for d in /data/media/*/; do
  [ -d "$d" ] || continue
  u=$(basename "$d")
  case "$u" in ''|*[!0-9]*) continue;; esac
  mp=$((u * 100000 + 10155))          # 该用户的媒体存储进程 uid
  c=$(find "$d" -mindepth 1 \
        \( -path "$d/Android/data" -o -path "$d/Android/obb" \) -prune -o \
        -user "$mp" -print 2>/dev/null | wc -l)
  [ "$c" -eq 0 ] && continue
  find "$d" -mindepth 1 \
        \( -path "$d/Android/data" -o -path "$d/Android/obb" \) -prune -o \
        -user "$mp" -exec chown 1023:1023 {} + 2>/dev/null
  say "用户 $u 回迁 $c 项"
  n=$((n + c))
done

say "完成，共 $n 项"
touch "$MARK"
