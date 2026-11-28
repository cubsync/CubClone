package com.cubsync.cubclone;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分身/空间领域逻辑的唯一来源。
 *
 * 把「空间列表、归属判定、名称、精简、扫描」这些纯业务从 Activity 里抽出来集中在这里，
 * UI（MainActivity/SettingsActivity）只读这里、只调这里，不各自维护缓存 ——
 * 避免「改一个功能导致别处状态不同步」的 bug。
 */
public final class CloneStore {

    private CloneStore() {}

    static final String OWNED = "cubclone_owned";
    /** 旧版归属标记 key（wb_clone），loadMarks 兜底读它做迁移，不再新写 */
    private static final String OWNED_LEGACY = "wb_clone";

    /** 归属判据要用 Context 读本地登记；两个入口 Activity 的 onCreate 都会调 attach() */
    static Context appCtx;
    /**
     * 隐藏分身的集合缓存。以前每次 isHidden 都 getStringSet + new HashSet 拷贝，
     * 而 bindCard 对每一行、每个分身各调一次 —— 245 个系统应用滚一屏就是几百次
     * 加锁读取 + 集合分配。改内存快照后只在 hide/unhide 时换引用。
     */
    private static volatile Set<String> HIDDEN;
    /** 隐藏集合的版本号：列表复用要靠它判断「隐藏状态变没变」 */
    private static volatile int HIDDEN_VER;

    static int hiddenVersion() {
        return HIDDEN_VER;
    }

    static void attach(Context c) {
        if (appCtx == null && c != null) {
            // 走 Locales.wrap：默认空间名（「空间 N」/「Clone N」）要按应用内语言生成，
            // getApplicationContext() 拿的是系统语言，英文模式会落成中文名。
            appCtx = Locales.wrap(c);
            mirrorPrefs();
        }
    }

