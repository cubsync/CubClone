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

    static void attach(Context c) {
        if (appCtx == null && c != null) appCtx = c.getApplicationContext();
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

    /** 主界面数据变更标志：建/删/装/精简空间后置 true，onResume 重拉 */
    static volatile boolean dirty = true;
    /** 应用元信息（图标/名字）变更标志 */
    static volatile boolean metaDirty = false;

    /** 分身空间名字缓存：uid → 系统里的名字（`cmd user list --all -v` 的 name= 字段） */
    static volatile Map<Integer, String> SPACE_NAMES = new HashMap<Integer, String>();
    /** 我们建的空间按扫描顺序（只用于显示「空间 1/2/3」，跟系统 id 无关） */
    static volatile java.util.List<Integer> SPACE_ORDER = new ArrayList<Integer>();

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

    /** 只在这个进程里读一次；两个入口 Activity 都靠它，别各自读各自的 */
    static void ensureMarks() {
        if (!marksLoaded) {
            marksLoaded = true;
            loadMarks();
        }
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
        // 立刻把它排进序号表，否则新建的空间要等下一次扫描才会显示成「空间 N」，
        // 中间这段会退回到系统名/id（用户看到的就是「又变成空间 20」）。
        java.util.List<Integer> ord = new ArrayList<Integer>(SPACE_ORDER);
        if (!ord.contains(Integer.valueOf(uid))) {
            ord.add(Integer.valueOf(uid));
            SPACE_ORDER = ord;
        }
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
    private static final String SCAN =
            "RM=\" $(dumpsys user 2>/dev/null"
            + " | sed -n 's/.*UserInfo{\\([0-9]*\\):.*<removing>.*/\\1/p' | tr '\\n' ' ') \"; "
            + "cmd user list --all -v 2>/dev/null"
            + " | grep -E 'type=(profile[.]CLONE|full[.]SECONDARY)' | while read -r ln; do "
            + "u=$(echo \"$ln\" | sed -n 's/^[0-9]*: id=\\([0-9]*\\),.*/\\1/p'); "
            + "case \"$RM\" in *\" $u \"*) continue;; esac; "
            // 普通用户（full.SECONDARY）只有带我们归属标记的才算空间：
            // 系统里自己建的「访客/第二用户」不能被当成分身显示
            + "case \"$ln\" in *type=profile[.]CLONE*) ;; "
            + "*) case \" $OURS \" in *\" $u \"*) ;; *) continue;; esac;; esac; "
            + "case \" $OURS \" in *\" $u \"*) echo \"SPACE $u\"; t=ICO;; "
            + "*) echo \"FSPACE $u\"; t=EXT;; esac; "
            + "pm list packages -e --user $u | sed \"s/^package:/PKG $u /\"; "
            + "pm list packages -d --user $u | sed \"s/^package:/OFF $u /\"; "
            + "cmd package query-activities --user $u"
            + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            + " | sed -n \"s/.*packageName=\\([^ ]*\\).*/$t $u \\1/p\" | sort -u; done; ";

    /** 拼上「我们建的空间」清单再跑 SCAN */
    static String listBatch(Context c) {
        attach(c);
        StringBuilder sb = new StringBuilder("OURS=\" ");
        for (Integer u : markedUids()) sb.append(u).append(' ');
        sb.append("\"; ");
        return sb.toString() + SCAN;
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
        if (appCtx == null) return new HashSet<String>();
        Set<String> s = appCtx.getSharedPreferences("clone", Context.MODE_PRIVATE)
                .getStringSet("hidden_clones", null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
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
    }

    static void unhideClone(Context c, int uid, String pkg) {
        SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
        Set<String> old = sp.getStringSet("hidden_clones", null);
        if (old == null) return;
        Set<String> s = new HashSet<String>(old);
        if (!s.remove(uid + ":" + pkg)) return;
        sp.edit().putStringSet("hidden_clones", s).apply();
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
        StringBuilder ul = new StringBuilder(" ");   // 卸载清单：直接卸载（优先级最高）
        for (String p : extraUninstall(c)) ul.append(p).append(' ');
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
        // 兜底（系统名是 space / 读不到）：按建号顺序显示「空间 N」
        int ord = SPACE_ORDER.indexOf(Integer.valueOf(uid));
        if (ord >= 0) return c.getString(R.string.space_name, String.valueOf(ord + 1));
        return c.getString(R.string.space_name, String.valueOf(uid));
    }

    /** 这个 uid 的「空间序号」（1 起，对应「空间 N」）；不在我们建的空间里（foreign）返回 0 */
    static int spaceIndex(int uid) {
        int i = SPACE_ORDER.indexOf(Integer.valueOf(uid));
        return i < 0 ? 0 : i + 1;
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
    static final int MIGRATE_LOCKED = -2;

    private static volatile boolean migratedOnce = false;

    /**
     * 把 /data/media/0/Android/media/&lt;包&gt; 从 sdcardfs 形态（media_rw:扩展组 2775）
     * 迁成 FUSE 形态（&lt;包uid&gt;:media_rw）。
     *
     * 这件事必须在**用户解锁之后**做，而模块脚本只有开机一个时机（脚本里等多久都等不到，
     * 日志实测是 skip）。所以放到应用启动这个「解锁后」的事件里，幂等、每次启动最多跑一次。
     *
     * 冷启动时本进程可能还没探过 root（rootOk 默认 false），这里自己补探一次，
     * 否则会在 Shell.ready() 处直接返回 -1、迁移被静默跳过（2026-11-20 实测）。
     *
     * @return 改动数；锁屏未解锁返回 {@link #MIGRATE_LOCKED}；不适用/没权限返回 -1
     */
    static int migrateMediaOwners() {
        if (migratedOnce) return 0;
        if (!Shell.ready()) Shell.checkRoot();
        if (!Shell.ready()) {
            android.util.Log.i("CubClone", "migrate: no root/shizuku");
            return -1;
        }
        String probe = Shell.exec("if [ -d /data/media/0/Android/media ]"
                + " && [ -f /data/system/packages.list ]; then echo READY; else echo LOCKED; fi");
        android.util.Log.i("CubClone", "migrate: probe=" + probe.trim());
        if (probe.indexOf("LOCKED") >= 0) return MIGRATE_LOCKED;   // 锁屏期共享存储还没挂
        Integer st = storageState();
        android.util.Log.i("CubClone", "migrate: storageState=" + st);
        if (st == null || st.intValue() != 2) return -1;   // 只在本机走纯 FUSE 时才有意义
        migratedOnce = true;
        String script =
                "B=/data/media/0/Android/media\n"
                + "P=/data/system/packages.list\n"
                + "L=/data/adb/cubclone-sdcardfs-fix.log\n"
                + "if [ ! -d \"$B\" ] || [ ! -f \"$P\" ]; then echo N=-1; exit 0; fi\n"
                + "n=0\n"
                + "for d in \"$B\"/*; do\n"
                + "  [ -d \"$d\" ] || continue\n"
                + "  pkg=${d##*/}\n"
                + "  u=$(awk -v p=\"$pkg\" '$1==p{print $2; exit}' \"$P\" 2>/dev/null)\n"
                + "  [ -n \"$u\" ] || continue\n"
                + "  c=$(stat -c %u \"$d\" 2>/dev/null)\n"
                + "  [ \"$c\" = \"$u\" ] && continue\n"
                + "  if chown -R \"$u:media_rw\" \"$d\" 2>/dev/null; then\n"
                + "    n=$((n+1))\n"
                + "    echo \"$(date '+%F %T') [app] chown $pkg $c -> $u\" >> \"$L\" 2>/dev/null\n"
                + "  fi\n"
                + "done\n"
                + "echo N=$n\n";
        String b64 = android.util.Base64.encodeToString(
                script.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        String out = Shell.exec("echo " + b64 + " | base64 -d | sh");
        int i = out.indexOf("N=");
        if (i < 0) return -1;
        try {
            int n = Integer.parseInt(out.substring(i + 2).trim().split("\\s+")[0]);
            if (n > 0) {
                Shell.exec("echo \"$(date '+%F %T') [app] migrate done, changed=" + n
                        + "\" >> /data/adb/cubclone-sdcardfs-fix.log");
            }
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------- 「生成修复模块」 ----------------
    // 模块做两件事：
    // ① 让 vold / MediaProvider 不再走 sdcardfs 分支（纯 FUSE）。内核带 sdcardfs 时，
    //    vold 给 clone 用户 bind Android/data 会先 UnmountTree(target)，而 target 在 FUSE
    //    视图里被 MediaProvider 按「不该由我管」直接回 EACCES —— 整卷回滚成 unmountable。
    //    关掉后 clone 卷正常挂上，且与主空间共用同一份存储（相册是同一份文件）。
    // ② 把 /data/media/0/Android/media/<包> 从 sdcardfs 形态（media_rw:<扩展组> 2775）
    //    迁成 FUSE 形态（<包uid>:media_rw）。不迁的话切成 FUSE 后 root 和别的应用都写不进去
    //    （实测第三方支付、文件管理器应用写入失败）。
    // 判据只用「/proc/filesystems 里有 sdcardfs」：静态、BFU 也可见。
    // ⛔ 不能拿 mount 或卷状态当判据 —— 脚本跑在 post-fs-data，那时还没解锁，一条挂载都没有。

    // id 与 name 都是模块在用户设备上的身份，1.x 发出去之后就不再动：换 id 会变成两个模块并存，
    // 换 name 会让用户在管理器里看到模块「改名」。只有 version / versionCode 可以前进。
    private static final String MODULE_PROP =
            "id=cubclone_sdcardfs_fix\n"
            + "name=CubClone 分身存储修复\n"
            + "version=2.0\n"
            + "versionCode=3\n"
            + "author=cubsync\n"
            + "description=内核带 sdcardfs 的机型上，让 vold/MediaProvider 走纯 FUSE，"
            + "恢复分身空间与主空间共用的存储（相册、文件同一份）。安装后会把 Android/media "
            + "下 sdcardfs 时代建的目录属主迁移成 FUSE 形态，避免主空间跨应用写入失效。"
            + "仅适用于内核带 sdcardfs 的设备；不含该驱动的机型会被拒绝安装。\n";

    /** 管理器若在更早阶段应用 system.prop，这里也备一份，双保险（内容与脚本一致） */
    private static final String MODULE_SYSPROP =
            "external_storage.sdcardfs.enabled=false\n";

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
            + "BEFORE=$(\"$RP\" external_storage.sdcardfs.enabled 2>/dev/null)\n"
            + "\"$RP\" external_storage.sdcardfs.enabled false\n"
            + "AFTER=$(\"$RP\" external_storage.sdcardfs.enabled 2>/dev/null)\n"
            + "say \"external_storage.sdcardfs.enabled: [$BEFORE] -> [$AFTER]\"\n";

    // 属主迁移不放模块里：模块脚本只有「开机」一个时机，而 /data/media/0/Android/media
    // 要用户解锁后才出现 —— 实测开机后停在锁屏，脚本等多久都是白等（日志里是 skip）。
    // 「解锁后」真正的事件是用户打开本应用，所以改由 migrateMediaOwners() 在启动时补做。

    /** 卸载只记一行：属性只改内存，重启回原值；属主不回滚（迁回 sdcardfs 形态反而会坏） */
    private static final String MODULE_UNINSTALL =
            "#!/system/bin/sh\n"
            + "LOG=/data/adb/cubclone-sdcardfs-fix.log\n"
            + "echo \"$(date '+%F %T') [uninstall] 模块已卸载；"
            + "external_storage.sdcardfs.enabled 只改内存，重启后自动恢复原值\" >> \"$LOG\"\n";

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
            + "if command -v set_perm >/dev/null 2>&1; then\n"
            + "  set_perm $MODPATH/post-fs-data.sh 0 0 0755\n"
            + "  set_perm $MODPATH/uninstall.sh 0 0 0755\n"
            + "else\n"
            + "  chmod 0755 $MODPATH/post-fs-data.sh $MODPATH/uninstall.sh\n"
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
            + "  2. 另外，主空间 /data/media/0/Android/media/<包> 的属主要从 sdcardfs 形态\n"
            + "     （media_rw:<扩展组> 2775）迁成 FUSE 形态（<包uid>:media_rw），否则切成 FUSE 后\n"
            + "     root 和别的应用都写不进这些老目录。这一步不在模块里做：模块只有开机一个时机，\n"
            + "     那时设备还没解锁、目录还不存在。由 CubClone 应用在启动时自动补做。\n"
            + "\n"
            + "刷入时就会检查，不满足直接拒绝安装、不留任何东西：\n"
            + "  内核没有 sdcardfs 驱动 -> 拒绝：本机不需要\n"
            + "\n"
            + "用法：在 APatch / KernelSU / Magisk 里刷入本 zip，重启手机，解锁后打开一次 CubClone。\n"
            + "  模块执行记录在 /data/adb/cubclone-sdcardfs-fix.log，属主迁移的记录也追加在这里。\n"
            + "\n"
            + "卸载：管理器里移除模块，重启即恢复原状（属性只改内存，不落盘）。\n"
            + "  属主不会回滚：迁回 sdcardfs 形态在纯 FUSE 下反而写不进去。\n"
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
        StringBuilder sb = new StringBuilder();
        List<String> pkgs = new ArrayList<String>();
        for (String comp : want) {
            String p = pkgOf(comp);
            if (p == null || pkgs.contains(p)) continue;
            pkgs.add(p);
            sb.append("pm install-existing --user ").append(uid).append(' ').append(p)
                    .append(" >/dev/null 2>&1; ")
                    .append("pm enable --user ").append(uid).append(' ').append(p)
                    .append(" >/dev/null 2>&1; ");
        }
        sb.append("echo A:$(pm list packages --user ").append(uid)
                .append(" -e | sed 's/^package://' | tr '\\n' ' ')");
        Set<String> avail = new HashSet<String>();
        for (String s : tag(Shell.exec(sb.toString()), "A:").trim().split("\\s+")) {
            if (s.length() > 0) avail.add(s);
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

    /**
     * 分身空间 uid 列表。
     * `cmd user list --all -v` 约 0.2s；以前用 app_process 起 JVM 要 2.1s，每次刷新都白等。
     */
    static java.util.List<Integer> listSpaceUids() {
        java.util.List<Integer> ids = new ArrayList<Integer>();
        Map<Integer, String> names = new HashMap<Integer, String>();
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
                if (isOurs(nm, uid)) {
                    ids.add(Integer.valueOf(uid));
                }
            } catch (Throwable ignored) {
            }
        }
        ids.removeAll(ghosts);
        SPACE_ORDER = new ArrayList<Integer>(ids);
        SPACE_NAMES = names;
        return ids;
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
