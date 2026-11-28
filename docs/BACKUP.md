# 备份与恢复

## 备份什么（可勾选，默认只开前三项）

| 项 | 位 | 路径 | 默认 |
|---|---|---|---|
| 应用数据 CE | 1 | `/data/user/<U>/<pkg>` | 开 |
| 设备数据 DE | 2 | `/data/user_de/<U>/<pkg>` | 开 |
| 权限 | 8 | 已授予的运行时权限 + appops（写成 `perm.txt`） | 开 |
| 安装包 | 4 | `pm path` 的 base + split；可选「按空间各存一份」 | 关 |
| 外部数据 | 16 | `/sdcard/Android/data/<pkg>` | 关 |
| OBB | 32 | `/sdcard/Android/obb/<pkg>` | 关 |
| 媒体 | 64 | `/sdcard/Android/media/<pkg>` | 关 |

`cache` / `code_cache` 一律排除。备份时先 `am force-stop` 目标应用，原数据不动。

## 包内布局

顶层就是应用包名，CE / DE / 外部三块各一层：

```
<包名>/user/…            应用数据
<包名>/user_de/…         设备数据
<包名>/apk/*.apk         安装包（按空间存时是 apk/<U>/*.apk）
<包名>/perm.txt          权限与 appops
<包名>/ext/…             Android/data
<包名>/obb/…             Android/obb
<包名>/media/…           Android/media
```

外部三块在设备上读自 `/data/media/<U>/Android/…`（root 视角，比 `/storage/emulated` 稳）。
恢复时按目标空间写回同一位置，但**写回方式按挂载方式分开**：

| 目录 | 挂载 | 写回方式 | 属主/组/权限 |
|---|---|---|---|
| `Android/data` | pass-through（f2fs）| 直接写底层 | 该包 uid : 1078，2770 / 660 |
| `Android/obb` | pass-through（f2fs）| 直接写底层 | 该包 uid : 1079，2770 / 660 |
| `Android/media` | FUSE（MediaProvider 代理）| 走 `/storage/emulated/<U>/…` 建文件 | 顶层目录 = 该包 uid : 1023；里面内容 = MediaProvider : 1023，2775 / 664 |

`Android/media` 两条硬规则，都是踩出来的：

1. **文件必须由 MediaProvider 建**（走 `/storage` 路径）。直接 `mv` 进底层，MediaProvider
   不认这些 inode，之后应用 `ftruncate` / 新建一律 `EPERM` / `ENOENT`。
2. **属主必须让 MediaProvider 改得动**。纯 FUSE 下读写全由它代理，它只是个进程：文件
   属主是 `media_rw`(1023) 且组不是它的时候，它只有 other 读权限，写就是 EPERM。现场
   表现是应用侧 `SQLITE_IOERR_SHMOPEN`、界面一直转圈。属主落在 MediaProvider
  自己的 uid、组 `media_rw`、664/2775 才是它能写、应用也能通过它写的形态。

属主/权限一律在**底层路径**上改（`/data/media/…`）：`/storage` 是 FUSE，root 在那边
`chown` 会静默失败（改完复读仍是原值）。在底层只动元数据、不动 inode，MediaProvider
不会因此「不认识」这些文件。落权后逐项 `stat` 校验顶层属主，不符就报「外部目录属主没落对」
而不是照常报成功。


## 压缩

默认 `zstd -3 -T0`（`-T0` = 全部核心），zstd 二进制随包提供（`assets/zstd-<abi>`，
运行期由 root 释放到 `/data/local/tmp/cubclone/zstd`——Android 10 起不允许应用在自己的数据目录里执行文件）。
不可用时按 `gzip → bzip2 → 纯打包` 降级。

级别对比（100 MB 应用数据：DB + XML + JSON，全核心）：

| 级别 | 体积 | 耗时 | 相对默认 3 |
|---|---|---|---|
| 1 | 18.43 MB | 0.2 s | 更大、更快 |
| **3（默认）** | **16.16 MB** | **0.36 s** | — |
| 6 | 15.14 MB | 0.52 s | −6% |
| 9 | 14.29 MB | 0.78 s | −12%，慢 2 倍 |
| 12 | 14.15 MB | 1.62 s | −12.5%，慢 4.5 倍 |
| 15 | 14.04 MB | — | −13% |
| 19 | 12.79 MB | 19.4 s | −21%，慢 50 倍 |

对照：同语料 `gzip -6` 为 19.56 MB / 1.5 s，`gzip -9` 19.35 MB，`bzip2 -9` 16.39 MB。
即 zstd 默认档已比 gzip -6 小 17%，`-9` 小 27% 且更快——所以界面建议「想更小就调到 9」。

## 过程页与通知

批量与单条备份/恢复都走同一个过程页：逐项状态 + 进度条 + 实时日志与速度，
可返回后台继续跑，回来后「数据备份 → 日志」还能看这次（或是上一次落盘的）日志，
跑完可按 全部 / 成功 / 失败 筛选、复制日志。

任务同时挂在一条通知上：进度 → 总结，同一条通知原地更新，默认不响不震
（`IMPORTANCE_LOW` + `setSound(null)` + 关振动），点开回到过程页。