    /**
     * 设置镜像：把 shared_prefs/clone.xml 定期复制一份到 files/clone.xml.bak。
     * 有过教训——外部 sed 把 XML 写坏，系统直接按默认值重建，隐藏清单/备份目录/分身登记
     * 一次全丢。有镜像至少能捞回来。半天轮换一次，旧的那份留作 .bak2。
     */
    private static void mirrorPrefs() {
        try {
            java.io.File src = new java.io.File(
                    appCtx.getFilesDir().getParentFile(), "shared_prefs/clone.xml");
            java.io.File bak = new java.io.File(appCtx.getFilesDir(), "clone.xml.bak");
            if (!src.exists()) return;
            if (bak.exists() && src.lastModified() - bak.lastModified() < 6 * 3600 * 1000L) return;
            if (bak.exists()) {
                java.io.File old = new java.io.File(appCtx.getFilesDir(), "clone.xml.bak2");
                old.delete();
                bak.renameTo(old);
            }
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(bak);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 归属判据只认「跟着空间走」的标记，不认 uid。
     * ⚠uid 会被系统复用：删掉 15 号空间后，系统的分身或别的空间可能又拿到 15 ⇒
     * 以前按 uid 登记就会把别人的空间当成自己建的（ESP 上就这么误判过）。
     * 判据：建完往该空间自己的 settings 里写 cubclone_owned=1（存这个用户的库，随空间一起删）。
     */
    private static final Set<Integer> MARKED = new HashSet<Integer>();
    private static boolean marksLoaded;

    /** 三方包集合的副本：设置页要按它数「这个空间里有几个三方应用」（不额外起 shell） */
    static volatile Set<String> THIRD = new HashSet<String>();

    /** 主空间系统包集合缓存（建空间算「不装集合」用；`pm list packages -s` 一次 86ms） */
    private static volatile Set<String> SYS_PKGS;

    /** 主界面数据变更标志：建/删/装/精简空间后置 true，onResume 重拉 */
    static volatile boolean dirty = true;
    /** 应用元信息（图标/名字）变更标志 */
    static volatile boolean metaDirty = false;

    /** 分身空间名字缓存：uid → 系统里的名字（`cmd user list --all -v` 的 name= 字段） */
    static volatile Map<Integer, String> SPACE_NAMES = new HashMap<Integer, String>();
    /**
     * 空间类型：true＝{@code profile.CLONE}（分身 profile，和主用户同组，天然同屏可见），
     * false＝{@code full.SECONDARY}（独立用户：Flyme 不支持 clone profile 时的降级结果，
     * 窗口可见性要插件补，见 WmVisibleHook）。
     */
    static volatile Map<Integer, Boolean> SPACE_TYPES = new HashMap<Integer, Boolean>();

    /** 这个空间是不是分身 profile（false＝完整独立用户）；未知按 false */
    static boolean isCloneSpace(int uid) {
        Boolean b = SPACE_TYPES.get(Integer.valueOf(uid));
        return b != null && b.booleanValue();
    }

    static boolean marked(int uid) {
        synchronized (MARKED) {
            return MARKED.contains(Integer.valueOf(uid));
        }
    }

    static Set<Integer> markedUids() {
        synchronized (MARKED) {
            return new HashSet<Integer>(MARKED);
        }
    }

    /**
     * 每次刷新都从设备重读一遍归属标记。
     *
     * <p>只读一次会漏：进程活着的时候别处建了空间（命令行、或另一个入口），
     * 标记在设备上、内存里没有 ⇒ 这个空间被当成「别人的空间」，它的**全部**包
     * 会被整片列出来——包括没有桌面图标的系统组件，界面上就是一行行空图标 + 包名。
     * 一次 shell 循环读回来约 100–200ms，换来列表永远和设备的真实状态一致。
     */
    static void ensureMarks() {
        marksLoaded = true;
        loadMarks();
    }

    /** 从设备上重新读一遍标记；顺带把「我们装过分身、但还没标记」的老空间补上标记 */
    static void loadMarks() {
        final String out = Shell.exec(
                "for u in $(cmd user list --all -v 2>/dev/null"
                + " | grep -E 'type=(profile[.]CLONE|full[.]SECONDARY)'"
                + " | sed -n 's/^[0-9]*: id=\\([0-9]*\\),.*/\\1/p'); do "
                + "echo \"CSPACE $u\"; "
                + "m=$(settings get --user $u secure " + OWNED + " 2>/dev/null); "
                + "[ -z \"$m\" ] && m=$(settings get --user $u secure " + OWNED_LEGACY + " 2>/dev/null); "
                + "case \"$m\" in 1*) echo \"MARK $u\";; esac; done; ");
        Set<Integer> now = new HashSet<Integer>();
        List<Integer> all = new ArrayList<Integer>();
        for (String line : out.split("\n")) {
            String t = line.trim();
            try {
                if (t.startsWith("MARK ")) now.add(Integer.valueOf(t.substring(5).trim()));
                else if (t.startsWith("CSPACE ")) all.add(Integer.valueOf(t.substring(7).trim()));
            } catch (Throwable ignored) {
            }
        }
        synchronized (MARKED) {
            MARKED.clear();
            MARKED.addAll(now);
        }
        // 老空间（本版本之前建的，没写 settings 标记）：只有我们确实往里装过分身才认，
        // 凭 flags 猜会把 ROM 自带的分身认成我们的。
        for (Integer u : all) {
            if (marked(u.intValue())) continue;
            Set<String> kept = keptPkgs(appCtx, u.intValue());
            if (kept != null && !kept.isEmpty()) markOurs(u.intValue());
        }
    }

    static void markOurs(int uid) {
        synchronized (MARKED) {
            MARKED.add(Integer.valueOf(uid));
        }
        Shell.exec("settings put --user " + uid + " secure " + OWNED + " 1");
        Shell.exec("settings delete --user " + uid + " secure " + OWNED_LEGACY);
    }

    static void unmarkOurs(int uid) {
        synchronized (MARKED) {
            MARKED.remove(Integer.valueOf(uid));
        }
        Shell.exec("settings delete --user " + uid + " secure " + OWNED);
        Shell.exec("settings delete --user " + uid + " secure " + OWNED_LEGACY);
    }

    /** 建空间用的系统用户名：直接写用户起的名字；没起名则按界面语言默认「空间 N / Clone N」 */
    static String cloneName(String base) {
        String b = base == null ? "" : base.replace("'", "").trim();
        if (b.length() > 20) b = b.substring(0, 20);
        if (b.isEmpty()) {
            int n = listSpaceUids().size() + 1;
            b = appCtx != null
                    ? appCtx.getString(R.string.space_name, String.valueOf(n))
                    : "Space " + n;
        }
        return b;
    }

    /** 这个 CLONE 空间是不是我们建的：只认空间里写的归属标记，不再靠名字前缀猜 */
    static boolean isOurs(String sysName, int uid) {
        return marked(uid);
    }

    /**
     * 一次 shell 把所有 profile.CLONE 空间的数据端上来：「SPACE / FSPACE uid」+ 包 + 图标。
     * 归属由 Java 侧按 $OURS 分类，shell 不看 flags。
     * ⚠别用 awk：Shizuku 起的 sh 里没有 awk，整条命令会静默失败、列表全空。
     */
    /**
     * 刚建好、还没跑完 prune 的空间。扫描时只报空间本身、不拉应用列表：
     * 新空间的系统应用此时还没 disable，拉出来会先铺一屏再消失（G7 实测）。
     * 只在内存里，App 重启就没了 —— 那时 prune 早跑完，不需要这个标记。
     */
    static final Set<Integer> PENDING = java.util.Collections.synchronizedSet(new HashSet<Integer>());

    static void markPending(int uid) {
        PENDING.add(Integer.valueOf(uid));
    }

    static void clearPending(int uid) {
        PENDING.remove(Integer.valueOf(uid));
    }

    static boolean isPending(int uid) {
        return PENDING.contains(Integer.valueOf(uid));
    }

    private static final String SCAN =
            "RM=\" $(dumpsys user 2>/dev/null"
            + " | sed -n 's/.*UserInfo{\\([0-9]*\\):.*<removing>.*/\\1/p' | tr '\\n' ' ') \"; "
            // 每个空间的三条 pm 查询并行跑（各写各的临时文件，最后 cat）。
            // 以前串行：六个空间 = 18 次跨进程查询首尾相接，实测 1.6s；并起来只花最慢那个的时间。
            + "SD=/data/local/tmp/cubclone-scan.$$; rm -rf $SD; mkdir -p $SD; "
            + "cmd user list --all -v 2>/dev/null"
            + " | grep -E 'type=(profile[.]CLONE|full[.]SECONDARY)' | { while read -r ln; do "
            + "u=$(echo \"$ln\" | sed -n 's/^[0-9]*: id=\\([0-9]*\\),.*/\\1/p'); "
            + "case \"$RM\" in *\" $u \"*) continue;; esac; "
            // 普通用户（full.SECONDARY）只有带我们归属标记的才算空间：
            // 系统里自己建的「访客/第二用户」不能被当成分身显示
            + "case \"$ln\" in *type=profile[.]CLONE*) ;; "
            + "*) case \" $OURS \" in *\" $u \"*) ;; *) continue;; esac;; esac; "
            // 刚建好、还没 prune 的空间：只报空间本身，不拉应用列表 ——
            // 此时新空间的系统应用还没禁用，拉出来会先铺一屏再消失（G7 实测）
            + "case \" $PEND \" in *\" $u \"*) echo \"SPACE $u\" > $SD/$u.txt; continue;; esac; "
            + "( case \" $OURS \" in *\" $u \"*) echo \"SPACE $u\"; t=ICO;; "
            + "*) echo \"FSPACE $u\"; t=EXT;; esac; "            + "pm list packages -e --user $u | sed \"s/^package:/PKG $u /\"; "
            + "pm list packages -d --user $u | sed \"s/^package:/OFF $u /\"; "
            + "cmd package query-activities --user $u"
            + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            + " | sed -n \"s/.*packageName=\\([^ ]*\\).*/$t $u \\1/p\" | sort -u"
            + " ) > $SD/$u.txt & done; wait; }; "
            + "cat $SD/*.txt 2>/dev/null; rm -rf $SD; ";

    /** 拼上「我们建的空间」清单再跑 SCAN */
    static String listBatch(Context c) {
        attach(c);
        StringBuilder sb = new StringBuilder("OURS=\" ");
        for (Integer u : markedUids()) sb.append(u).append(' ');
        sb.append("\"; PEND=\" ");
        synchronized (PENDING) {
            for (Integer u : PENDING) sb.append(u).append(' ');
        }
        sb.append("\"; ");
        return sb.toString() + SCAN;
    }

    /** 建空间命令：算出「不装集合」一起带给 CLI，新空间建号时就不装这些应用 */
    static String createCmd(Context c, String name) {
        attach(c);
        StringBuilder sb = new StringBuilder(CLI).append(" create '")
                .append(name.replace("'", "")).append("'");
        StringBuilder ni = new StringBuilder();
        for (String p : notInstallSet(c)) {
            if (ni.length() > 0) ni.append(',');
            ni.append(p);
        }
        if (ni.length() > 0) sb.append(" '").append(ni).append("'");
        return sb.toString();
    }

    /** 主空间全部系统包（缓存；失败返回空集，调用方按「没算出来」处理） */
    static Set<String> systemPkgs() {
        Set<String> s = SYS_PKGS;
        if (s != null) return s;
        Set<String> out = new HashSet<String>();
        String r = Shell.exec("pm list packages -s 2>/dev/null");
        for (String line : r.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) out.add(t.substring(8).trim());
        }
        if (!out.isEmpty()) SYS_PKGS = out;
        return out;
    }

    /**
     * 空间跑起来的最低要求：这些包必须装进去，其余系统包一律不装。
     *
     * <p>不硬编码整份清单：按包名特征匹配主空间实际存在的包，ROM 换了也不会漏
     * （实测魅族文件选择器叫 {@code com.meizu.picker}，AOSP 叫 {@code documentsui}）。
     * 三家参考实现的分身用户只有 8–10 个包，共同项只有 android / WebView / GMS。
     */
    static Set<String> essentialPkgs(Context c) {
        Set<String> out = new HashSet<String>();
        out.add("android");
        Set<String> all = systemPkgs();
        for (String p : all) {
            if (p.endsWith(".documentsui")          // AOSP 文件选择器
                    || p.contains("picker")          // 厂商文件选择器
                    || p.contains("permissioncontroller")
                    || p.contains("packageinstaller")
                    || p.endsWith(".ext.services")
                    || p.equals("com.android.phone")
                    || p.equals("com.android.keychain")
                    || p.contains("soterserver")     // 支付安全组件
                    || p.contains("ifaa")
                    || p.contains("providers.media")
                    // ↓ 缺了这三个，空间里的应用根本起不来（G7 实测 2026-11-27）
                    || p.endsWith("providers.settings")   // Settings.Global 的提供者，
                                                          // 少了它任何 Activity 在 PhoneWindow
                                                          // 构造时就 NPE 闪退
                    || p.endsWith("externalstorage")      // /storage 访问
                    || p.endsWith("providers.downloads")  // 下载管理器
                    || p.equals("com.android.settings")   // 「应用信息」页由它提供
                    // ↓ 缺了它，空间里任何应用弹一次 Toast，SystemUI 就崩一次（G7 实测 2026-11-27）
                    //   ToastUI.createContextAsUser(空间用户) 抛 Own package not found
                    //   → SystemUI 进程重启 → 重启后第一屏就是锁屏，用户看到「点一下就锁屏」
                    || p.equals("com.android.systemui")
                    || p.contains("launcher")             // 空间自己的桌面，退出应用有地方可回
                    ) {
                out.add(p);
            }
        }
        out.addAll(mustKeep(c));      // WebView + 全部输入法
        out.addAll(defaultKeep(c));   // GMS / GSF / Vending
        out.addAll(resolverPkgs(c));  // 打开网页/图片/分享文本的默认应用
        return out;
    }

    /**
     * 建号时要排除的包 = 主空间全部系统包 − 必需包 − 禁用清单 − 不装清单。
     *
     * <p>交给系统的 {@code createProfileForUser(..., disallowedPackages)} 一次生效：
     * 建号那一刻这些包在这个用户里就是「未安装」，不需要建完再一个个禁用。
     * G7 实测：传 249 个 → 新空间只剩 8 个包，与系统自带分身完全一致。
     *
     * <p>禁用清单要排除在「不装」之外：它们是「装了但不给用」，得先装进去再禁用。
     */
    static Set<String> notInstallSet(Context c) {
        Set<String> out = new HashSet<String>();
        // 总开关：关掉＝三个清单都不执行，建号就是系统默认行为（全装）
        if (!pruneSys(c)) return out;
        Set<String> all = systemPkgs();
        if (all.isEmpty()) return out;          // 查不到就传空，退回老行为（不误伤）
        Set<String> keep = essentialPkgs(c);
        keep.addAll(preinstallExtra(c));   // 预装清单里额外勾选的系统应用
        keep.addAll(extraDisable(c));
        keep.addAll(extraUninstall(c));
        for (String p : all) {
            if (!keep.contains(p)) out.add(p);
        }
        return out;
    }

    /** 某空间登记的包；null 表示老版本建的空间，没登记过 */
    static Set<String> keptPkgs(Context c, int uid) {
        try {
            return c.getSharedPreferences("clone", Context.MODE_PRIVATE)
                    .getStringSet("space_pkgs_" + uid, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 这个空间里还剩几个「带桌面图标的三方应用」。用来判断空间是不是真的空了。
     * 返回 -1 ＝ 判不了（主身份应用清单还没加载完），调用方不许据此下结论。
     */
    static int iconsInSpace(int uid) {
        if (THIRD.isEmpty()) return -1;
        String out = Shell.exec("cmd package query-activities --user " + uid
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " | sed -n 's/.*packageName=\\([^ ]*\\).*/\\1/p' | sort -u");
        int n = 0;
        for (String p : out.split("\n")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (appCtx != null && t.equals(appCtx.getPackageName())) continue;
            if (THIRD.contains(t)) n++;
        }
        return n;
    }

    static void markKept(Context c, int uid, String pkg) {
        SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
        Set<String> old = sp.getStringSet("space_pkgs_" + uid, null);
        Set<String> s = old == null ? new HashSet<String>() : new HashSet<String>(old);
        if (!s.add(pkg)) return;
        sp.edit().putStringSet("space_pkgs_" + uid, s).apply();
    }

    /**
     * 只有我们建的空间才登记「这个空间该有它」。
     * ⚠非自建空间一旦被登记，loadMarks 的老空间迁移会把它认成我们建的 ——
     * 之后就会被精简、被算进名额、还能被删空间。所以非自建空间一律不登记。
     */
    static void markKeptIfOurs(Context c, int uid, String pkg) {
        ensureMarks();
        if (isOurs(null, uid)) markKept(c, uid, pkg);
    }

    /** 这个空间里是否已经有这个包：我们的空间看登记表，非自建空间没有登记表，直接问系统 */
    static boolean spaceHas(Context c, int uid, String pkg) {
        Set<String> kept = keptPkgs(c, uid);
        if (kept != null) return kept.contains(pkg);
        ensureMarks();
        if (isOurs(null, uid)) return false;   // 老的自己空间没登记表：沿用旧口径（当未装，装了也算幂等）
        return installedIn(uid, pkg);
    }

    /** 系统里这个用户下是否装了这个包（只查启用的；被 disable 的按「未装」处理，装一次会顺手 enable） */
    static boolean installedIn(int uid, String pkg) {
        String out = Shell.exec("pm list packages -e --user " + uid + " " + pkg);
        // pm 的过滤参数是子串匹配（查 via 会带回 via.pro），必须逐行全等
        for (String line : out.split("\n")) {
            if (line.trim().equals("package:" + pkg)) return true;
        }
        return false;
    }

    static void unmarkKept(Context c, int uid, String pkg) {
        SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
        Set<String> old = sp.getStringSet("space_pkgs_" + uid, null);
        if (old == null) return;
        Set<String> s = new HashSet<String>(old);
        if (!s.remove(pkg)) return;
        sp.edit().putStringSet("space_pkgs_" + uid, s).apply();
    }

    // ---------------- 隐藏（纯显示层，不碰系统状态） ----------------

    /** 被用户隐藏的分身（"uid:pkg" 集合）：应用保持启用、能跑，只是不在列表里显示 */
    static Set<String> hiddenClones() {
        Set<String> s = HIDDEN;
        if (s != null) return s;
        synchronized (CloneStore.class) {
            if (HIDDEN != null) return HIDDEN;
            Set<String> v = appCtx == null ? null
                    : appCtx.getSharedPreferences("clone", Context.MODE_PRIVATE)
                    .getStringSet("hidden_clones", null);
            HIDDEN = v == null ? new HashSet<String>() : new HashSet<String>(v);
            return HIDDEN;
        }
    }

    static boolean isHidden(int uid, String pkg) {
        return hiddenClones().contains(uid + ":" + pkg);
    }

    static void hideClone(Context c, int uid, String pkg) {
        SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
        Set<String> old = sp.getStringSet("hidden_clones", null);
        Set<String> s = old == null ? new HashSet<String>() : new HashSet<String>(old);
        s.add(uid + ":" + pkg);
        sp.edit().putStringSet("hidden_clones", s).apply();
        HIDDEN = new HashSet<String>(s);   // copy-on-write，volatile 换引用
        HIDDEN_VER++;
    }

    static void unhideClone(Context c, int uid, String pkg) {
        SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
        Set<String> old = sp.getStringSet("hidden_clones", null);
        if (old == null) return;
        Set<String> s = new HashSet<String>(old);
        if (!s.remove(uid + ":" + pkg)) return;
        sp.edit().putStringSet("hidden_clones", s).apply();
        HIDDEN = new HashSet<String>(s);
        HIDDEN_VER++;
    }

    /** 隐藏清单（配置）：设置页勾选要隐藏的系统应用；建空间时才一次性固化进 hidden_sys */
    static Set<String> hideList() {
        if (appCtx == null) return new HashSet<String>();
        Set<String> s = appCtx.getSharedPreferences("clone", Context.MODE_PRIVATE)
                .getStringSet("hide_list", null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    /** 建空间时把隐藏结果对账到隐藏清单：清单有、结果无的 → hideSys（=前台点隐藏）；
     *  清单无、结果有的 → unhideSys（取消勾选后下次建空间恢复）。复用前台函数、不整体覆盖。 */
    static void applyHideList(Context c) {
        Set<String> want = hideList();
        Set<String> have = hiddenSys();
        for (String p : want) if (!have.contains(p)) hideSys(c, p);
        for (String p : have) if (!want.contains(p)) unhideSys(c, p);
    }

    /** 已隐藏的主空间系统应用（pkg 集合，执行结果）：保持启用、能跑，只不在主界面显示 */
    static Set<String> hiddenSys() {
        if (appCtx == null) return new HashSet<String>();
        Set<String> s = appCtx.getSharedPreferences("clone", Context.MODE_PRIVATE)
                .getStringSet("hidden_sys", null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    static boolean isSysHidden(String pkg) {
        return hiddenSys().contains(pkg);
    }

    static void hideSys(Context c, String pkg) {
        SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
        Set<String> old = sp.getStringSet("hidden_sys", null);
        Set<String> s = old == null ? new HashSet<String>() : new HashSet<String>(old);
        s.add(pkg);
        sp.edit().putStringSet("hidden_sys", s).apply();
    }

    static void unhideSys(Context c, String pkg) {
        SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
        Set<String> old = sp.getStringSet("hidden_sys", null);
        if (old == null) return;
        Set<String> s = new HashSet<String>(old);
        if (!s.remove(pkg)) return;
        sp.edit().putStringSet("hidden_sys", s).apply();
    }

    /**
     * `cmd user list --all -v` 的一行是否属于空间类型。
     * 正常机型是 clone profile；内核带 sdcardfs 的机型上 clone 共享存储挂不上
     * （vold 在 Android/data 的 lookup 被 MediaProvider 的 FUSE 拒掉），
     * CubClone 会降级建成 full.SECONDARY（独立存储），同样算空间。
     */
    static boolean isSpaceType(String line) {
        return line.contains("type=profile.CLONE") || line.contains("type=full.SECONDARY");
    }

    /** 空间名（建空间时给的名字），用来给老空间猜「这个空间本来是给谁建的」 */
    static String spaceName(int uid) {
        String out = Shell.exec("cmd user list --all -v 2>/dev/null");
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (!isSpaceType(t) || !t.contains("id=" + uid + ",")) continue;
            int i = t.indexOf("name=");
            int j = i < 0 ? -1 : t.indexOf(',', i);
            if (j > i) return t.substring(i + 5, j).trim();
        }
        return null;
    }

    /** 自己给过桌面图标的包，同样算用户要的 */
    static Set<String> shortcutPkgs(Context c, int uid) {
        Set<String> out = new HashSet<String>();
        try {
            ShortcutManager sm = (ShortcutManager) c.getSystemService(Context.SHORTCUT_SERVICE);
            if (sm == null) return out;
            String prefix = "clone_" + uid + "_";
            for (ShortcutInfo si : sm.getPinnedShortcuts()) {
                String id = si.getId();
                if (id != null && id.startsWith(prefix)) out.add(id.substring(prefix.length()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 老空间没登记表时的兜底：空间名 == 应用名，就当这个应用是用户要的 */
    static Set<String> sameNamePkgs(Context c, int uid) {
        Set<String> out = new HashSet<String>();
        String name = spaceName(uid);
        if (name == null || name.length() == 0) return out;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                if (ri.activityInfo == null) continue;
                if (name.equals(String.valueOf(ri.loadLabel(pm)))) {
                    out.add(ri.activityInfo.packageName);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 分身里的隐式 Intent 要靠这些应用解析：浏览器（点链接）、看图、文件选择器。
     * 默认打开方式是按用户隔离的 —— 分身里一个解析器都没装的话，
     * 应用点个链接就是 "No activity found"，所以这几个必须留在空间里。
     */
    static Set<String> resolverPkgs(Context c) {
        Set<String> out = new HashSet<String>();
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            addResolver(pm, out, new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("http://example.com/")));
            addResolver(pm, out, new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("content://media/external/images/media/1"))
                    .setType("image/jpeg"));
            addResolver(pm, out, new Intent(Intent.ACTION_SEND).setType("text/plain"));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addResolver(android.content.pm.PackageManager pm, Set<String> out,
                                    Intent i) {
        try {
            android.content.pm.ResolveInfo ri =
                    pm.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) out.add(ri.activityInfo.packageName);
        } catch (Throwable ignored) {
        }
    }

    /** 分身里没它们就用不了的东西（硬必需，锁定不可禁用/卸载）：输入法、WebView */
    static Set<String> mustKeep(Context c) {
        Set<String> out = new HashSet<String>();
        out.add("com.android.webview");
        out.add("com.google.android.webview");
        out.add("com.android.inputmethod.latin");
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            addIme(out, android.provider.Settings.Secure.getString(
                    cr, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD));
            String all = android.provider.Settings.Secure.getString(
                    cr, android.provider.Settings.Secure.ENABLED_INPUT_METHODS);
            if (all != null) {
                for (String s : all.split(":")) addIme(out, s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 默认保留但可改（Play/GMS）：国内外情况不同，国内非必需；默认保留，用户可加黑名单卸载 */
    static Set<String> defaultKeep(Context c) {
        Set<String> out = new HashSet<String>();
        out.add("com.google.android.gms");
        out.add("com.google.android.gsf");
        out.add("com.android.vending");
        return out;
    }

    private static void addIme(Set<String> out, String comp) {
        if (comp == null) return;
        int i = comp.indexOf('/');
        String p = (i > 0 ? comp.substring(0, i) : comp).trim();
        if (p.length() > 0) out.add(p);
    }

    /** 「建空间后禁用多余系统应用」开关（老 key keep_sys 语义相反，读到时迁移成 prune_sys） */
    /**
     * 共享存储是否真的能用：先看卷状态，再实地探一次目录。
     * <p>卷报 mounted 不代表该用户的目录存在——sdcardfs 模式下挂载点由 vold 在
     * 「卷刚 MOUNTED」那一刻批量创建，运行期新建的用户赶不上，目录永远不会出现。
     * 所以只看 sm 的状态会误判为可用，必须再探 `/storage/emulated/&lt;uid&gt;`。
     * <p>这里只做检测，供界面如实提示，不做修复。
     */
    static boolean storageReady(int uid) {
        String out = Shell.exec("sm list-volumes all");
        if (out != null && !out.startsWith("ERR_")) {
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.startsWith("emulated;" + uid + " ")) {
                    if (!t.contains(" mounted")) return false;
                    break;
                }
            }
        }
        String probe = Shell.exec("test -d /storage/emulated/" + uid + " && echo D_OK || echo D_NO");
        return probe != null && probe.contains("D_OK");
    }

    static boolean pruneSys(Context c) {
        SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
        if (sp.contains("keep_sys")) {
            boolean v = !sp.getBoolean("keep_sys", false);
            sp.edit().putBoolean("prune_sys", v).remove("keep_sys").apply();
            return v;
        }
        return sp.getBoolean("prune_sys", true);
    }

    /** 用户勾选的「禁用」系统应用（建空间时 pm disable-user，不可用但保留安装记录） */
    static Set<String> extraDisable(Context c) {
        Set<String> s = c.getSharedPreferences("clone", Context.MODE_PRIVATE)
                .getStringSet("disable_extra", null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    /** 用户勾选的「彻底卸载」系统应用（prune 时最高优先级：直接 uninstall，而不是 disable） */
    static Set<String> extraUninstall(Context c) {
        Set<String> s = c.getSharedPreferences("clone", Context.MODE_PRIVATE)
                .getStringSet("uninstall_extra", null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    /**
     * 用户额外勾选的「预装」系统应用：本来会被排除，勾了就跟着底线一起装进新空间。
     * 底线（{@link #essentialPkgs}）不可取消，不在这里。
     */
    static Set<String> preinstallExtra(Context c) {
        Set<String> s = c.getSharedPreferences("clone", Context.MODE_PRIVATE)
                .getStringSet("preinstall_extra", null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    /** 内置必需白名单（文件选择器/相机/通讯录/拨号/短信/媒体库）；与 pruneSpace 的 ESS 字符串保持一致 */
    static boolean isEssentialPkg(String p) {
        return p.endsWith(".documentsui") || p.contains(".camera")
                || p.contains("contacts") || p.contains("dialer")
                || p.contains("messaging") || p.contains("providers.media");
    }

    /**
     * 清掉分身空间里「用户没要」的东西。
     *
     * 判据＝登记表（＋给过桌面图标的包）；不再按「三方 / 系统」猜 —— 一加那种
     * 连主身份里 25 个三方应用一起灌进新空间的系统，按老判据一个都删不掉。
     *
     * 两遍：① 所有带桌面图标的包，只留登记表 / 解析器 / 输入法 / Play 这些；
     *       ② 再扫一遍三方包，连**没有桌面图标**的那些也删（它们只是被灌进来的，
     *          留着会在分身里偷偷起服务、收推送）。
     *
     * @param fresh true＝这个空间刚建出来（不可能有用户数据），登记表为空也照删；
     *              false＝清理老空间，什么线索都没有就不动，宁可留一堆也不误删。
     * @return 重试一次后仍然没删掉的包数
     * ⚠只 `pm disable-user --user <uid>`（禁用），主身份那份毫发无损，随时 `pm enable` 装回。
     */
    static int pruneSpace(Context c, int uid, boolean fresh) {
        if (!Launcher.safe(uid)) return 0;
        applyHideList(c);   // 建空间时执行隐藏清单：等价于对清单里每个系统应用前台点一次隐藏
        Set<String> rec = keptPkgs(c, uid);
        Set<String> keep = rec == null ? new HashSet<String>() : new HashSet<String>(rec);
        keep.addAll(shortcutPkgs(c, uid));
        if (!fresh) keep.addAll(sameNamePkgs(c, uid));
        // 解析器 / 输入法 / Play 一律留着，否则分身里打不了字、点链接没反应
        keep.addAll(resolverPkgs(c));
        keep.addAll(mustKeep(c));
        keep.addAll(defaultKeep(c));   // Play/GMS 默认保留，但可加卸载清单卸载
        StringBuilder kp = new StringBuilder(" ");
        for (String p : keep) kp.append(p).append(' ');
        StringBuilder ul = new StringBuilder(" ");   // 不装清单（老空间补卸）：新空间建号时已经没装
        if (!fresh) for (String p : extraUninstall(c)) ul.append(p).append(' ');
        StringBuilder di = new StringBuilder(" ");   // 禁用清单：禁用（不可用，先于保留）
        for (String p : extraDisable(c)) di.append(p).append(' ');
        String q = String.valueOf((char) 34);      // "
        final String ESS = "*.documentsui|*.camera*|*contacts*|*dialer*"
                + "|*messaging*|*providers.media*";
        String cmd =
                "KP=" + q + kp + q + "; UL=" + q + ul + q + "; DI=" + q + di + q
                + "; DL=" + q + " " + q + "; "
                + "un() { "
                + "case " + q + "$UL" + q + " in *" + q + " $1 " + q + "*) "
                + "pm uninstall --user " + uid + " " + q + "$1" + q
                + " >/dev/null 2>&1 || echo " + q + "FAIL $1" + q + "; "
                + "DL=" + q + "$DL$1 " + q + "; return 0;; esac; "
                + "case " + q + "$DI" + q + " in *" + q + " $1 " + q + "*) "
                + "pm disable-user --user " + uid + " " + q + "$1" + q
                + " >/dev/null 2>&1 || echo " + q + "FAIL $1" + q + "; "
                + "DL=" + q + "$DL$1 " + q + "; return 0;; esac; "
                + "case " + q + "$KP" + q + " in *" + q + " $1 " + q + "*) return 0;; esac; "
                + "case " + q + "$DL" + q + " in *" + q + " $1 " + q + "*) return 0;; esac; "
                + "case " + q + "$1" + q + " in " + ESS + ") return 0;; esac; "
                + "pm disable-user --user " + uid + " " + q + "$1" + q
                + " >/dev/null 2>&1 || echo " + q + "FAIL $1" + q + "; "
                + "DL=" + q + "$DL$1 " + q + "; }; "
                + "for p in $(cmd package query-activities --user " + uid
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " | sed -n " + q + "s/.*packageName=\\([^ ]*\\).*/\\1/p" + q
                + " | sort -u); do un $p; done; "
                + "for p in $(pm list packages -3 -e --user " + uid
                + " | sed " + q + "s/^package://" + q + "); do un $p; done";
        String out = Shell.exec(cmd);

        List<String> fails = new ArrayList<String>();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("FAIL ")) fails.add(t.substring(5).trim());
        }
        if (fails.isEmpty()) return 0;
        // 刚建好的用户里偶发 "Failure [not installed for user]"（包正在被系统处理），重试一次
        int still = 0;
        for (String p : fails) {
            String r = Launcher.disable(uid, p).toLowerCase();
            if (r.contains("success") || r.contains("not installed")) continue;
            still++;
            android.util.Log.w("CubClone", "prune fail uid=" + uid + " " + p + " " + r.trim());
        }
        return still;
    }

    /**
     * 反向操作：把主身份里的系统应用重新装回分身空间。
     * 全部是 `pm install-existing --user`，只动该用户的安装记录，不碰主身份。
     */
    static void restoreSpace(int uid) {
        if (!Launcher.safe(uid)) return;
        Shell.exec("for p in $(pm list packages -s); do p=${p#package:}; "
                + "pm install-existing --user " + uid + " $p >/dev/null 2>&1; "
                + "pm enable --user " + uid + " $p >/dev/null 2>&1; done");
    }

    /**
     * 空间显示名：系统里的名字 → 「空间 <uid>」兜底。
     * 系统名只能建空间时给（setUserName 要 MANAGE_USERS，shell 身份必被拒）。
     */
    static String nameOf(Context c, int uid) {
        // 空间名就是系统名（LSP 管理器里看到的那个），一套名字，不再另存「界面名」。
        String sys = SPACE_NAMES.get(Integer.valueOf(uid));
        if (sys == null || sys.trim().length() == 0) {
            sys = spaceName(uid);          // 外部空间不在缓存里，现查一次
        }
        if (sys != null && sys.trim().length() > 0) {
            String t2 = sys.trim();
            if (t2.length() > 0 && !t2.equalsIgnoreCase("space")) return t2;
        }
        // 兜底（系统名是 space / 读不到）：直接用空间 id。
        // ⛔ 不能按「第几个建的空间」编号：那个号随建的顺序排、删过空间还会留洞，
        // 同一个空间昨天叫「空间 2」今天可能叫「空间 24」，用户看到的号会自己变。
        return c.getString(R.string.space_name, String.valueOf(uid));
    }

    /**
     * 这个 CLONE 空间的存储（/data/user/N、/data/media/N）是否已就绪。
     * createProfileForUser 只建号、不建存储：存储是首次 start-user 时由系统
     * prepareUserStorage 创建并打上 INITIALIZED 标志。没 INITIALIZED 的分身，
     * 备份还原会报 /data/user/N 不存在。
     */
    static boolean spaceReady(int uid) {
        String out = Shell.exec("cmd user list --all -v 2>/dev/null");
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (isSpaceType(t) && t.contains("id=" + uid + ",")) {
                return t.contains("INITIALIZED");
            }
        }
        return true; // 查不到就不折腾（多半是已删除的 stale uid）
    }

    /** 补 start 一个未初始化的空间并等它就绪。走 Shell.exec，Shizuku shell 身份即可，无需 root。 */
    static void startSpace(final int uid) {
        if (spaceReady(uid)) return;
        Shell.exec("am start-user " + uid);
        for (int i = 0; i < 20 && !spaceReady(uid); i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }
    }

    /**
     * 等新空间的共享存储挂好（CLI settle，最长 25 秒，跑在后台线程）。
     * 建号已经返回了，这一步只影响「相册/文件能不能和主空间共用」，不挡界面。
     */
    static boolean settleSpace(Context c, int uid) {
        attach(c);
        return Shell.exec(CLI + " settle " + uid).contains("MODE=shared");
    }

    /**
     * 建号后的「就绪」步骤：只等存储，完了立刻摘掉「准备中」。
     * 装输入法、预处理系统应用都不影响「空间能不能用」，放到 {@link #cleanSpace} 里后置，
     * 否则「准备中」要挂十几秒（G7 实测装输入法就占了大头）。
     */
    static boolean readySpace(Context c, int uid) {
        boolean shared = false;
        try {
            shared = settleSpace(c, uid);
            markOurs(uid);
        } finally {
            clearPending(uid);
        }
        return shared;
    }

    /**
     * 建号后的收尾：装输入法 + 只禁用你勾的那几个 + 执行隐藏清单。
     *
     * <p>系统应用在建号时就被排除了（见 {@link #notInstallSet}），这里**不再批量禁用**——
     * 以前那套「先全装 237 个、再逐个 pm disable」是 11-05 走反了的路，三家参考实现
     * 没有一家这么做，而且每个包起一次进程，24 个包就是 4–8 秒的 CPU 高峰。
     *
     * <p>没被排除掉的老空间仍可通过 {@link #pruneSpace} 手动重扫。
     */
    static int cleanSpace(Context c, int uid) {
        try {
            ensureIme(uid);                     // 输入法是「空间能用」的前提，不受清单开关管
            markSetupComplete(uid);             // 缺了它最近任务不给这个空间的应用加载图标和标题
            if (pruneSys(c)) {                  // 总开关：关掉＝禁用清单和隐藏清单都不执行
                applyDisableList(c, uid);
                applyHideList(c);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 标记「用户设置已完成」。缺了它，launcher 的最近任务不会给这个用户的应用加载
     * 图标和标题（{@code Task.mUserSetupComplete=false}），卡片切走一次就消失。
     *
     * <p>AOSP 里这一步平时由 SetupWizard 做；我们用 {@code createProfileForUser} 直接建号，
     * 没人做，所以从第一版起这个标记一直是 0（2026-11-27 G7 实测：系统分身 999 是 1，
     * 自建空间是 0，同一张芝麻粒卡片在 999 里有图标名字、在我们空间里全空）。
     */
    static void markSetupComplete(int uid) {
        if (!Shell.ready()) return;
        if (!Launcher.safe(uid)) return;
        Shell.exec("settings put --user " + uid + " secure user_setup_complete 1");
    }

    /** 老空间补「用户设置完成」标记：一次 shell 批量查，只对没置上的写。 */
    static void ensureSetupComplete(Set<Integer> uids) {
        if (uids == null || uids.isEmpty() || !Shell.ready()) return;
        StringBuilder sb = new StringBuilder();
        for (Integer u : uids) {
            int uid = u.intValue();
            if (!Launcher.safe(uid)) continue;
            sb.append("v=$(settings get --user ").append(uid)
                    .append(" secure user_setup_complete); [ \"$v\" = 1 ] || settings put --user ")
                    .append(uid).append(" secure user_setup_complete 1; ");
        }
        if (sb.length() > 0) Shell.exec(sb.toString());
    }

    /**
     * 禁用清单：装了但不给用。先确保装上（万一被排除在「不装」之外算错了），再禁用。
     * 只处理用户勾选的几个包，不是全量遍历。
     */
    static void applyDisableList(Context c, int uid) {
        Set<String> dis = extraDisable(c);
        if (dis.isEmpty() || !Launcher.safe(uid)) return;
        StringBuilder sb = new StringBuilder();
        for (String p : dis) {
            sb.append("pm install-existing --user ").append(uid).append(' ').append(p)
                    .append(" >/dev/null 2>&1; ");
            sb.append("pm disable-user --user ").append(uid).append(' ').append(p)
                    .append(" >/dev/null 2>&1; ");
        }
        Shell.exec(sb.toString());
    }

    /**
     * 建号之后的收尾（兼容老调用点）：就绪 + 预处理一次做完。
     * 界面要即时反馈时用 {@link #readySpace} + {@link #cleanSpace} 分两步。
     */
    static boolean prepareSpace(Context c, int uid) {
        boolean shared = readySpace(c, uid);
        cleanSpace(c, uid);
        return shared;
    }

    /**
     * 本机分身存储状态：
     * 0＝本机不需要（内核没有 sdcardfs 驱动）
     * 1＝需要修（有 sdcardfs 驱动，且 vold 真的在用 → 分身卷 unmountable）
     * 2＝已修（有驱动，但一个 sdcardfs 挂载都没有 → 走的是纯 FUSE，和正常机型一致）
     * null＝判不了（提权没就绪，或还在 BFU、存储没挂）
     *
     * 判断必须提权执行：应用进程读 /proc/filesystems 会被 SELinux 拒
     * （avc denied untrusted_app proc_filesystems:file read），拿到空串必然漏报。
     *
     * 判据只用「驱动是否存在 + 系统里 sdcardfs 挂载数」两个客观事实，
     * 不看任何属性 —— 属性只在开机瞬间有意义，事后读出来是空（11-18 实测）。
     */
    /**
     * 设备上已装模块的版本，不需要任何权限。
     * 模块 2.4 起在 system.prop / post-fs-data 里留了 sys.cubclone.module.code，
     * 普通进程 getprop 就能读——不用 su，也不用碰 /data/adb（普通应用根本进不去）。
     * @return {code, version}；null = 没装，或是 2.3 及更早（那时还没有这个标记）
     */
    public static String[] moduleInstalled() {
        String code = Shell.raw("getprop", "sys.cubclone.module.code").trim();
        if (!code.matches("\\d+")) return null;
        String v = Shell.raw("getprop", "sys.cubclone.module.version").trim();
        return new String[]{code, v.length() == 0 ? code : v};
    }

    // ---------------- 「解锁数量上限」模块（LSPosed） ----------------

    /** 目标上限的硬边界，与 hook 里的 MAX_LIMIT 保持一致 */
    public static final int HOOK_MAX = 21474;
    public static final int HOOK_DEFAULT = 10;

    /** persist 属性重启后还在；hook 启动时优先读它，读不到才用默认值 */
    static final String HOOK_LIMIT_PERSIST = "persist.sys.cubclone.hook.limit";

    /** 模块包名（增强插件是独立 APK，装在设备上但不显示图标） */
    static final String HOOK_PKG = "com.cubsync.cubclone.hook";

    /**
     * hook 是否已跑起来。两个证据任一成立即可：
     * <ul>
     *   <li>{@code sys.cubclone.hook}=1：system_server 里挂上了（数量上限）；</li>
     *   <li>{@code sys.cubclone.hook.lw}>0：桌面进程里挂上了（禁用补图标）。</li>
     * </ul>
     * 只看前者的话，system 没加载、只有桌面进程加载时会被误判成「已装未生效」。
     */
    public static boolean hookActive() {
        if ("1".equals(Shell.raw("getprop", "sys.cubclone.hook").trim())) {
            return true;
        }
        String lw = Shell.raw("getprop", "sys.cubclone.hook.lw").trim();
        return lw.matches("\\d+") && Integer.parseInt(lw) > 0;
    }

    /**
     * 正在运行的插件版本：hook 在 system_server 启动时上报 {@code sys.cubclone.hook.ver}。
     * 这是「当前真正在跑的那一版」——重装 APK 但没重启时它还是旧值。
     * 1.0.7 及更早的模块不写这个属性，那时返回 null（调用方按「运行中但版本未知」显示）。
     */
    public static String hookRunningVersion() {
        String v = Shell.raw("getprop", "sys.cubclone.hook.ver").trim();
        return v.length() == 0 ? null : v;
    }

    /**
     * 设备上已安装的插件版本（模块 APK 的 versionName）；没装返回 null。
     *
     * <p>必须走 PackageManager：普通应用跑 {@code dumpsys package} 没有 DUMP 权限，
     * 拿到的是空输出，之前就是这样把「已装版本」读成 null，界面显示成「已装?」。
     */
    public static String hookInstalledVersion() {
        try {
            if (appCtx != null) {
                android.content.pm.PackageInfo pi =
                        appCtx.getPackageManager().getPackageInfo(HOOK_PKG, 0);
                String v = pi.versionName == null ? "" : pi.versionName.trim();
                return v.length() == 0 ? null : v;
            }
        } catch (Throwable ignored) {
            // 没装 → NameNotFoundException；其它异常同样按「读不到」处理
        }
        return null;
    }

    /**
     * 主应用里内嵌的插件版本（assets/CubCloneHook.apk）。它就是「可用的最新版」，
     * 不需要联网：出主应用时带的就是哪一版。
     */
    public static String hookBundledVersion() {
        if (appCtx == null) return null;
        java.io.File f = new java.io.File(appCtx.getCacheDir(), "hook-bundled.apk");
        try {
            java.io.InputStream in = appCtx.getAssets().open("CubCloneHook.apk");
            java.io.OutputStream out = new java.io.FileOutputStream(f);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
                try { out.close(); } catch (Throwable ignored) { }
            }
            android.content.pm.PackageInfo pi = appCtx.getPackageManager()
                    .getPackageArchiveInfo(f.getAbsolutePath(), 0);
            String v = pi == null || pi.versionName == null ? "" : pi.versionName.trim();
            return v.length() == 0 ? null : v;
        } catch (Throwable t) {
            return null;
        } finally {
            try { f.delete(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 比较两个版本号：&gt;0 表示 a 比 b 新。
     * 主段按数字比；后缀按 dev &lt; alpha &lt; beta &lt; rc &lt; 正式，同后缀比 N。
     */
    public static int verCmp(String a, String b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        String[] pa = a.split("-", 2);
        String[] pb = b.split("-", 2);
        String[] na = pa[0].split("\\.");
        String[] nb = pb[0].split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            int x = i < na.length ? toInt(na[i]) : 0;
            int y = i < nb.length ? toInt(nb[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        int sa = suffixRank(pa.length > 1 ? pa[1] : null);
        int sb = suffixRank(pb.length > 1 ? pb[1] : null);
        if (sa != sb) return sa < sb ? -1 : 1;
        int xa = suffixNum(pa.length > 1 ? pa[1] : null);
        int xb = suffixNum(pb.length > 1 ? pb[1] : null);
        if (xa != xb) return xa < xb ? -1 : 1;
        return 0;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** dev=0 alpha=1 beta=2 rc=3 正式=4 */
    private static int suffixRank(String suf) {
        if (suf == null || suf.length() == 0) return 4;
        if (suf.startsWith("dev")) return 0;
        if (suf.startsWith("alpha")) return 1;
        if (suf.startsWith("beta")) return 2;
        if (suf.startsWith("rc")) return 3;
        return 4;
    }

    private static int suffixNum(String suf) {
        if (suf == null) return 0;
        int i = suf.indexOf('.');
        return i < 0 ? 0 : toInt(suf.substring(i + 1));
    }

    /**
     * 解锁模块生效时返回实际目标值（&gt;0）；没装、没在 LSPosed 里启用、或 hook 没跑起来返回 null。
     * 只看 {@code sys.cubclone.hook}：这个属性只有 system_server 里的 hook 会写，普通进程 getprop 就能读。
     */
    public static Integer hookLimit() {
        if (!"1".equals(Shell.raw("getprop", "sys.cubclone.hook").trim())) return null;
        String v = Shell.raw("getprop", HOOK_LIMIT_PERSIST).trim();
        if (v.length() == 0) v = Shell.raw("getprop", "sys.cubclone.hook.limit").trim();
        try {
            int n = Integer.parseInt(v);
            if (n >= 1 && n <= HOOK_MAX) return Integer.valueOf(n);
        } catch (Throwable ignored) {
        }
        return Integer.valueOf(HOOK_DEFAULT);
    }

    /**
     * 写目标上限。persist 属性重启后不丢，hook 下次开机直接读到；
     * 同时写一份运行时属性，让已生效的 system_server 状态在界面上能读回一致的值。
     * @return 失败原因，null = 成功
     */
    public static String writeHookLimit(int n) {
        if (n < 1 || n > HOOK_MAX) return "range";
        String out = Shell.exec("setprop " + HOOK_LIMIT_PERSIST + " " + n);
        String back = Shell.raw("getprop", HOOK_LIMIT_PERSIST).trim();
        if (!String.valueOf(n).equals(back)) {
            return out.length() == 0 ? "readback=" + back : out.trim();
        }
        return null;
    }

    /** assets 里内置的解锁模块（app/build.sh 从 dist/ 拷进来） */
    static final String HOOK_ASSET = "CubCloneHook.apk";

    /** 安装拦截开关：hook 每次拦截现读这个属性，缺省 1（开） */
    public static final String HOOK_INSTALL_PERSIST = "persist.sys.cubclone.hook.install";

    /**
     * 空间增删改后打个「待刷新」标记：进程被杀也不会丢，主页 onResume 消费一次。
     * 只靠静态 dirty 不行 —— 建空间是在后台线程里跑完的，用户可能已经先回到主页。
     */
    static void markSpacesDirty(Context c) {
        dirty = true;
        try {
            c.getSharedPreferences("clone", Context.MODE_PRIVATE)
                    .edit().putBoolean("spaces_dirty", true).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 取并清「待刷新」标记（只让主页刷一次，别每次 onResume 都重扫） */
    static boolean takeSpacesDirty(Context c) {
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
            boolean v = sp.getBoolean("spaces_dirty", false);
            if (v) sp.edit().putBoolean("spaces_dirty", false).apply();
            return v;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 读安装拦截开关。
     * @return null ＝ 没 root 读不到；属性没写过按「开」算（和 hook 的缺省一致）
     */
    public static Boolean installConverge() {
        if (!Shell.ready()) return null;
        String v = Shell.raw("getprop", HOOK_INSTALL_PERSIST).trim();
        if (v.length() == 0) return Boolean.TRUE;
        return Boolean.valueOf(!"0".equals(v));
    }

    // ── 桌面防护（建空间不往主空间桌面补图标） ─────────────────────

    public static final String HOOK_GUARD_PERSIST = "persist.sys.cubclone.guard";
    /** 主桌面是否隐藏我们建的空间（框架层两条钩子）：1=隐藏（默认），0=恢复原厂 */
    public static final String HOOK_HIDE_PERSIST = "persist.sys.cubclone.hide";

    /** 读「主桌面隐藏分身应用」开关：属性没写过按「开」算。null = 没 root。 */
    public static Boolean hideOn() {
        if (!Shell.ready()) return null;
        String v = Shell.raw("getprop", HOOK_HIDE_PERSIST).trim();
        if (v.length() == 0) return Boolean.TRUE;
        return Boolean.valueOf(!"0".equals(v));
    }

    /** 写「主桌面隐藏分身应用」开关。插件每次判断现读，立即生效、不用重启。 */
    public static String writeHide(boolean on) {
        String val = on ? "1" : "0";
        String out = Shell.exec("setprop " + HOOK_HIDE_PERSIST + " " + val);
        String back = Shell.raw("getprop", HOOK_HIDE_PERSIST).trim();
        if (!val.equals(back)) {
            return out.length() == 0 ? "readback=" + back : out.trim();
        }
        return null;
    }

    /** 读桌面防护开关：属性没写过按「开」算（和插件缺省一致）。null ＝ 没 root。 */
    public static Boolean guardOn() {
        if (!Shell.ready()) return null;
        String v = Shell.raw("getprop", HOOK_GUARD_PERSIST).trim();
        if (v.length() == 0) return Boolean.TRUE;
        return Boolean.valueOf(!"0".equals(v));
    }

    /** 写桌面防护开关。插件每次拦截现读，立即生效、不用重启桌面。 */
    public static String writeGuard(boolean on) {
        String val = on ? "1" : "0";
        String out = Shell.exec("setprop " + HOOK_GUARD_PERSIST + " " + val);
        String back = Shell.raw("getprop", HOOK_GUARD_PERSIST).trim();
        if (!val.equals(back)) {
            return out.length() == 0 ? "readback=" + back : out.trim();
        }
        return null;
    }

    /** 桌面进程里各挂载点的状态，如 {@code pc1,add1,task2,prov1,on1}；没跑起来返回空串。 */
    public static String guardPoints() {
        return Shell.raw("getprop", "sys.cubclone.hook.lw.pts").trim();
    }

    /** 模块是否进过桌面进程（进过就写 1）。空串 = 从没加载过，和「加载了但没挂上」是两回事。 */
    public static String guardAlive() {
        return Shell.raw("getprop", "sys.cubclone.hook.lw.alive").trim();
    }

    /** 桌面 APK 里出现这些类，说明它有「包变更后把主空间应用全铺一遍」这条私有链路。 */
    private static final String[] GUARD_CLASSES = {
            "PackageUpdateReceiverUtil",
            "OplusLauncherProvider",
            "OplusPackageUpdatedTask",
            "OplusLauncherModel",
    };

    /**
     * 本机默认桌面自身有没有「建号后往主空间补图标」这条实现 —— 直接看它 APK 里的类，
     * 不看品牌、不看系统版本：出厂就没这段代码的机器，本来就不需要防护。
     *
     * @return TRUE＝有这行为、需要拦；FALSE＝本机桌面没这段代码；null＝探不到（权限/工具缺失）
     */
    public static Boolean guardNeeded() {
        if (!Shell.ready()) return null;
        if (guardNeedVal != null) return guardNeedVal;
        StringBuilder cmd = new StringBuilder();
        cmd.append("p=$(cmd package resolve-activity -a android.intent.action.MAIN")
                .append(" -c android.intent.category.HOME 2>/dev/null")
                .append(" | grep -m1 packageName= | sed 's/.*packageName=//' | tr -d '\\r');")
                .append(" [ -n \"$p\" ] || exit 3;")
                .append(" command -v unzip >/dev/null 2>&1 || exit 4;")
                .append(" n=0;")
                .append(" for f in $(pm path $p 2>/dev/null | sed 's/package://'); do")
                .append("   c=$(unzip -p \"$f\" 'classes*.dex' 2>/dev/null | grep -a -c");
        for (String k : GUARD_CLASSES) {
            cmd.append(" -e ").append(k);
        }
        cmd.append("); n=$((n+${c:-0})); done; echo \"NEED=$n\"");
        String out = Shell.exec(cmd.toString());
        int i = out.indexOf("NEED=");
        if (i < 0) {
            android.util.Log.i("CubClone", "guardNeeded: " + out.trim());
            return null;
        }
        String num = out.substring(i + 5).trim();
        int e = 0;
        while (e < num.length() && num.charAt(e) >= '0' && num.charAt(e) <= '9') e++;
        if (e == 0) return null;
        Boolean v;
        try {
            v = Boolean.valueOf(Integer.parseInt(num.substring(0, e)) > 0);
        } catch (Throwable t) {
            return null;
        }
        // 桌面 APK 没变就一直用这次结果：扫一遍几十 MB 的 dex 要一秒，不必每次进页面都扫
        guardNeedVal = v;
        return v;
    }

    /** 探测结果（进程内缓存）。null ＝ 还没探过 / 探不到。 */
    private static Boolean guardNeedVal;

    /** 换桌面、升级桌面后要让下一次进页面重探。 */
    public static void guardNeededReset() {
        guardNeedVal = null;
    }

    /** 桌面防护实际拦下的次数；0 表示从没触发过，空串表示属性没写。 */
    public static String guardHits() {
        return Shell.raw("getprop", "sys.cubclone.hook.lw.hits").trim();
    }

    /**
     * 写安装拦截开关。hook 每次拦截现读，所以**立即生效、不用重启**。
     * @return 失败原因，null = 成功
     */
    public static String writeInstallConverge(boolean on) {
        String val = on ? "1" : "0";
        String out = Shell.exec("setprop " + HOOK_INSTALL_PERSIST + " " + val);
        String back = Shell.raw("getprop", HOOK_INSTALL_PERSIST).trim();
        if (!val.equals(back)) {
            return out.length() == 0 ? "readback=" + back : out.trim();
        }
        return null;
    }

    /**
     * 建号链上的实际有效上限：两道闸取小的那个。一次 dumpsys 同时读两个字段，
     * 各自读一次要跑两遍 shell（dumpsys user 几百毫秒）。
     * @return <=0 表示这台机器两道闸都不限
     */
    public static int readCap() {
        int per = -1, all = -1;
        try {
            String out = Shell.exec("dumpsys user");
            int i = out.indexOf("android.os.usertype.profile.CLONE:");
            if (i >= 0) {
                String sub = out.substring(i, Math.min(out.length(), i + 600));
                for (String line : sub.split("\n")) {
                    String ln = line.trim();
                    if (ln.startsWith("mMaxAllowedPerParent:")) per = num(ln);
                    else if (ln.startsWith("mMaxAllowed:")) all = num(ln);
                }
            }
        } catch (Throwable ignored) {
        }
        return effectiveCap(per, all);
    }

    /** 两道闸谁小谁说了算；任意一个 <= 0 视为「不限」，只取另一个 */
    public static int effectiveCap(int perParent, int maxAll) {
        if (perParent <= 0) return maxAll <= 0 ? 0 : maxAll;
        if (maxAll <= 0) return perParent;
        return Math.min(perParent, maxAll);
    }

    private static int num(String line) {
        try {
            return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 把内置的解锁模块装进系统。
     * 只负责「装」：LSPosed 里的启用和重启必须用户自己来 —— 改 LSPosed 的配置库属于侵入，
     * 它自己的守护进程会覆盖，不能当产品功能。
     * @return null = 成功；否则是失败原因
     */
    public static String installUnlockModule(Context c) {
        byte[] data;
        try {
            java.io.InputStream in = c.getAssets().open(HOOK_ASSET);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            data = bos.toByteArray();
        } catch (Throwable t) {
            return "no-asset(" + t.getClass().getSimpleName() + ")";
        }
        String path = "/data/local/tmp/CubCloneHook.apk";
        String err = Shell.write(path, data);
        if (err != null && err.length() > 0) return "write: " + err;
        // ⛔ 必须带 --user 0：不加这个，pm install 会把模块装进每一个分身空间，
        //    在分身里多出一份没用的 APK，LSPosed 也会对着非 0 用户报「模块未激活」。
        String out = Shell.exec("pm install -r --user 0 " + path);
        return out.contains("Success") ? null : out.trim();
    }

    static Integer storageState() {
        if (!Shell.ready()) return null;
        String out = Shell.exec(
                "if [ \"$(grep -c sdcardfs /proc/filesystems 2>/dev/null)\" = \"0\" ]; then echo NO_NEED; "
                + "elif [ ! -d /storage/emulated/0 ]; then echo UNKNOWN; "
                + "elif [ \"$(mount 2>/dev/null | grep -c sdcardfs)\" -gt 0 ]; then echo BROKEN; "
                + "else echo FIXED; fi");
        if (out.indexOf("FIXED") >= 0) return Integer.valueOf(2);
        if (out.indexOf("BROKEN") >= 0) return Integer.valueOf(1);
        if (out.indexOf("NO_NEED") >= 0) return Integer.valueOf(0);
        return null;
    }

    /** 设备还没解锁：共享存储没挂，迁移做不了（调用方应弹选项窗让用户解锁后重试） */
    // ---------------- 「生成修复模块」 ----------------
    // 模块做两件事：
    // ① 让 vold / MediaProvider 不再走 sdcardfs 分支（纯 FUSE）。内核带 sdcardfs 时，
    //    vold 给 clone 用户 bind Android/data 会先 UnmountTree(target)，而 target 在 FUSE
    //    视图里被 MediaProvider 按「不该由我管」直接回 EACCES —— 整卷回滚成 unmountable。
    //    关掉后 clone 卷正常挂上，且与主空间共用同一份存储（相册是同一份文件）。
    // ② 切到纯 FUSE 后，所有文件读写由 MediaProvider 进程代理。它操作底层文件时受 POSIX
    //    约束：sdcardfs 时代建的文件属主是 media_rw(1023)，MediaProvider 只是 other，
    //    ftruncate 直接 EPERM → SQLite 报 4618 SQLITE_IOERR_SHMOPEN，应用打不开库。
    //    原生 FUSE 机型（无 sdcardfs 的机器）文件属主本来就是 MediaProvider 自己，所以没这问题。
    //    模块把 sdcardfs 时代的属主一次性迁成 FUSE 形态：顶层 Android/media/<包> 归该包，
    //    里面只把残留的 media_rw(1023) 改成 MediaProvider；应用自己建的文件和权限都不动。
    // ⛔ 只改属主，不动内容、不搬动、不删除。
    // 判据只用「/proc/filesystems 里有 sdcardfs」：静态、BFU 也可见。
    // ⛔ 不能拿 mount 或卷状态当判据 —— 脚本跑在 post-fs-data，那时还没解锁，一条挂载都没有。

    /**
     * 模块版本：应用内置的这一份就是「最新」。模块内容有任何改动，必须同时把 code 加一，
     * 否则用户刷了新版也会被判成「已是最新」。
     */
    public static final String MODULE_VERSION = "2.5";
    public static final int MODULE_CODE = 8;

    // id 与 name 都是模块在用户设备上的身份，1.x 发出去之后就不再动：换 id 会变成两个模块并存，
    // 换 name 会让用户在管理器里看到模块「改名」。只有 version / versionCode 可以前进。
    private static final String MODULE_PROP =
            "id=cubclone_sdcardfs_fix\n"
            + "name=CubClone 分身存储修复\n"
            + "version=" + MODULE_VERSION + "\n"
            + "versionCode=" + MODULE_CODE + "\n"
            + "author=cubsync\n"
            + "description=内核带 sdcardfs 的机型上，让 vold/MediaProvider 走纯 FUSE，"
            + "恢复分身空间与主空间共用的存储（相册、文件同一份）。切到 FUSE 后文件由 "
            + "MediaProvider 代理读写，sdcardfs 时代留下的属主会让它改不动这些文件，"
            + "模块会把这些属主一次性迁成 FUSE 形态。只改属主，不动文件内容和位置。"
            + "仅适用于内核带 sdcardfs 的设备；不含该驱动的机型会被拒绝安装。\n";

    /**
     * 管理器若在更早阶段应用 system.prop，这里也备一份，双保险（内容与脚本一致）。
     * 后两行是版本标记：应用用 getprop 就能读到模块装没装、是不是最新，
     * 不需要 root，也不用碰 /data/adb（普通应用进不去）。
     */
    private static final String MODULE_SYSPROP =
            "external_storage.sdcardfs.enabled=false\n"
            + "sys.cubclone.module.code=" + MODULE_CODE + "\n"
            + "sys.cubclone.module.version=" + MODULE_VERSION + "\n";

    /**
     * post-fs-data.sh：只做属性（必须在 vold 启动前），顺带尝试一次属主迁移。
     * resetprop 路径按 APatch → KernelSU → Magisk 依次探测。
     */
    private static final String MODULE_PFD =
            "#!/system/bin/sh\n"
            + "# CubClone 分身共享存储：让 vold 走纯 FUSE（clone 卷才挂得上）\n"
            + "LOG=/data/adb/cubclone-sdcardfs-fix.log\n"
            + "NOW=$(date '+%F %T')\n"
            + "say() { echo \"$NOW [pfd] $*\" >> \"$LOG\"; }\n"
            + "\n"
            + "RP=\"\"\n"
            + "for p in /data/adb/ap/bin/resetprop /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop; do\n"
            + "  if [ -x \"$p\" ]; then RP=\"$p\"; break; fi\n"
            + "done\n"
            + "[ -z \"$RP\" ] && RP=$(command -v resetprop 2>/dev/null)\n"
            + "if [ -z \"$RP\" ]; then say '跳过：找不到 resetprop，未做任何改动'; exit 0; fi\n"
            + "\n"
            + "if ! grep -q sdcardfs /proc/filesystems 2>/dev/null; then\n"
            + "  say '本机内核没有 sdcardfs 驱动，跳过，未做任何改动'; exit 0\n"
            + "fi\n"
            + "\n"
            // 版本标记：system.prop 在部分管理器上不生效时这里兜一次，应用零权限 getprop 可读
            + "\"$RP\" sys.cubclone.module.code " + MODULE_CODE + "\n"
            + "\"$RP\" sys.cubclone.module.version " + MODULE_VERSION + "\n"
            + "BEFORE=$(\"$RP\" external_storage.sdcardfs.enabled 2>/dev/null)\n"
            + "\"$RP\" external_storage.sdcardfs.enabled false\n"
            + "AFTER=$(\"$RP\" external_storage.sdcardfs.enabled 2>/dev/null)\n"
            + "say \"external_storage.sdcardfs.enabled: [$BEFORE] -> [$AFTER]\"\n";

    /**
     * service.sh：把 sdcardfs 时代的属主迁成 FUSE 形态，并且**每次开机都复查一遍**。
     *
     * 纯 FUSE 下所有文件读写都由 MediaProvider 进程代理，它必须能改这些文件。原生 FUSE
     * 机型上文件属主本来就是它（8A 实测：DCIM 里的照片、Android/media/&lt;包&gt; 里的库，
     * 属主都是 com.android.providers.media.module 的 uid）；sdcardfs 时代是 media_rw(1023)，
     * MediaProvider 只是 other，ftruncate 直接 EPERM，SQLite 报 4618 打不开库；现场表现是
     * 应用界面一直转圈（XRadiant 的库就放在 Android/media 里）。
     *
     * 规则（与原生 FUSE 机型一致，不按应用猜归属）：
     *   ① Android/media/&lt;包&gt; 顶层目录 → 该包 uid（scoped storage 的隔离边界），升级后修一次
     *   ② 目录里残留的 media_rw(1023) 内容 → MediaProvider 的 uid，每次开机都查
     * ② 必须每次开机跑：1023 属主会被「不是这个应用」的 root 写入重新带回来（备份恢复、
     * 脚本、别的模块都会），只迁一次就是修一次又被弄坏一次。只挑 -user media_rw，
     * 应用自己建的文件、自己设的权限一律不动。
     */
    private static final String MODULE_SVC =
            "#!/system/bin/sh\n"
            + "# CubClone 分身共享存储：把 sdcardfs 时代的属主迁成 FUSE 形态（顶层一次 + 内容每次开机）\n"
            + "LOG=/data/adb/cubclone-sdcardfs-fix.log\n"
            + "say() { echo \"$(date '+%F %T') [svc] $*\" >> \"$LOG\"; }\n"
            + "\n"
            + "if ! grep -q sdcardfs /proc/filesystems 2>/dev/null; then exit 0; fi\n"
            + "\n"
            + "FLAG=/data/adb/cubclone-sdcardfs-fix.migrated3\n"
            + "PKGLIST=/data/system/packages.list\n"
            + "# 等用户 0 的共享存储可用。\n"
            + "# 这里没有可订阅的事件：解锁改的是挂载与权限，Android/media 这个目录项早就存在\n"
            + "# （实测 mtime 一直没变），inotify 收不到任何 create；模块也没法注入 init 的\n"
            + "# on property:sys.user.0.ce_available 触发器（只支持 sh 与 sepolicy.rule）。\n"
            + "# 所以只能检查，且必须低频：前 60 秒每 2 秒（解锁通常紧跟开机），之后每 15 秒。\n"
            + "# 每次只是一次 stat，命中 dentry 缓存、不落盘；24 小时仍不解锁就放弃本次。\n"
            + "i=0\n"
            + "while [ ! -d /data/media/0/Android/media ]; do\n"
            + "  if [ $i -lt 30 ]; then sleep 2; else sleep 15; fi\n"
            + "  i=$((i+1))\n"
            + "  [ $i -gt 6000 ] && { say 'wait: 24 小时仍未解锁，放弃本次'; exit 0; }\n"
            + "  if [ $((i % 20)) -eq 0 ]; then say \"wait: 已检查 $i 次\"; fi\n"
            + "done\n"
            + "\n"
            // Android 10+ 是 com.android.providers.media.module；9 及以下是 com.android.providers.media。
            // 两个都认，否则在老 sdcardfs 机型上会直接「找不到 MediaProvider」而整个跳过。
            + "MP=$(awk '$1==\"com.android.providers.media.module\"{m=$2; exit} $1==\"com.android.providers.media\"{o=$2} END{if(m!=\"\")print m; else if(o!=\"\")print o}' \"$PKGLIST\" 2>/dev/null)\n"
            + "[ -n \"$MP\" ] || { say '找不到 MediaProvider 的 uid，跳过'; exit 0; }\n"
            + "\n"
            // ① 顶层目录：只在升级后修一次（2.3 把分身的包目录套成了主空间 uid）
            + "if [ ! -f \"$FLAG\" ]; then\n"
            + "  n=0\n"
            + "  for BASE in /data/media/*; do\n"
            + "    U=${BASE##*/}\n"
            + "    case \"$U\" in ''|*[!0-9]*) continue;; esac\n"
            + "    [ -d \"$BASE/Android/media\" ] || continue\n"
            + "    OMODE=$(stat -c %a \"$BASE\" 2>/dev/null)\n"
            + "    chmod 2775 \"$BASE\" 2>/dev/null\n"
            + "    for d in \"$BASE\"/Android/media/*; do\n"
            + "      [ -d \"$d\" ] || continue\n"
            + "      pkg=${d##*/}\n"
            // 分身里该包的真实 uid：优先读系统自己建的数据目录属主（换 ROM / 换设备都准），
            // 读不到才退回 AOSP 的 UserHandle.PER_USER_RANGE 公式 appId + user*100000
            + "      duid=$(stat -c %u \"/data/user/$U/$pkg\" 2>/dev/null)\n"
            + "      if [ -z \"$duid\" ]; then\n"
            + "        puid=$(awk -v p=\"$pkg\" '$1==p{print $2; exit}' \"$PKGLIST\" 2>/dev/null)\n"
            + "        [ -n \"$puid\" ] && duid=$((puid + U * 100000))\n"
            + "      fi\n"
            + "      [ -n \"$duid\" ] && chown \"$duid:1023\" \"$d\" 2>/dev/null && n=$((n+1))\n"
            + "    done\n"
            + "    [ -n \"$OMODE\" ] && chmod \"$OMODE\" \"$BASE\" 2>/dev/null\n"
            + "  done\n"
            + "  say \"migrate: 顶层目录已修 $n 个\"\n"
            + "  touch \"$FLAG\"\n"
            + "fi\n"
            + "\n"
            // ② 内容：每次开机只挑残留的 media_rw(1023)，别的属主不动（借主空间 MediaProvider
            //    身份写文件的应用——XRadiant——属主本来就是 MP，改成别的反而不写）
            + "c=0\n"
            + "for BASE in /data/media/*; do\n"
            + "  U=${BASE##*/}\n"
            + "  case \"$U\" in ''|*[!0-9]*) continue;; esac\n"
            + "  [ -d \"$BASE/Android/media\" ] || continue\n"
            + "  OMODE=$(stat -c %a \"$BASE\" 2>/dev/null)\n"
            + "  chmod 2775 \"$BASE\" 2>/dev/null\n"
            + "  for d in \"$BASE\"/Android/media/*; do\n"
            + "    [ -d \"$d\" ] || continue\n"
            + "    one=$(find \"$d\" -mindepth 1 -user media_rw 2>/dev/null | head -1)\n"
            + "    [ -n \"$one\" ] || continue\n"
            + "    find \"$d\" -mindepth 1 -user media_rw -exec chown \"$MP\" {} + 2>/dev/null\n"
            + "    c=$((c+1))\n"
            + "    say \"heal: ${d##*/} 的 media_rw 属主已改成 MediaProvider($MP)\"\n"
            + "  done\n"
            + "  [ -n \"$OMODE\" ] && chmod \"$OMODE\" \"$BASE\" 2>/dev/null\n"
            + "done\n"
            + "[ \"$c\" -gt 0 ] && say \"heal done, 涉及 $c 个包目录\"\n"
            + "exit 0\n";

    /** 卸载只记一行：属性只改内存，重启回原值；属主不回滚（迁回 sdcardfs 形态反而会坏） */
    private static final String MODULE_UNINSTALL =
            "#!/system/bin/sh\n"
            + "LOG=/data/adb/cubclone-sdcardfs-fix.log\n"
            + "echo \"$(date '+%F %T') [uninstall] 模块已卸载；"
            + "external_storage.sdcardfs.enabled 只改内存，重启后自动恢复原值\" >> \"$LOG\"\n"
            + "# 清迁移标记：重新刷入时会再走一遍（幂等，已迁的不会再动）\n"
            + "rm -f /data/adb/cubclone-sdcardfs-fix.migrated /data/adb/cubclone-sdcardfs-fix.migrated2 /data/adb/cubclone-sdcardfs-fix.migrated3\n";

    /** 安装阶段把特征检查做完：不符条件直接 abort，模块不落地 */
    private static final String MODULE_CUSTOMIZE =
            "# 刷入前检查：本机不符合条件就直接拒绝安装，不在设备上留下任何东西\n"
            + "if ! command -v ui_print >/dev/null 2>&1; then ui_print() { echo \"$1\"; }; fi\n"
            + "if ! command -v abort >/dev/null 2>&1; then abort() { echo \"! $1\"; exit 1; }; fi\n"
            + "\n"
            + "ui_print \"- CubClone 分身共享存储：检查本机特征\"\n"
            + "if ! grep -q sdcardfs /proc/filesystems 2>/dev/null; then\n"
            + "  abort \"! 本机内核没有 sdcardfs 驱动，分身存储不走这条路径，不需要此模块（未做任何改动）\"\n"
            + "fi\n"
            + "ui_print \"- 内核带 sdcardfs，继续安装\"\n"
            // 这个开关是 Android 11（SDK 30）才有的，更老的系统上装了也不会生效
            + "SDK=$(getprop ro.build.version.sdk 2>/dev/null)\n"
            + "case \"$SDK\" in ''|*[!0-9]*) SDK=0;; esac\n"
            + "if [ \"$SDK\" -gt 0 ] && [ \"$SDK\" -lt 30 ]; then\n"
            + "  abort \"! 本机 Android $SDK（低于 11）：external_storage.sdcardfs.enabled 从 Android 11 才有，装了不会生效（未做任何改动）\"\n"
            + "fi\n"
            + "if command -v set_perm >/dev/null 2>&1; then\n"
            + "  set_perm $MODPATH/post-fs-data.sh 0 0 0755\n"
            + "  set_perm $MODPATH/service.sh 0 0 0755\n"
            + "  set_perm $MODPATH/uninstall.sh 0 0 0755\n"
            + "else\n"
            + "  chmod 0755 $MODPATH/post-fs-data.sh $MODPATH/service.sh $MODPATH/uninstall.sh\n"
            + "fi\n"
            + "ui_print \"- 安装完成，重启后分身空间与主空间共用同一份存储\"\n";

    private static final String MODULE_README =
            "CubClone 分身共享存储模块\n"
            + "\n"
            + "它做什么：\n"
            + "  1. 把 external_storage.sdcardfs.enabled 报成 false，让 vold / MediaProvider 走纯 FUSE\n"
            + "     （和内核没有 sdcardfs 驱动的机型同一条分支）。内核带 sdcardfs 时，vold 给分身\n"
            + "     用户 bind Android/data 会被 FUSE 拒掉（EACCES），整卷回滚成 unmountable，\n"
            + "     分身里根本没有外部存储。关掉后分身卷正常挂上，且与主空间共用同一份文件。\n"
            + "  2. 把 sdcardfs 时代留下的文件属主迁成 FUSE 形态（顶层一次，内容每次开机复查）。\n"
            + "     纯 FUSE 下所有读写都由 MediaProvider 进程代理，它操作底层文件时受 POSIX 约束：\n"
            + "     属主是 media_rw(1023) 的文件它只是 other，改不动，于是 SQLite 报 4618\n"
            + "     SQLITE_IOERR_SHMOPEN，应用打不开自己的库（导出 0 字节、导入报错、界面一直加载中）。\n"
            + "     原生 FUSE 机型上这些文件属主本来就是 MediaProvider 自己，所以没这个问题。\n"
            + "     迁移规则（与原生 FUSE 机型一致，不按应用猜归属）：\n"
            + "       · Android/media/<包名> 顶层目录 -> 该包在这个空间里自己的 uid\n"
            + "       · 里面只改 sdcardfs 遗留的 media_rw(1023) -> 本机 MediaProvider 的 uid\n"
            + "       · 应用自己建的文件（包括属主是主空间 MediaProvider 的那种）和权限一律不动\n"
            + "       · Android/data、Android/obb 完全不动（它们是直挂，不走 FUSE）\n"
            + "\n"
            + "不做什么：不改文件内容、不搬动、不删除，只改属主。\n"
            + "\n"
            + "刷入时就会检查，不满足直接拒绝安装、不留任何东西：\n"
            + "  内核没有 sdcardfs 驱动 -> 拒绝：本机不需要\n"
            + "  Android 低于 11 -> 拒绝：模块依赖的 external_storage.sdcardfs.enabled 从 11 才有\n"
            + "\n"
            + "用法：在 APatch / KernelSU / Magisk 里刷入本 zip，重启手机即可。\n"
            + "  顶层目录只在升级后修一次（/data/adb/cubclone-sdcardfs-fix.migrated3 留标记）；\n"
            + "  残留的 media_rw(1023) 属主每次开机都复查一遍——备份恢复、脚本、别的模块都会把它带回来；\n"
            + "  执行记录在 /data/adb/cubclone-sdcardfs-fix.log，每条动作都会写进去。\n"
            + "\n"
            + "卸载：管理器里移除模块，重启即恢复 sdcardfs 分支。属性只改内存；\n"
            + "  已迁的属主不回滚（迁回 media_rw 反而会让应用重新打不开库，且属主本来就不影响\n"
            + "  sdcardfs 分支的读写）。\n"
            + "\n"
            + "注意：ROM OTA 升级后 Magisk/APatch 模块通常要重刷一次。\n";

    /**
     * 现场生成模块 zip 到公共 Download 目录（走 MediaStore，不需要存储权限）。
     * 返回给用户看的相对路径（Download/xxx.zip）；失败返回 null。
     */
    static String writeFixModule(Context c) {
        try {
            byte[] zip = buildFixModuleZip();
            if (zip == null) return null;
            // 文件名跟 1.x 一致：用户上一版就是这个包名，重新生成要原地覆盖，不能在 Download 里多出一份。
            // cubclone-shared-storage.zip 是 2.0 测试期用错的名字，一并清掉。
            String name = "cubclone-sdcardfs-fix.zip";
            android.net.Uri col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            for (String base : new String[]{"cubclone-sdcardfs-fix", "cubclone-shared-storage"}) {
                try {
                    c.getContentResolver().delete(col,
                            "(" + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?"
                            + " OR " + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?)"
                            + " AND " + android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                            new String[]{base + ".zip", base + " (%).zip",
                                    android.os.Environment.DIRECTORY_DOWNLOADS + "/"});
                } catch (Throwable ignored) {
                }
            }
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = c.getContentResolver().insert(col, v);
            if (uri == null) return null;
            java.io.OutputStream os = c.getContentResolver().openOutputStream(uri);
            if (os == null) return null;
            try {
                os.write(zip);
                os.flush();
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
            return android.os.Environment.DIRECTORY_DOWNLOADS + "/" + name;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] buildFixModuleZip() {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zos = null;
        try {
            zos = new java.util.zip.ZipOutputStream(bos);
            putEntry(zos, "module.prop", MODULE_PROP);
            putEntry(zos, "system.prop", MODULE_SYSPROP);
            putEntry(zos, "post-fs-data.sh", MODULE_PFD);
            putEntry(zos, "service.sh", MODULE_SVC);
            putEntry(zos, "uninstall.sh", MODULE_UNINSTALL);
            putEntry(zos, "customize.sh", MODULE_CUSTOMIZE);
            putEntry(zos, "README.txt", MODULE_README);
            zos.finish();
            zos.close();
            zos = null;
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (zos != null) try { zos.close(); } catch (Throwable ignored) { }
        }
    }

    private static void putEntry(java.util.zip.ZipOutputStream zos, String name, String body)
            throws java.io.IOException {
        zos.putNextEntry(new java.util.zip.ZipEntry(name));
        zos.write(body.getBytes("UTF-8"));
        zos.closeEntry();
    }




    /**
     * 插件是否已把输入法整条链路重定向到主用户。
     *
     * <p>三个 hook 各写一个诊断 prop，全部装成功才算可用：
     * 空间有哪些输入法（查主用户）、空间是否算前台 profile、绑定时是否换成主用户。
     * 任一缺失就走老路：把输入法包装进空间。没 root / 没装插件时恒为 false。
     */
    static boolean imeRedirectReady() {
        return hookOk("sys.cubclone.hook.ime.q")
                && hookOk("sys.cubclone.hook.ime.cp")
                && hookOk("sys.cubclone.hook.ime");
    }

    private static boolean hookOk(String key) {
        String v = Shell.raw("getprop", key).trim();
        return v.length() > 0 && !v.startsWith("no-method") && !v.startsWith("fail");
    }

    /** ensureIme 的结果：imes＝分身里真正可用的输入法个数，def＝写进去的默认输入法 */
    static final class ImeResult {
        final int imes;
        final String def;
        final String err;

        ImeResult(int imes, String def, String err) {
            this.imes = imes;
            this.def = def;
            this.err = err;
        }

        boolean ok() {
            return err == null;
        }
    }

    /**
     * 让分身有能用的输入法：把主空间在用的输入法装进分身，再把主空间的输入法设置抄过去。
     *
     * clone profile 是原生多用户，系统给每个用户各跑一个输入法实例 —— 所以这里能同步的
     * 只有**设置**（默认是谁、启用了哪些、上次用的子类型），词库/账号/皮肤这些数据
     * 各空间一份，不共享。想真正共享主空间那一个实例，只能改 system_server（LSPosed 或
     * ROM 补丁），普通应用包括 Shizuku 都做不到。
     *
     * 与旧版 syncIme 的区别：幂等（可反复调）、按分身**实际装着**的输入法过滤启用列表
     * （旧版直接抄，主空间有、分身没有的包会留下指向空包的僵尸项）、连子类型设置一起抄、
     * 写完回读校验、失败有原因。
     *
     * @return 结果；失败时 err 非空
     */
    static ImeResult ensureIme(int uid) {
        if (!Shell.ready()) return new ImeResult(0, "", "需要 root 或 Shizuku");
        if (!Launcher.safe(uid)) return new ImeResult(0, "", "uid " + uid);
        String q = String.valueOf((char) 34);
        // ① 主空间（user 0）当前的四项输入法设置，一次取回
        String a = Shell.exec("echo D:$(settings get --user 0 secure default_input_method); "
                + "echo E:$(settings get --user 0 secure enabled_input_methods); "
                + "echo S:$(settings get --user 0 secure selected_input_method_subtype); "
                + "echo H:$(settings get --user 0 secure input_methods_subtype_history)");
        if (a.trim().startsWith("ERR")) return new ImeResult(0, "", a.trim());
        String def = tag(a, "D:");
        String en = tag(a, "E:");
        String sub = tag(a, "S:");
        String hist = tag(a, "H:");
        // ①a 哪些输入法不带：卸载清单（用户明确要清掉的应用）+ 新空间输入法清单里取消勾选的
        Set<String> skipIme = new HashSet<String>();
        try {
            Set<String> ul = extraUninstall(appCtx);
            if (ul != null) skipIme.addAll(ul);
        } catch (Throwable ignored) {
        }
        try {
            skipIme.addAll(imeExclude(appCtx));
        } catch (Throwable ignored) {
        }
        List<String> want = new ArrayList<String>();
        if (!skipIme.contains(pkgOf(def))) addComp(want, def);
        if (!isNull(en)) {
            for (String s : en.split(":")) {
                if (!skipIme.contains(pkgOf(s))) addComp(want, s);
            }
        }
        // ①b 主空间**所有**输入法都带进分身（不只是当前启用的那几个）——
        //     用户想在新空间里随时切换任意输入法，不用先回主空间启用。
        //     同样跳过上面那两份清单里的包。
        String imeOut = Shell.exec(
                "cmd package query-services -a android.view.InputMethod --brief 2>/dev/null");
        for (String line : imeOut.split("\n")) {
            String t = line.trim();
            int sl = t.indexOf('/');
            // 只要 "包/类" 形状：排除 "Service #0:"、属性行（带空格或 =）
            if (sl <= 0 || t.indexOf(' ') >= 0 || t.indexOf('=') >= 0) continue;
            String p = pkgOf(t);
            if (p == null || skipIme.contains(p)) continue;
            addComp(want, t);
        }
        if (want.isEmpty()) return new ImeResult(0, "", "主空间没有可用输入法");
        // ② 装进分身并启用（install-existing 幂等），最后一次列出分身里真正可用的包
        //    插件可用时不装：它把「这个用户有哪些输入法」和「绑到哪个用户」都重定向到主用户，
        //    空间里一个输入法包都不需要有，跟系统自带分身一个效果。
        List<String> pkgs = new ArrayList<String>();
        for (String comp : want) {
            String p = pkgOf(comp);
            if (p == null || pkgs.contains(p)) continue;
            pkgs.add(p);
        }
        Set<String> avail = new HashSet<String>();
        if (imeRedirectReady()) {
            avail.addAll(pkgs);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String p : pkgs) {
                sb.append("pm install-existing --user ").append(uid).append(' ').append(p)
                        .append(" >/dev/null 2>&1; ")
                        .append("pm enable --user ").append(uid).append(' ').append(p)
                        .append(" >/dev/null 2>&1; ");
            }
            sb.append("echo A:$(pm list packages --user ").append(uid)
                    .append(" -e | sed 's/^package://' | tr '\\n' ' ')");
            for (String s : tag(Shell.exec(sb.toString()), "A:").trim().split("\\s+")) {
                if (s.length() > 0) avail.add(s);
            }
        }
        // ③ 启用列表只留分身里真有的组件（顺序照主空间，重复去掉）
        StringBuilder enOut = new StringBuilder();
        List<String> keep = new ArrayList<String>();
        for (String comp : want) {
            String p = pkgOf(comp);
            if (p == null || !avail.contains(p) || keep.contains(comp)) continue;
            keep.add(comp);
            if (enOut.length() > 0) enOut.append(':');
            enOut.append(comp);
        }
        if (keep.isEmpty()) return new ImeResult(0, "", "分身里装不上输入法");
        // 默认项必须也在启用列表里，否则系统会说「没有可用输入法」直接回落到英文键盘
        String useDef;
        if (!isNull(def) && avail.contains(pkgOf(def))) {
            useDef = def;
            if (!keep.contains(def)) {
                if (enOut.length() > 0) enOut.append(':');
                enOut.append(def);
                keep.add(def);
            }
        } else {
            useDef = keep.get(0);
        }
        if (!isNull(en) && en.endsWith(":")) enOut.append(':');   // 老版本设置带尾冒号，原样保留
        // ④ 子类型历史同样只留分身里有的包（格式 "包/类;子类型id"，冒号分隔）
        StringBuilder hOut = new StringBuilder();
        if (!isNull(hist)) {
            for (String e : hist.split(":")) {
                if (e.trim().length() == 0) continue;
                int i = e.indexOf(';');
                String p = pkgOf(i > 0 ? e.substring(0, i) : e);
                if (p == null || !avail.contains(p)) continue;
                if (hOut.length() > 0) hOut.append(':');
                hOut.append(e);
            }
        }
        // ⑤ 写入并回读校验
        StringBuilder w = new StringBuilder();
        w.append("settings put --user ").append(uid).append(" secure enabled_input_methods ")
                .append(q).append(enOut).append(q).append("; ");
        w.append("settings put --user ").append(uid).append(" secure default_input_method ")
                .append(q).append(useDef).append(q).append("; ");
        if (!isNull(sub)) {
            w.append("settings put --user ").append(uid)
                    .append(" secure selected_input_method_subtype ").append(sub).append("; ");
        }
        if (hOut.length() > 0) {
            w.append("settings put --user ").append(uid)
                    .append(" secure input_methods_subtype_history ").append(q).append(hOut)
                    .append(q).append("; ");
        }
        w.append("echo D:$(settings get --user ").append(uid).append(" secure default_input_method)");
        String got = tag(Shell.exec(w.toString()), "D:");
        if (!useDef.equals(got)) return new ImeResult(keep.size(), useDef, "设置没写进去");
        return new ImeResult(keep.size(), useDef, null);
    }

    /** 取 "前缀:值" 形式回显里的某个值（没这行返回 ""） */
    private static String tag(String out, String prefix) {
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith(prefix)) return t.substring(prefix.length()).trim();
        }
        return "";
    }

    private static boolean isNull(String s) {
        return s == null || s.length() == 0 || s.equals("null");
    }

    /**
     * 一次空间扫描的结果。设置页和独立的空间管理页共用同一份，
     * 免得两边各解析一遍批处理输出、字段口径漂移。
     */
    static final class SpaceScan {
        final List<Integer> uids = new ArrayList<Integer>();
        final List<Set<String>> counts = new ArrayList<Set<String>>();
        final List<Set<String>> live = new ArrayList<Set<String>>();
        final List<Boolean> foreign = new ArrayList<Boolean>();
        int perParent = -1;
        int maxAll = -1;

        /** 我们自己建的空间数（不含系统自带） */
        int oursCount() {
            int n = 0;
            for (Boolean b : foreign) if (!b.booleanValue()) n++;
            return n;
        }

        /** 建号链上的实际有效上限；<=0 表示这机器两道闸都不限 */
        int cap() {
            return effectiveCap(perParent, maxAll);
        }
    }

    /**
     * 扫全机空间。会 start 我们自己建但从未启动过的分身（自愈），
     * 所以要跑在后台线程，别在 UI 线程调。
     */
    static SpaceScan scanSpaces(Context c) {
        SpaceScan r = new SpaceScan();
        // 归属标记必须先读：listBatch 要用它分 SPACE/FSPACE
        ensureMarks();
        // 刷新系统名缓存：改名后（系统名变了）不刷这里，界面就还是旧名
        listSpaceUids();
        // 刚建好、还在 prepareSpace 里的空间别在这里再 start：它会白等 spaceReady，
        // 让整次扫描卡十几秒（界面一直「读取中」）。
        for (Integer u : markedUids()) {
            if (isPending(u.intValue())) continue;
            startSpace(u.intValue());
        }

        String out = Shell.exec(listBatch(c)
                + "dumpsys user | sed -n '/android.os.usertype.profile.CLONE:/,/^$/p'");
        for (String line : out.split("\n")) {
            String ln = line.trim();
            if (ln.length() == 0) continue;
            if (ln.startsWith("SPACE ") || ln.startsWith("FSPACE ")) {
                try {
                    r.uids.add(Integer.valueOf(Integer.parseInt(ln.substring(6).trim())));
                    r.counts.add(new HashSet<String>());
                    r.live.add(new HashSet<String>());
                    r.foreign.add(Boolean.valueOf(ln.startsWith("FSPACE ")));
                } catch (Throwable ignored) {
                }
            } else if (ln.startsWith("PKG ")) {
                String[] pp = ln.split("\\s+");
                if (pp.length < 3) continue;
                if (pp[2].equals(c.getPackageName())) continue;
                int idx = indexOfUid(r.uids, pp[1]);
                if (idx < 0) continue;
                r.live.get(idx).add(pp[2]);
                // 计数＝enabled 的三方；系统复制的包建号时已被 disable，落在 OFF，不计
                if (THIRD.contains(pp[2])) r.counts.get(idx).add(pp[2]);
            } else if (ln.startsWith("OFF ") || ln.startsWith("EXT ")) {
                String[] pp = ln.split("\\s+");
                if (pp.length < 3) continue;
                int idx = indexOfUid(r.uids, pp[1]);
                if (idx >= 0) r.live.get(idx).add(pp[2]);
            } else if (ln.startsWith("mMaxAllowedPerParent:")) {
                r.perParent = num(ln);
            } else if (ln.startsWith("mMaxAllowed:")) {
                r.maxAll = num(ln);
            }
        }
        return r;
    }

    private static int indexOfUid(List<Integer> uids, String uid) {
        try {
            return uids.indexOf(Integer.valueOf(Integer.parseInt(uid)));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** CLI 调用前缀：jar 由主应用释放到 /data/local/tmp/cubclone.jar */
    static final String CLI =
            "CLASSPATH=/data/local/tmp/cubclone.jar app_process /system/bin CubClone";

    /**
     * 某空间里所有带桌面图标的<b>三方</b>应用包名。
     * 批量备份用它拼 EXTRA_ITEMS —— 系统应用（设置/计算器/收音机）不备，
     * 它们每个空间都自带一份，备了只是白占体积。
     */
    static List<String> launchablePkgs(Context c, int uid) {
        String sys = Shell.exec("pm list packages -s --user " + uid
                + " | sed 's/^package://' | sort -u");
        Set<String> system = new HashSet<String>();
        for (String p : sys.split("\n")) {
            String t = p.trim();
            if (t.length() > 0) system.add(t);
        }
        String out = Shell.exec("cmd package query-activities --user " + uid
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " | sed -n 's/.*packageName=\\([^ ]*\\).*/\\1/p' | sort -u");
        List<String> r = new ArrayList<String>();
        for (String p : out.split("\n")) {
            String t = p.trim();
            if (t.length() == 0) continue;
            if (c != null && t.equals(c.getPackageName())) continue;
            if (system.contains(t)) continue;
            if (isEssentialPkg(t)) continue;
            r.add(t);
        }
        return r;
    }

    /** 只收 "包/类" 形状的输入法组件，别把 settings 回显的杂项当组件 */
    private static void addComp(List<String> out, String comp) {
        if (comp == null) return;
        String t = comp.trim();
        int i = t.indexOf('/');
        if (i <= 0 || i == t.length() - 1) return;
        if (!out.contains(t)) out.add(t);
    }

    // ---------------- 新空间输入法清单 ----------------

    /**
     * 建新空间时**不带**进去的输入法（只存用户取消勾选的那些包名）。
     * 默认空集合 ＝ 全部带进去。清单是全局的，对所有新空间生效；
     * 已建好的空间点空间行的「输入法」按钮，也按这份清单重同步一次。
     */
    static Set<String> imeExclude(Context c) {
        try {
            Set<String> s = c.getSharedPreferences("clone", Context.MODE_PRIVATE)
                    .getStringSet("ime_exclude", null);
            return s == null ? new HashSet<String>() : new HashSet<String>(s);
        } catch (Throwable t) {
            return new HashSet<String>();
        }
    }

    static void setImeExclude(Context c, Set<String> s) {
        c.getSharedPreferences("clone", Context.MODE_PRIVATE)
                .edit().putStringSet("ime_exclude", new HashSet<String>(s)).apply();
    }

    /** 主空间所有输入法：{包名, 显示名}，按显示名排序 */
    static List<String[]> allImes(Context c) {
        List<String[]> out = new ArrayList<String[]>();
        if (c == null) return out;
        try {
            List<ResolveInfo> list = c.getPackageManager().queryIntentServices(
                    new Intent("android.view.InputMethod"), 0);
            Set<String> seen = new HashSet<String>();
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri == null || ri.serviceInfo == null) continue;
                    String p = ri.serviceInfo.packageName;
                    if (p == null || !seen.add(p)) continue;
                    CharSequence lab = null;
                    try {
                        lab = ri.serviceInfo.loadLabel(c.getPackageManager());
                    } catch (Throwable ignored) {
                    }
                    out.add(new String[]{p, lab == null ? p : lab.toString()});
                }
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                return a[1].compareToIgnoreCase(b[1]);
            }
        });
        return out;
    }

    /** 这个包是不是输入法（挂了 android.view.InputMethod 服务）—— 决定装完要不要同步输入法设置 */
    static boolean isIme(String pkg) {
        if (pkg == null || pkg.length() == 0 || !Shell.ready()) return false;
        String out = Shell.exec(
                "cmd package query-services -a android.view.InputMethod --brief 2>/dev/null");
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith(pkg + "/")) return true;
        }
        return false;
    }

    private static String pkgOf(String comp) {
        if (comp == null) return null;
        int i = comp.indexOf('/');
        String p = (i > 0 ? comp.substring(0, i) : comp).trim();
        return p.length() == 0 ? null : p;
    }

    /** 我们建的分身空间；建号计数、自动复用、空间序号都只认这一份 */
    static java.util.List<Integer> listSpaceUids() {
        java.util.List<Integer> ids = listSpaceUids(false);
        syncHookSpaces(ids);
        return ids;
    }

    /** 上次报给增强插件的空间列表；没变就不重复发 shell */
    private static volatile String HOOK_SPACES = "";
    private static final java.util.concurrent.ExecutorService HOOK_SYNC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * 把「我们建的空间」报给增强插件（`persist.sys.cubclone.spaces`）。
     *
     * <p>插件 hook 的是 {@code WindowManagerService.isCurrentProfile(int)}：AOSP 判断
     * 「某个用户的窗口要不要显示给当前用户」只有这一道闸（{@code showToCurrentUser()}）。
     * 8A 上空间是 {@code profile.CLONE}（user 0 的子 profile），天然为真；G7 的 Flyme
     * 不支持 clone profile，建出来是 {@code full.SECONDARY}，不在 {@code mCurrentProfileIds}
     * 里，于是 task 建出来也是 {@code visible=false} —— 这就是「启动了但不显示」（G7 实测）。
     *
     * <p>用 persist 属性而不是写死：空间增删后下一次判断就生效，不用重启。
     * 发命令丢到独立线程，别让这条 shell 拖慢调用方（有些调用点在 UI 线程上）。
     */
    private static void syncHookSpaces(java.util.List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id.intValue());
        }
        final String csv = sb.toString();
        if (csv.equals(HOOK_SPACES)) return;
        HOOK_SPACES = csv;
        HOOK_SYNC.execute(() -> Shell.exec("setprop persist.sys.cubclone.spaces '" + csv + "'"));
    }

    /**
     * 「可选目标空间」：我们建的空间；开了「管理非自建空间」时把 ROM 自带的分身空间也带上。
     * 只给「装到哪个空间 / 备份哪个空间 / 恢复到哪个空间」这类目标选择用 ——
     * 名额计算、自动复用、空间序号不能跟着走，否则会把别人的空间算成自己的。
     */
    static java.util.List<Integer> pickableSpaceUids(Context c) {
        if (!manageForeign(c)) return listSpaceUids();
        ensureMarks();
        return listSpaceUids(true);
    }

    /** 「管理非自建空间」开关（默认关：非自建空间只读） */
    static boolean manageForeign(Context c) {
        Context cc = c != null ? c : appCtx;
        if (cc == null) return false;
        return cc.getSharedPreferences("clone", Context.MODE_PRIVATE)
                .getBoolean("manage_foreign", false);
    }

    /**
     * 分身空间 uid 列表。
     * `cmd user list --all -v` 约 0.2s；以前用 app_process 起 JVM 要 2.1s，每次刷新都白等。
     *
     * @param includeForeign 是否带上非自建空间（ROM 自带 MultiApp 等）；普通用户
     *                       （full.SECONDARY）只有带我们的归属标记才算，任何模式都不放进来
     */
    private static java.util.List<Integer> listSpaceUids(boolean includeForeign) {
        java.util.List<Integer> ids = new ArrayList<Integer>();
        Map<Integer, String> names = new HashMap<Integer, String>();
        Map<Integer, Boolean> types = new HashMap<Integer, Boolean>();
        Set<Integer> ghosts = new HashSet<Integer>();
        // 顺带取一次「卡在 <removing> 的幽灵空间」：这类档案 pm remove-user 早返回 Success 了，
        // 但状态停在 STOPPING，`am stop-user` 也会被拒（-4 related users can't be stopped），
        // 只有重启能清 —— 界面上不能再把它当成活着的空间。
        String out = Shell.exec("cmd user list --all -v 2>/dev/null; echo '#RM#'; "
                + "dumpsys user 2>/dev/null | grep '<removing>'");
        boolean rm = false;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#RM#")) {
                rm = true;
                continue;
            }
            if (rm) {
                int a = t.indexOf('{'), b = a < 0 ? -1 : t.indexOf(':', a);
                if (a >= 0 && b > a) {
                    try {
                        ghosts.add(Integer.valueOf(t.substring(a + 1, b)));
                    } catch (Throwable ignored) {
                    }
                }
                continue;
            }
            if (!isSpaceType(t)) continue;
            int i = t.indexOf("id=");
            if (i < 0) continue;
            int j = i + 3, k = j;
            while (k < t.length() && Character.isDigit(t.charAt(k))) k++;
            try {
                int uid = Integer.parseInt(t.substring(j, k));
                int n = t.indexOf("name=");
                int e = n < 0 ? -1 : t.indexOf(',', n);
                String nm = e > n ? t.substring(n + 5, e).trim() : "";
                if (!Launcher.safe(uid)) continue;
                // 名字无条件记下来：isOurs(null, uid) 的调用方（删分身、回收空间）
                // 要靠它认出「名字带前缀」的空间，不然会把我们自己的空间判成只读
                if (nm.length() > 0) names.put(Integer.valueOf(uid), nm);
                // 类型也一起记：空间管理页要标「克隆型 / 独立型」，
                // 两者的系统行为不同（前者天然同屏可见，后者要靠插件补）
                final boolean cloneType = t.contains("type=profile.CLONE");
                types.put(Integer.valueOf(uid), Boolean.valueOf(cloneType));
                final boolean mine = isOurs(nm, uid);
                if (mine) {
                    ids.add(Integer.valueOf(uid));
                } else if (includeForeign && cloneType) {
                    // 非自建分身：只有显式要「可选目标空间」时才列；
                    // 名字已记进 SPACE_NAMES，界面直接显示系统名，不用再起一次 shell 查
                    ids.add(Integer.valueOf(uid));
                }
            } catch (Throwable ignored) {
            }
        }
        ids.removeAll(ghosts);
        SPACE_NAMES = names;
        SPACE_TYPES = types;
        return ids;
    }

    /** 所有分身空间（含非自建）：只给「空间还活着吗」这类判断用，不能拿来算名额 */
    static java.util.List<Integer> allSpaceUids() {
        ensureMarks();
        return listSpaceUids(true);
    }

    /**
     * 找一个可以装下该应用的分身空间。
     * 规则：空间里不能已经有这个应用；优先挑应用最少的那个。
     * 找不到返回 -1。
     */
    static int pickReusableSpace(Context c, String pkg) {
        java.util.List<Integer> l = reusableSpaces(c, pkg);
        return l.isEmpty() ? -1 : l.get(0).intValue();
    }

    /**
     * 能装这个应用的空间（我们自己建的、且还没有它），按 uid 升序 —— 老空间先用。
     * 以前按「已装分身数」升序排，结果每次都塞进最新那个空空间，看起来像「总建到最后一个」。
     * ⚠判断「空间里已经有它」只能用登记表：新建空间时系统会把主身份的全部包灌进来
     * （红米 180 个、一加 378 个），按包列表算的话每个空间都「已经有它」，永远不肯复用。
     */
    static java.util.List<Integer> reusableSpaces(Context c, String pkg) {
        java.util.List<Integer> out = new ArrayList<Integer>();
        for (int uid : listSpaceUids()) {
            Set<String> kept = keptPkgs(c, uid);
            if (kept != null && kept.contains(pkg)) continue;
            out.add(Integer.valueOf(uid));
        }
        Collections.sort(out);
        return out;
    }
}
