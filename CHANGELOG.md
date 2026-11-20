# Changelog

本文件格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。
版本规则见 [docs/VERSIONING.md](docs/VERSIONING.md)。

## [Unreleased]

### 变更
- 主界面长按主空间应用改为弹菜单（与分身行长按一致），第一项是「批量创建分身」进多选；
  菜单里还能选目标空间创建分身、看应用信息、一次移除该应用的全部分身。
  移除只动分身，绝不碰主空间的应用和数据。
- 修复模块的说明文字与实际行为对齐（原来还写着放行 fuse ioctl，那是 2.1 的做法、已移除）。
- 分身共享存储修复模块升到 2.3（`cubclone-sdcardfs-fix.zip`，id 与名称不变，刷入即覆盖旧版）：
  ① 让 vold 走纯 FUSE（分身卷才挂得上、相册与主空间同一份）；
  ② 把 sdcardfs 时代留下的文件属主一次性迁成 FUSE 形态。
  等解锁只能做低频检查，不能靠 inotify：解锁改的是挂载与权限，`Android/media` 这个目录项
  早就存在（实测 mtime 没变过），inotify 收不到 create；模块也无法注入 init 的
  `on property:sys.user.0.ce_available` 触发器。改为前 60 秒每 2 秒、之后每 15 秒检查一次，
  每次只是一次 stat（命中缓存、不落盘），24 小时不解锁则放弃本次、下次开机再来。
- 根因（设备实测）：纯 FUSE 下所有文件读写都由 MediaProvider 进程代理，它操作底层文件时
  受 POSIX 约束。sdcardfs 时代建的文件属主是 `media_rw(1023)`，MediaProvider 只是 other，
  `ftruncate` 直接 EPERM，SQLite 报 `4618 SQLITE_IOERR_SHMOPEN`，应用打不开自己的库
  （导出 0 字节、导入报错、界面一直加载中）。原生 FUSE 机型上这些文件属主本来就是
  MediaProvider 自己，所以没有这个问题。
- 迁移规则与原生 FUSE 机型一致，不按应用猜归属：`Android/media/<包名>` 顶层目录归该包 uid，
  里面的内容与共享存储其它位置归本机 MediaProvider 的 uid；`Android/data`、`Android/obb`
  完全不动。只改属主，不动内容、不搬动、不删除；只跑一次，成功留标记。
- 移除 2.1 的 `fuse` ioctl 放行：8A 上同样有这条 avc 却功能正常，说明它不是原因。

- 新增应用数据克隆，入口只有一个「克隆数据到…」（主应用行和分身行长按菜单里都有）：
  源就是长按的那一行，目标列出其余装了该应用的空间，方向不设限 ——
  主空间→分身、分身→主空间、分身之间互拷都能用。
  · 只复制应用私有数据（`/data/user/<用户>/<包>`），不含相册、下载等共享文件，也不复制 
    cache/code_cache；
  · 复制前停止两端应用，目标原有数据整体改名成 `.cubclone-bak-<时间戳>` 留作备份；
  · 复制后按目标用户 uid 重设属主，并把目标原有的 SELinux 上下文（含本应用的 MLS 类别）
    递归套回去 —— 用 `restorecon` 只会得到不带类别的 `system_data_file`，应用会起不来；
  · 复制完成后把源应用**已授予的运行时权限**同步到目标空间（`pm grant`），
    另加两个用户可感知的特殊授权：悬浮窗、所有文件访问；目标里打开应用不用再点一遍；
  · 需要 root：`/data/user` 下的数据 Shizuku 的 shell 身份读写不了。
- 设置页新增「数据备份」：列出克隆时留下的 `.cubclone-bak-<时间戳>` 备份，
  可恢复（当前数据先另存为新备份，两版本互换，不会丢）、导出到 `Download/CubClone-backup`、
  删除（二次确认）。这些备份在 `/data/user/<空间>/` 下、权限 700，普通文件管理器读不到。
- 设置页「数据备份」：备份可以压缩归档了。算法可选 zstd / gzip / bzip2 / 不压缩（纯 tar），
  每种算法可单独选级别并在列表里标出默认档（zstd 3、gzip 6、bzip2 9）。
  实测本机 `/system_ext/bin/zstd` 损坏（连 `echo hi | zstd -c` 都报 `Unsupported parameter`），
  所以压缩失败会自动降级到 gzip 并提示实际用的算法；打包完成并校验通过后才删原目录。
  恢复和导出都能识别 `.tar.zst` / `.tar.gz` / `.tar.bz2` / `.tar`。
- 「数据备份」改成独立页面：列表每项显示应用、空间、时间、大小、是否已压缩，点一行或右侧 ⋮ 出
  恢复 / 导出 / 压缩 / 删除；顶部一行「克隆时的备份压缩」选算法与级别，空列表有说明。
  压缩设置现在对后续克隆生效：复制完成后自动把那份备份压成包。
- 备份页补上多选（长按进入，支持全选、批量删除、批量压缩）、按应用名/包名搜索、按空间筛选；
  没有 root 时页面顶部直接说明原因，而不是让列表空着。
- 「数据备份」改名为「克隆前数据备份」（英文 Pre-clone backups），名字与它的实际来源一致。
### 修复
- 级别选不到：`setMessage()` 与 `setItems()` 在 AlertDialog 里共用同一块内容区，
  后设置的会顶掉先设置的，级别列表因此没显示出来。已去掉 message。
- 「数据备份」四个字在深色主题下发灰：它没有 id、不在 `applyChrome()` 的配色名单里，
  只能吃布局默认色。已加 id 并纳入名单。
- 新建空间时如果 clone 共享存储挂不上、又没刷修复模块，自动降级为独立存储（普通用户），
  不再留下一个没有外部存储的空间。

## [0.6.52] - 2026-11-18

### 新增
- 分身存储自检与修复：部分 ROM 内核仍编着 `sdcardfs`，会让分身外部存储挂不上（相册空白、
  依赖外部存储的输入法闪退）。设置页检测到显示红字，点一下在 Download 生成修复模块，
  用 APatch / KernelSU / Magisk 刷入重启即可；修好后转绿字，可随时重新生成。
  本机不需要的机型不显示提示，模块刷入时也会拒绝安装。
- 新空间输入法清单：按系统实际安装的输入法列出，默认全选，可逐项取消。
- 打开分身时的提示带空间序号与名称。

### 变更
- 版本号改为 `0.6.52`（原 `6.52`），与开源项目 Semantic Versioning 对齐；
  versionCode 改为由版本号自动推导。

### 修复
- 新装应用后前台列表不刷新。
- 新建空间后输入法设置不完整（默认输入法、已启用列表、子类型未同步）。
- 生成修复模块会留下 `(1)` 副本，改为同名直接覆盖。

## [0.6.51] - 2026-11-06

首个对外版本：多空间管理、批量分身、网格/列表视图、主题与中英界面、
Shizuku / root 双模式、单分身改名 / 启用 / 禁用 / 隐藏 / 移除。

[Unreleased]: https://github.com/cubsync/CubClone/compare/v0.6.52...HEAD
[0.6.52]: https://github.com/cubsync/CubClone/releases/tag/v0.6.52
