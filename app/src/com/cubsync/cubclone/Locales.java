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

    /** 跟随系统时的实际语言：标准 Settings 键 system_locales 取首语言，通用不挑 ROM */
    private static Locale systemLocale(Context ctx) {
        try {
            String s = Settings.System.getString(ctx.getContentResolver(), "system_locales");
            if (s != null && !s.trim().isEmpty()) {
                return Locale.forLanguageTag(s.split(",")[0].trim());
            }
        } catch (Throwable ignored) {
        }
        LocaleList ll = LocaleList.getDefault();
        return ll.isEmpty() ? Locale.getDefault() : ll.get(0);
    }

    public static Context wrap(Context base) {
        clearSystemOverride(base);
        String tag = tag(base);
        Locale loc;
        if (tag == null) {
            // 跟随系统：必须显式 setLocale，不能裸 return base ——
            // 一加 ColorOS 实测不写 persist.sys.locale（三个 locale 属性全空），
            // 应用进程默认 locale 被框架 fallback 成 en，系统明明 zh-CN 却整页英文。
            // 改读标准 Settings 键 system_locales（各 ROM 都写且值正确）。
            loc = systemLocale(base);
        } else {
            loc = tag.startsWith("en") ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        }
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(loc);
        c.setLayoutDirection(loc);
        return base.createConfigurationContext(c);
    }
}
