# 版本与发布规则

## 版本号

Semantic Versioning，`MAJOR.MINOR.PATCH`，当前处于 `0.x`：

| 变更 | 进位 | 例 |
|---|---|---|
| 新功能、行为调整 | MINOR | 0.6.52 → 0.7.0 |
| 只修 bug | PATCH | 0.6.52 → 0.6.53 |
| 不兼容的接口/数据变更 | MAJOR（0.x 阶段允许 Breaking 走 MINOR） | 1.0.0 |

**开发期不动版本号**：`versionName` 保持上一个已发布版本，改代码、构建、装真机都沿用；
收版时才写新号。未验证的改动不占版本号，汇报只给 git 提交号。

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
| Android | `CubClone-<版本号>.apk`（预发布带后缀，如 `CubClone-0.7.0-beta.1.apk`），签名用仓库外的密钥库（见 build.sh 的 `$CUBCLONE_SIGNING`） |
| 桌面 | 暂无 |

产物名带版本号；`app/分身空间.apk` 是构建中间产物，不入库。
