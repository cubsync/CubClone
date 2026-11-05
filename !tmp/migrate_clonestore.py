#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性把 MainActivity 里已迁到 CloneStore 的业务方法/字段删除，并改引用。"""
import re, sys

path = r"D:\Dev\Workspaces\WorkBuddy\cubsync\CubClone\app\src\com\cubsync\cubclone\MainActivity.java"
with open(path, encoding="utf-8") as f:
    text = f.read()

orig_len = len(text)

# ---- 删除块：每块 (start_marker, end_marker)，end_marker 取到该块最后一行末尾 ----
blocks = [
    # 块7 pickReusableSpace + reusableSpaces
    ("    /**\n     * 找一个可以装下该应用的分身空间。",
     "        return out;\n    }\n"),
    # 块6 SPACE_NAMES .. listSpaceUids
    ("    /** 分身空间名字缓存：uid → 系统里的名字",
     "        return ids;\n    }\n"),
    # 块5 restoreSpace
    ("    /**\n     * 反向操作：把主身份里的系统应用重新装回分身空间。",
     "                + \"pm enable --user \" + uid + \" $p >/dev/null 2>&1; done\");\n    }\n"),
    # 块4 登记表 .. pruneSpace
    ("    // ---------------- 分身空间登记表 ----------------",
     "        return still;\n    }\n"),
    # 块3 归属 .. listBatch
    ("    /** 空间归属标记：写在该空间自己的 settings 里（cubclone_owned=1），随空间一起删 */",
     "        return sb.toString() + SCAN;\n    }\n"),
    # 块2 dirty/metaDirty
    ("    /** 数据只在真正改过之后重读；平时进出页面直接用缓存，不再每次都起 shell */",
     "    static volatile boolean metaDirty = false;\n"),
    # 块1 THIRD
    ("    /** 三方包集合的副本：设置页要按它数「这个空间里有几个三方应用」（不额外起 shell） */",
     "    static volatile Set<String> THIRD = new HashSet<String>();\n"),
]

for start, end in blocks:
    i = text.find(start)
    if i < 0:
        print("!! 找不到 start:", start[:50])
        sys.exit(1)
    j = text.find(end, i)
    if j < 0:
        print("!! 找不到 end:", end[:50])
        sys.exit(1)
    j += len(end)
    text = text[:i] + text[j:]
    print("删除块 OK: %s ..." % start.strip()[:40])

# ---- 替换引用 ----
methods = ["markOurs", "unmarkOurs", "marked", "markedUids", "ensureMarks", "loadMarks",
           "isOurs", "cloneName", "spaceName", "nameOf", "spaceReady", "startSpace",
           "listSpaceUids", "listBatch", "keptPkgs", "markKept", "unmarkKept", "iconsInSpace",
           "shortcutPkgs", "sameNamePkgs", "resolverPkgs", "isEssentialPkg", "mustKeep",
           "pruneSpace", "restoreSpace", "pickReusableSpace", "reusableSpaces", "attach"]
fields = ["THIRD", "dirty", "metaDirty", "SPACE_NAMES", "SPACE_ORDER", "appCtx"]

stats = {}
for m in methods:
    pat = re.compile(r'(?<![A-Za-z0-9_.])' + m + r'\s*\(')
    text, n = pat.subn('CloneStore.' + m + '(', text)
    if n: stats[m] = n

for fld in fields:
    pat = re.compile(r'(?<![A-Za-z0-9_.])' + fld + r'(?![A-Za-z0-9_])')
    text, n = pat.subn('CloneStore.' + fld, text)
    if n: stats[fld] = n

print("---- 替换统计 ----")
for k, v in sorted(stats.items(), key=lambda x: -x[1]):
    print("  %s: %d" % (k, v))

with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(text)

print("完成：%d -> %d 字符" % (orig_len, len(text)))
