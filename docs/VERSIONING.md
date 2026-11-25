# 版本与发布规则

## 版本号

Semantic Versioning，`MAJOR.MINOR.PATCH`，当前处于 `0.x`：

| 变更 | 进位 | 例 |
|---|---|---|
| 新功能、行为调整 | MINOR | 0.6.52 → 0.7.0 |
| 只修 bug | PATCH | 0.6.52 → 0.6.53 |
| 不兼容的接口/数据变更 | MAJOR（0.x 阶段允许 Breaking 走 MINOR） | 1.0.0 |

**开发期不动版本号**：`versionName` 保持上一个已发布版本，改代码、构建、装真机都沿用；
收版时才写新号。未验证的改动不占版本号。

## 测试版

内测只用预发布后缀，不消耗正式版本号：

```
0.7.0-alpha.1 → 0.7.0-beta.1 → 0.7.0-beta.2 → 0.7.0-rc.1 → 0.7.0
```

预发布一律发 GitHub `--prerelease`；转正式时用新的 tag（`v0.7.0`），不是把 beta 的
release 点成正式。

## versionCode

由版本号自动推导，不手写：

```
正式版   versionCode = major * 1000000 + minor * 10000 + patch * 100
预发布   versionCode = 正式版 code - 槽偏移 + 序号N
```

| 后缀 | 槽偏移 | 序号范围 | 0.7.0 下的 code |
|---|---|---|---|
| `-dev.N` | 100 | 1–9 | 69901–69909 |
| `-alpha.N` | 90 | 1–19 | 69911–69929 |
| `-beta.N` | 70 | 1–29 | 69931–69959 |
| `-rc.N` | 40 | 1–29 | 69961–69989 |
| 无 | — | — | 70000 |

**预发布必须小于同号正式版**，否则正式版发不出去（versionCode 只能升不能降）。
只认 `dev` / `alpha` / `beta` / `rc` 四种后缀，其它后缀构建直接报错。

`app/build.sh` 构建时从 `AndroidManifest.xml` 的 `versionName` 算出并传给 aapt2。

> 坑：manifest 里**不能**留 `android:versionCode`。留了 aapt2 会忽略 `--version-code`
> 参数，产物 versionCode 变空。

## 分支

| 分支 | 用途 | 生命周期 |
|---|---|---|
| `main` | 永远可安装、可运行，日常小改直接提交 | 常驻 |
| `feat/<短名>`、`fix/<短名>` | 单次改动 | 合进 main 后删 |
| `release/<版本号>` | 收版：从 main 切出 → 验证 → 打 tag → 合回 main | **发完即删**，需要时再建 |

- 收版分支命名 `release/0.6.52`，不用裸版本号，和 `feat/`、`fix/` 一眼区分。
- 发完版就把 release 分支删掉：tag `v0.6.52` 已经固定了那一刻的代码，分支留着只是噪音。
- 只有需要给**已发布的旧版本**单独回补补丁时，才把 `release/<旧版本号>` 重新建出来（从对应 tag 切）。
- 任何时刻远端应该只有 `main`（加正在进行的 `feat/`、`fix/`）。
- 单人开发的例外：改动很小且当场验证通过，允许直接在 `main` 提交（本文档与 CHANGELOG 的维护就是这样）。

## 回滚

**不需要保留版本分支**，tag 就是那个版本的永久记录。三种用法：

| 场景 | 做法 |
|---|---|
| 装回旧版（最常用） | 从 GitHub Release 下对应版本的 APK 覆盖装；`versionCode` 只增不减，降级要 `adb install -r -d` 或先卸载 |
| 看那版代码 | `git switch --detach v0.7.0`、`git diff v0.6.52 v0.7.0` |
| 给旧版打补丁 | `git switch -c fix/<问题> v0.7.0` → 改 → 合回 main → 打 `v0.7.1` |

## 为什么不保留每个版本的分支

业界做法一致：release 分支合并后删除，**tag 才是版本的永久锚点**。

- GitFlow 原作者的操作流程里，`release finish` 的最后一步就是删分支；
  各路实践指南的回答同样直接——「Should release branches be deleted after merging? Yes.」
- 长期留下的唯一真实收益是「方便切回去看」，而这用 `git switch v0.7.0` 一样做到。
  代价却是实打实的：没人合并、没人验证的分支会越漂越远，真要回补时还得从 tag 重建，
  而且它和 tag 摆在一起，看的人会以为那里面是「最新代码」。
- 只有**同时维护多个已发布版本、需要长期回补**的产品（企业软件 LTS、对外 SDK）才保留
  per-version 分支。本项目不是这种形态。

结论：远端常态只有 `main`；`feat/`、`fix/` 合完即删；收版用 `release/<版本号>`，发完即删。

## 发布

1. 收版时才改 `AndroidManifest.xml` 的 `versionName`，构建一次确认 `versionCode` 推导正确。
2. 装真机，等验证通过。
3. 验证通过后：`CHANGELOG.md` 补条目 → 提交 → `git checkout -b release/<版本号>` → 打 tag
   `v<版本号>` → `gh release create v<版本号> --prerelease --notes-file <说明>`。
4. 真机确认没问题后，把 pre-release 转正式：
   `gh release edit v<版本号> --prerelease=false --latest`。
5. `release/<版本号>` 合回 `main`（快进即可），然后**删掉这条分支**，远端只剩 `main`。

## 发布说明

- 面向用户：只写**下载什么、能做什么、修了什么、要注意什么**，不写实现细节。
- 中英双语：中文在前、`---` 分隔、英文在后。
- 来源是 `CHANGELOG.md` 对应版本条目，不要另起一份手写内容。

## 产物

| 平台 | 产物 |
|---|---|
| Android（收版） | `dist/CubClone-<版本号>-arm64-v8a.apk` + `dist/CubClone-<版本号>-universal.apk`（预发布带后缀，如 `CubClone-0.7.0-beta.1-arm64-v8a.apk`），签名用仓库外的密钥库（见 build.sh 的 `$CUBCLONE_SIGNING`） |
| Android（测试包） | `dist/CubClone-<版本号>+b<构建号>.<短提交号>-<abi|universal>.apk` —— 版本号部分与应用内「关于」显示的 `versionName` 完全一致 |
| 桌面 | 暂无 |

产物名带版本号，出到仓库根的 `dist/`；`build/` 放中间件。两者都在 `.gitignore` 里，不入库。
每出一个测试包，同版本的旧 `+*.apk` 当场删掉；手机 `Download` 里也只留最新那一个。

预发布与开发期构建在 `versionName` 末尾追加 `+b<构建号>.<短提交号>`（SemVer §5 的构建元数据，如
`0.8.0-dev.1+b167.7569020`，有未提交改动时末尾带 `-dirty`）。构建号取 `git rev-list --count HEAD`，
单调递增——哈希看不出先后，`b167` 一定比 `b166` 新。设置页「关于」直接读 `versionName`，
所以设备上一眼能看出装的是第几版、哪次提交。
它**不占**版本号、不影响 `versionCode`、不参与版本优先级；`-dev.N` 的 N 仍然只在验证通过后才递增。

**正式版不带构建标记**：发给用户的包 `versionName` 必须干净（`0.7.0`），「关于」里不许出现 `+6b0d837` 这类编号。
要确认线上包对应哪次提交，看 tag 与 Release 页的提交记录，不靠应用内版本号。
