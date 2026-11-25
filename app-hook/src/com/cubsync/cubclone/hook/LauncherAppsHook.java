package com.cubsync.cubclone.hook;

import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 桌面（launcher）的「应用变化」回调：我们建的空间一律不通知。
 *
 * <p>现象（OP12 实测，2026-11-25 23:30）：建空间时桌面第二页 Agent2API 之后多出一串图标，
 * 全不是机主自己装的；{@code launcher.db} 的 {@code singledesktopitems_draw} 里有
 * {@code profileId=1019, user_id=13} 这一行（1019 是空间 13 的 serialNo），
 * {@code app_icons.db} 也按 {@code profileId=serialNo} 存了每个分身用户的应用图标。
 *
 * <p>为什么单靠 {@link LauncherProfileHook} 不够：那条只拦住「launcher 主动拉 profile 列表」。
 * 装包时 {@code LauncherAppsService} 还会把 {@code PACKAGE_ADDED} 逐个 listener 分发；
 * 分发前的门槛是 {@code isEnabledProfileOf(cookie.user, user, ...)}——clone 是 user 0 的
 * profile，这里恒为真，于是 launcher 直接按广播里的 user 加图标，不再查列表。
 * 我们建空间时会给空间装输入法，正好触发这一条（OP12 上多出来的就是 QQ 输入法）。
 *
 * <p>做法：hook {@code LauncherAppsService$LauncherAppsImpl.isEnabledProfileOf}，
 * 被判定的 user 是我们的空间时返回 false。这一处覆盖 added/removed/changed/available/
 * suspended 全部回调，且是 AOSP 的公共路径，ColorOS / Flyme / AOSP Launcher3 通用，
 * 不需要按 ROM 适配。系统自己的分身（Flyme 999、ColorOS MultiApp/system_clone）不在我们的
 * 列表里，行为不变。
 */
final class LauncherAppsHook {

    private static final String TAG = "CubCloneHook";
    private static final String IMPL =
            "com.android.server.pm.LauncherAppsService$LauncherAppsImpl";

    static final String PROP_HITS = "sys.cubclone.hook.la.hits";
    static final String PROP_LAST = "sys.cubclone.hook.la";

    private static final AtomicInteger HITS = new AtomicInteger();

    private final XposedInterface xp;

    LauncherAppsHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        Class<?> impl;
        try {
            impl = Class.forName(IMPL, false, cl);
        } catch (Throwable t) {
            Log.w(TAG, "la: no " + IMPL);
            return 0;
        }
        int n = 0;
        for (Method m : impl.getDeclaredMethods()) {
            if (!"isEnabledProfileOf".equals(m.getName())) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            // 两个重载：(user, profile, debugMsg) 与 (user, profile)
            if (pt.length < 2 || pt[1] != UserHandle.class) {
                continue;
            }
            try {
                m.setAccessible(true);
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            // 本来就不让看的（非本组 profile）直接放行
                            if (!Boolean.TRUE.equals(r)) {
                                return r;
                            }
                            Object prof = chain.getArg(1);
                            if (!(prof instanceof UserHandle)) {
                                return r;
                            }
                            int uid = userIdOf(prof);
                            if (uid <= 0) {
                                return r;
                            }
                            android.content.Context ctx = null;
                            try {
                                ctx = WmVisibleHook.ctxOf(chain.getThisObject());
                            } catch (Throwable ignored) {
                            }
                            if (!WmVisibleHook.ours(uid, ctx)) {
                                return r;
                            }
                            int k = HITS.incrementAndGet();
                            HookMain.mark(PROP_HITS, String.valueOf(k));
                            HookMain.mark(PROP_LAST, "hide " + uid);
                            if (k == 1) {
                                Log.i(TAG, "la: hide package callbacks for uid " + uid);
                            }
                            return Boolean.FALSE;
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "la hook failed: " + m + " " + t);
            }
        }
        Log.i(TAG, "la: hooks=" + n);
        return n;
    }

    /** 编译桩里的 UserHandle 没有 getIdentifier，只能反射（system_server 不受 hidden API 限制） */
    private static int userIdOf(Object handle) {
        try {
            Method m = UserHandle.class.getMethod("getIdentifier");
            return ((Integer) m.invoke(handle)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }
}
