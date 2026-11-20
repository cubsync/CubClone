# Changelog

本文件格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。
版本规则见 [docs/VERSIONING.md](docs/VERSIONING.md)。

## [Unreleased]

### 变更
- 分身共享存储修复模块升到 2.1（`cubclone-sdcardfs-fix.zip`，id 与名称不变，刷入即覆盖旧版）：
  只做两件系统行为上的事 —— 让 vold 走纯 FUSE（分身卷才挂得上、相册与主空间同一份），
  以及放开一个 `fuse` 文件上的 ioctl（`0xf50c`）。后者是实测出来的：Android 16 的默认策略
  不允许普通应用对 FUSE 文件发这个 ioctl，会让把数据库放在 `/sdcard` 上的应用直接打不开库
  （表现为导出 0 字节、导入报错）。
- 模块不再改动任何文件的属主、不搬动也不删除主空间里的任何东西。上一版在
  `Android/media` 下做的属主迁移已移除：实测同一个库在 OP12（原生纯 FUSE、属主形态相同）
  完全正常，说明问题不在属主。

### 修复
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
