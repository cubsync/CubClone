package com.cubsync.cubclone;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 需要 root 的功能的统一开关。
 *
 * 两个目的：
 *   1. 只有 Shizuku、没 root 的设备上，这些功能的入口直接不出现——
 *      点进去必然失败，不如不给；
 *   2. 拿得到 root 也能单独关掉某一项（比如只用克隆数据、不想让人动备份）。
 *
 * 总开关关掉后所有 root 功能一起消失；单项开关只在总开关打开时生效。
 */
public final class Root {

    public static final String CLONE = "clone";
    public static final String BACKUP = "backup";

    /** 最近一次探测结果；null 表示这次进程里还没探过，退回 Shell 的缓存值 */
    private static volatile Boolean last;

    private Root() {}

    public static void setAvailable(boolean ok) {
        last = Boolean.valueOf(ok);
    }

    public static boolean available() {
        Boolean v = last;
        if (v != null) return v.booleanValue();
        return Shell.rootOk;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("clone", Context.MODE_PRIVATE);
    }

    public static boolean master(Context c) {
        return sp(c).getBoolean("root_master", true);
    }

    public static boolean feature(Context c, String f) {
        return sp(c).getBoolean("root_feat_" + f, true);
    }

    /** 某个功能的入口该不该出现：总开关 + 单项开关 + 当前真的拿得到 root */
    public static boolean on(Context c, String f) {
        return master(c) && feature(c, f) && available();
    }
}
