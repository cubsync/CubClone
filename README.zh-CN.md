<p align="center"><img src="assets/logo.png" width="132" alt="CubClone"></p>

<h1 align="center">CubClone 分身空间</h1>

<p align="center"><a href="README.md">English</a> · <b>简体中文</b></p>

CubClone（分身空间）是一个 Android 应用多开工具。它不虚拟化应用，而是直接使用系统的
多用户能力创建真实的 clone profile，在里面跑独立的一份应用副本 —— 也就是各家 ROM 内置的
「应用分身」，这里做成了独立开源应用。

一个空间一个沙箱：每个分身有自己的应用数据、登录态和通知，同一款应用能开成几个独立账号，
只取决于系统允许建多少个空间。

## 原理

1. `pm create-user` 建一个 `profile.CLONE` 用户（设备侧助手通过 `app_process` 执行）。
2. `pm install-existing --user <uid>` 把应用装进这个空间。
3. 跨用户 intent 启动，并可钉一个桌面快捷方式一键进入。
4. 每个空间维护一份包登记表，系统自动灌进去的预装应用可以按表清理掉。

提权走 **Shizuku** 或 **root**，有哪个用哪个。

- 自己建的空间在创建时就打了标记，即使 uid 被系统回收复用也能认出归属。
- 别人建的空间（例如 ROM 自带的应用分身）**只读显示**：能看、能打开，不做任何修改。

## 功能

- 网格 / 列表两种视图可切换，行列数可调
- 两种视图顶部都有「最近打开」
- 多选批量分身：N 个应用 × M 个空间
- 单个分身可改名、启用 / 禁用、隐藏、钉快捷方式，空间可单独清理
- 新建空间时可挑输入法，并自动修复空间里的输入法设置
- 处理内核 `sdcardfs` 冲突：部分 ROM 上分身共享存储挂不上，可一键生成模块
  （APatch / KernelSU / Magisk）刷入，恢复后分身与主空间共用同一份相册和文件；
  没刷模块时，新建空间会降级为独立存储，至少不会有空间却完全没有外部存储
- 主题：跟随系统 / 浅色 / 深色 / 纯黑（AMOLED），中英双语界面

## 构建

不用 Gradle，直接调 Android 构建工具（aapt2 / javac / d8 / apksigner）。

```bash
# 1) 设备侧助手 jar（作为 asset 打进 APK）
(cd proto && ./build.sh)

# 2) 应用本体；签名凭据从 $CUBCLONE_SIGNING 读
#    （默认 ./signing.properties，该文件已在 .gitignore 里）
(cd app && ./build.sh)
```

环境：Android SDK build-tools 35.0.0 + platform android-36、JDK 21。

产物：`dist/CubClone-<版本号>.apk`，中间件在 `build/`；两者都不入库。

Shizuku 的 API class 内置在 `app/libs/`（取自官方发行版），对应的 AIDL 源在 `app/libs/aidl`。


## 状态

持续开发中，作者日常在用。已在 Android 13–16 的接近原生系统与厂商 ROM 上验证。

## 许可

Apache-2.0，见 [LICENSE](LICENSE)。品牌资源（logo）不在该许可范围内。
