# Changelog

本文件格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。
版本规则见 [docs/VERSIONING.md](docs/VERSIONING.md)。

## [Unreleased]

### 变更
- 内核仍带 `sdcardfs` 的机型上，新建空间自动改用独立存储（普通用户类型）：应用照常安装、
  启动、卸载，相册和下载等文件与主空间各存一份。此前这类机型上新空间的外部存储完全挂不上。
- 设置页的存储提示改为只做说明，不再生成「关 sdcardfs」修复模块。

### 移除
- 「生成修复模块」入口。实测关掉 `sdcardfs` 会让主空间 `Android/media/<包>` 的跨包写入失效
  （支付宝芝麻粒保存不了配置、MT 管理器删不掉目录），代价不可接受。

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
