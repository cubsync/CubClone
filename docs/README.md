# CubClone docs — 参考资料索引

迁移自旧工作区 `moto-port/` 的调研与取证资料，按 CubDesk/docs 的组织方式归类。

## screenshots/

各版本 UI 实拍与验证截图（moto 原生「应用分身」、红米/OP12 装机效果、网格/设置/主题等）。
文件名保留取证时的原名，按前缀识别来源：

| 前缀 | 来源 |
|---|---|
| `moto-*` / `mt-*` / `esp-*` / `desk-*` | moto 设备（XT2153）取证 |
| `redmi-*` | 红米 8A 实测 |
| `settings-*` / `theme-*` / `ui-*` / `new-ui*` / `v14-*` | 版本迭代界面 |
| `open-icon*` / `shot-*` / `reuse*` / `order-check` | 功能点验证 |

## 同类项目调研.md

开源/闭源「应用分身」实现路线调研：work profile（Island/Shelter/Insular）、
multi-user（本项目的路线）、虚拟化引擎（VirtualApp/BlackBox）三类方案对比。

## reference/ui-dumps/

uiautomator 界面取证 dump（moto 应用分身各界面控件树），供交互复刻参考。

## reference/apk/、reference/decoded/（⛔不进仓库）

moto「应用分身」（PrcSecurityCenter）与一加 OplusMultiApp 的原版 APK、
framework.jar、反编译产物与 smali——**属摩托罗拉/一加版权物，仅供本地复刻参考，
已写入 .gitignore，绝不提交；仓库公开前请再次确认此目录保持排除状态**。

自写文档（可公开）：
- `reference/apk/oplus/一加应用分身-评估.md` —— 一加 MultiApp 行为评估
