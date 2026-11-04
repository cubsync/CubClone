package com.cubsync.cubclone;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.provider.Settings;

import java.util.Locale;

/**
 * 应用内语言：自己包一层 Context，不依赖系统的 per-app language。
 * 注意：老版本用过 LocaleManager.setApplicationLocales，那个覆盖会存在系统里、
 * 重装也不清，导致「跟随系统」永远是上次选的语言（系统中文却出英文）——
 * 所以这里先把系统级覆盖抹掉，跟随系统时按真实系统语言解析。
 */
public final class Locales {

    private Locales() {}

    /** 当前语言开关：null 表示跟随系统 */
    public static String tag(Context ctx) {
        String t = ctx.getSharedPreferences("clone", Context.MODE_PRIVATE)
                .getString("lang", null);
        return (t == null || t.isEmpty()) ? null : t;
    }

    private static boolean cleared;

    /** 抹掉残留在系统里的 per-app 语言覆盖（每进程只做一次） */
    private static void clearSystemOverride(Context ctx) {
        if (cleared) return;
        cleared = true;
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            android.app.LocaleManager lm = (android.app.LocaleManager)
                    ctx.getSystemService(Context.LOCALE_SERVICE);
            if (lm == null) return;
            if (lm.getApplicationLocales().isEmpty()) return;
            lm.setApplicationLocales(LocaleList.getEmptyLocaleList());
        } catch (Throwable ignored) {
        }
    }

    public static Context wrap(Context base) {
        clearSystemOverride(base);
        String tag = tag(base);
        if (tag == null) {
            // 跟随系统：不手动设 locale，直接用系统默认（per-app 覆盖已在上面抹掉）。
            // 手动 setLocales 反而会因 system_locales 解析差异导致「系统中文却出英文」。
            return base;
        }
        Configuration c = new Configuration(base.getResources().getConfiguration());
        Locale loc = tag.startsWith("en") ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        c.setLocale(loc);
        c.setLayoutDirection(loc);
        return base.createConfigurationContext(c);
    }
}
