# 分身数据体积：口径、来源与成本

「批量删除分身」页每行显示的体积和「无数据」判定都按本文口径。改之前先读完。

## 口径

**用户数据 = 文件字节总和，不含缓存。**

范围：`/data/user/<U>/<包>`、`/data/user_de/<U>/<包>`、`/data/media/<U>/Android/{data,media,obb}/<包>`。

- 路径中出现 `cache` 或 `code_cache` 段的文件不计（系统把缓存单列，Settings 也分两行显示）。
- 其他文件全计，**包括 `files/profileInstalled` 这种 24 字节系统标记**——它在，说明应用在这个
  分身里启动过，不能算空壳。
- **目录 inode 不计**。f2fs 上每个目录 `st_size` 3452 字节，算进去会让刚克隆、一次没打开的应用
  凭空多出十几 KB，判定就废了。

判定「无数据」= 结果恰好 0。只有刚克隆、一次没启动的应用才是 0：系统只给它建了
`cache`、`code_cache` 两个空目录，没有别的条目。

实测（moto edge 20 pro，2026-11-22）：

| 空间 | 应用 | 用户数据 | 判定 |
|---|---|---|---|
| u10 | 芝麻粒 | 24 B（`files/profileInstalled`） | 有数据 |
| u10 | XRadiant | 9,067,297 B | 有数据 |
| u11 | 刚克隆的 XRadiant | 0（只有两个空缓存目录） | 无数据 |
| u11 | 微信 | 3.4 GB | 有数据 |

> 未闭环：同机 Settings「应用信息」给芝麻粒显示 4.10 KB，本口径算 24 B。
> 差异来源未确认（可能是 Settings 对目录块或 quota 的算法），不影响「有/无」判定。

## 为什么不直接调系统 API

系统的「应用信息 → 存储」走 `StorageStatsManager` → `IStorageStatsManager` →
`StorageStatsService` → `Installer.getAppSize` → `InstalldNativeService::getAppSize`。
后者按 quota 分段统计：`dataSize` = CE + DE，`cacheSize` = 两者下的 `cache`/`code_cache`。

在本机用 root 起 `app_process`，反射拿 `storagestats` binder 直接调
`queryStatsForUid` / `queryStatsForPackage`（`callingPackage` 为 `com.android.settings`，
volume 试过 `private`、`primary_physical`），结果是：

```
芝麻粒 u0  data=0 cache=0 app=17151488  (2ms)
芝麻粒 u10 data=0 cache=0 app=17151488  (1ms)
微信   u10 data=0 cache=0 app=890611712 (5ms)
```

`app` 有值、`data`/`cache` 恒 0，且耗时 0–5 ms——这是 quota 路径返回空的特征（真遍历
2 GB 的微信不可能 5 ms）。同一台机 `dumpsys diskstats` 的 `App Data Sizes` 有真实值，
但它是**按包名去重**的全系统聚合，分不出是哪个空间，用不了。

其他路也堵着：

- `cmd storagestats`：本机 `No shell command implementation.`
- 应用内直接调：查其他用户（u10/u11）需要 `INTERACT_ACROSS_USERS`（signature 级），
  普通应用拿不到，`pm grant` 也给不了。

结论：**这个 ROM 上该 API 拿不到按空间的数据体积**，所以用 root 读文件、按系统同一公式算。

## 没有 root 时

体积只能靠 root 读文件，没有 root 一律显示「—」，不做假数字；「仅无数据」按钮禁用并说明原因。
**列表和删除不受影响**：两者只用 shell 身份（`cmd user list`、`pm list packages --user`、
`pm uninstall --user`），Shizuku 就够。

实测（同机，uid 2000 = shell = Shizuku 身份）：

```
pm list packages -3 --user 11        → 正常列出
pm uninstall --user 11 <不存在包>     → Failure [not installed for 11]   ← 权限检查通过
ls /data/user/11/<包>                → Permission denied                 ← 这就是必须 root 的原因
```

## 成本

成本取决于**文件总数**，不取决于应用个数（APK 在 `/data/app` 共享，不扫）。

实测一个空间 17,824 个文件，`du` 遍历 **0.12 s**；`find -type f -printf` 加 awk 汇总同量级。
几百个分身的空间数量是几个到十几个，累加也在秒级。

即便如此也不每次打开都扫：

- 结果写 `files/size-cache.txt`（`<uid>|<包> <字节>`，另有 `#<uid> <时间戳>`）。
- 进页面先读缓存秒显；某空间的缓存超过 10 分钟、或该空间出现了缓存里没有的包
  （新克隆、恢复出来的），才重扫这个空间。
- 删除成功后立刻删掉对应缓存条目。
- 排序菜单里「重新统计体积」可手动强制重扫。
- 统计线程用 `THREAD_PRIORITY_BACKGROUND`。
