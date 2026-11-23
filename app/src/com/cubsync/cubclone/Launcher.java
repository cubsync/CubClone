package com.cubsync.cubclone;

import android.content.Context;

/**
 * 分身内应用操作：安装进分身 / 从分身移除 / 启动。
 *
 * ⚠ 安全铁律：所有接受 uid 的操作都必须先过 {@link #safe(int)}。
 * 一旦把 uid 0（机主）当成替身传进来，卸载/删除的就是主身份的数据。
 * 本类永不执行 rm -rf 之类的文件删除，只用 PackageManager/UserManager 的定向接口。
 */
public final class Launcher {

    /** 分身的 uid 下限：AOSP 给 clone profile 分配的都在 10 以上 */
    private static final int MIN_CLONE_UID = 10;

    private Launcher() {}

    /** uid 是否可以被当作分身处理 —— 唯一的守门函数 */
    public static boolean safe(int uid) {
        return uid >= MIN_CLONE_UID;
    }

    /** 被分身用户里启动一个应用 */
    public static boolean launch(Context ctx, int uid, String pkg) {
        if (!safe(uid) || !Shell.ready()) return false;
        Shell.exec("am start-user " + uid);

        String comp = component(uid, pkg);
        String out;
        if (comp != null) {
            out = Shell.exec("am start --user " + uid + " -n " + comp);
        } else {
            out = Shell.exec("monkey -p " + pkg
                    + " -c android.intent.category.LAUNCHER 1 --user " + uid);
        }
        return !out.contains("Error") && !out.contains("Exception");
    }

    /**
     * 按原样把应用拉起来（备份完把刚才停掉的应用放回去用）。
     * 不能走 launch()：那个有 safe(uid) 门槛（uid &lt; 10 直接拒），主空间恰好是 0。
     */
    public static boolean relaunch(int user, String pkg) {
        if (!Shell.ready()) return false;
        String comp = component(user, pkg);
        String out;
        if (comp != null) {
            out = Shell.exec("am start --user " + user + " -n " + comp);
        } else {
            out = Shell.exec("monkey -p " + pkg
                    + " -c android.intent.category.LAUNCHER 1 --user " + user);
        }
        return !out.contains("Error") && !out.contains("Exception");
    }

    /** 拿到该用户在指定包下的启动组件；失败返回 null */
    private static String component(int uid, String pkg) {
        String out = Shell.exec("cmd package resolve-activity --brief --user " + uid + " " + pkg);
        String last = null;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (s.contains("/") && !s.startsWith("priority") && !s.startsWith("No activity")) {
                last = s;
            }
        }
        return last;
    }

    /** 把主用户已有的应用装进分身（不是重新下载，是点亮安装状态） */
    public static String installExisting(int uid, String pkg) {
        if (!safe(uid)) return "REFUSED uid<" + MIN_CLONE_UID;
        // 之前被「隐藏」过的（pm disable-user）要顺手 enable，否则装回来也看不见
        Shell.exec("pm enable --user " + uid + " " + pkg + " >/dev/null 2>&1");
        return Shell.exec("pm install-existing --user " + uid + " " + pkg);
    }

    /**
     * 在分身里「隐藏」一个应用：`pm disable-user`，图标消失、跑不起来，但**不删数据**，
     * `pm enable` 一键还原。这是 shell 身份能做到的、最接近 Island 用
     * `setApplicationHidden` 的效果（`pm hide` 要 MANAGE_USERS，shell 没有）。
     */
    public static String disable(int uid, String pkg) {
        if (!safe(uid)) return "REFUSED uid<" + MIN_CLONE_UID;
        return Shell.exec("pm disable-user --user " + uid + " " + pkg);
    }

    /** 取消隐藏 */
    public static String enable(int uid, String pkg) {
        if (!safe(uid)) return "REFUSED uid<" + MIN_CLONE_UID;
        return Shell.exec("pm enable --user " + uid + " " + pkg);
    }

    /** 从分身里卸载该应用（主用户那份不受影响） */
    public static String uninstall(int uid, String pkg) {
        if (!safe(uid)) return "REFUSED uid<" + MIN_CLONE_UID;
        return Shell.exec("pm uninstall --user " + uid + " " + pkg);
    }

    /** 列出某个用户下已安装的包名 */
    public static java.util.Set<String> packagesOf(int uid) {
        java.util.Set<String> set = new java.util.HashSet<String>();
        if (!safe(uid)) return set;
        String out = Shell.exec("pm list packages -e --user " + uid);
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (s.startsWith("package:")) set.add(s.substring(8).trim());
        }
        return set;
    }

    /** 防止分身里也出现一份本 App，导致启动时弹"用哪个应用打开"选择器 */
    public static void hideSelfFrom(int uid) {
        if (!safe(uid)) return;
        Shell.exec("pm uninstall --user " + uid + " com.cubsync.cubclone");
    }
}
