package com.cubsync.cubclone;

import android.content.Context;

/**
 * shell 层只回错误码（ERR_xxx），中文/英文在界面层按当前语言取。
 * 错误码要能被 startsWith("ERR") 判到，别把前缀去掉。
 */
public final class Err {

    private Err() {}

    public static String text(Context c, String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("ERR ")) s = s.substring(4).trim();
        if (s.startsWith("ERR_NEED_ROOT")) return c.getString(R.string.err_need_root);
        if (s.startsWith("ERR_NO_PERM")) return c.getString(R.string.err_no_perm);
        if (s.startsWith("ERR_SHIZUKU_SVC")) return c.getString(R.string.err_no_shizuku_svc);
        if (s.startsWith("ERR_NEWPROC")) return c.getString(R.string.err_newproc_fail);
        if (s.startsWith("ERR_EXT_OWN")) return c.getString(R.string.err_ext_own);
        return s;
    }
}
