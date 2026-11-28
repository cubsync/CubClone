# 分身数据体积：来源、口径与成本

「批量删除分身」页每行显示的体积和「无数据」判定都按本文口径。改之前先读完。

## 数据来源

```
cmd package get-package-storage-stats --user <U> <包>
```

这就是系统「应用信息 → 存储」用的那条路（`IPackageManager.getPackageStorageStats`
→ `StorageStatsService` → installd），输出：

```
code: 17151488 bytes (16.36 Mb)
data: 143360 bytes (140.00 Kb)
cache: 139264 bytes (136.00 Kb)
apk: 3051355 bytes (2.91 Mb)
```

**只需要 shell 身份**：root 或 Shizuku 都行，不需要 root。一次调用约 40 ms，
按空间攒成一条 shell 循环批量跑（40 个一批，分块防止命令行过长）。

## 口径

显示与判定都用 **`data - cache`**，即「用户数据」。

系统的 `dataBytes` 含缓存，`cacheBytes` 是其中缓存部分，两者相减才是 Settings 里
「用户数据」那一行。例如 `data=143360`、`cache=139264` → **4096 B（4 KB）**，
和「应用信息」页面上的数字对得上。

判定「无数据」= 结果恰好 0。刚克隆、一次没启动的应用就是这个值（系统给它建的
`cache`、`code_cache` 都是 0 字节）。

示例：

| 空间 | 应用 | data | cache | 判定 |
|---|---|---|---|---|
| 空间 A | 应用 A | 143,360 | 139,264 | 4 KB（有数据） |
| 空间 A | 应用 B | 10,219,520 | 557,056 | 9.2 MB（有数据） |
| 空间 B | 刚克隆的应用 | 0 | 0 | 无数据 |
| 空间 B | 聊天类应用 | 3,538,157,568 | 33,817,600 | 3.3 GB（有数据） |

## 为什么不直接调 `StorageStatsManager`

直接调 `storagestats` 服务在本 ROM 上拿不到值。用 root 起 `app_process` 反射拿 binder，
`queryStatsForUid` / `queryStatsForPackage`（`callingPackage` 试过 `com.android.settings` 与 `null`，
volume 试过 `private`、`primary_physical`、真实卷 UUID）结果都一样：

```
应用 A u0 data=0 cache=0 app=17151488  (2ms)
应用 B  space=A  data=0 cache=0 app=890611712 (5ms)
```

`app` 有值、`data`/`cache` 恒 0、耗时 0–5 ms —— quota 路径返回空。换 shell 身份调用同样是 0，
所以不是权限问题，是这条服务在本 ROM 上的实现。

`cmd package get-package-storage-stats` 走的是 `PackageManager` 侧的封装，取到的才是真值。
（`cmd storagestats` 在本机没有 shell 实现；`dumpsys diskstats` 的 `App Data Sizes` 有真值，
但按包名去重后分不出空间，用不了。）

## 成本

一个包一次调用约 40 ms，成本与**应用个数**成正比（这点和读文件不同）：

| 场景 | 耗时 |
|---|---|
| 单个包 | 40 ms |
| 6 个包（一个空间全部） | 0.30 s |
| 400 个包（按 40 一批） | 约 16 s，后台 |

所以加了缓存，不每次打开都扫：

- 结果写 `files/size-cache.txt`（`<uid>|<包> <字节>`，另有 `#<uid> <时间戳>`）。
- 进页面先读缓存秒显；某空间缓存超过 10 分钟、或该空间出现了缓存里没有的包
  （新克隆、恢复出来的）才重扫这个空间。
- 删除成功后立刻删掉对应缓存条目。
- 排序菜单里「重新统计体积」可手动强制重扫。
- 统计线程用 `THREAD_PRIORITY_BACKGROUND`。

首次 247 ms 出界面（统计在后台继续），二次 228 ms 且缓存文件未被改写。

## 与 root 的关系

这一页**不再需要 root**：

- 列表：`cmd user list --all -v`、`pm list packages --user <U>`
- 删除：`pm uninstall --user <U> <包>`
- 体积：`cmd package get-package-storage-stats --user <U> <包>`

三者都是 shell 能力，Shizuku 就够。以 shell 身份（Shizuku 同身份）执行：

```
pm list packages -3 --user <U>        → 正常列出
pm uninstall --user <U> <不存在包>     → Failure [not installed for <U>]  ← 权限检查通过
cmd package get-package-storage-stats --user 10 <包> → data/cache 有值
```

自测开关：`am start ... --ez noRoot true` 会在有 root 的机器上强制走 Shizuku 分支。
