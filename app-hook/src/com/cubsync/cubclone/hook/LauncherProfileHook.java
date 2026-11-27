package com.cubsync.cubclone.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 让系统桌面（launcher）看不到我们建的空间里的应用。
 *
 * <p>现象（G7 实测，2026-11-25）：建一个 clone 空间并装进应用后，桌面自动多出一页——
 * 空间里的电话/设置/图库/CubDesk 全被摆上去了。根因是 {@code LauncherApps} 会把当前用户
 * 同组的 profile 一起拉进来。
 *
 * <h3>为什么要改拦截点（2026-11-27 回归）</h3>
 * 之前 hook 的是 {@code UserManagerService.getProfileIds()}，按「调用者是桌面」把我们的 uid
 * 从结果里剔掉。但 Flyme 的<b>最近任务也在桌面进程里</b>，它同样走这个方法拿 profile 列表，
 * 结果任务卡片上的图标和名称全没了（{@code lp=3->2}、{@code hits=4668} 为证）。同一个进程、
 * 同一个 API，靠调用者根本区分不出「拉桌面图标」和「画任务卡片」。
 *
 * <p>现在只拦 {@code LauncherAppsService.getActivityList(null, ...)}：包名为 {@code null}
 * 表示「把这个用户的应用全给我」，正是桌面铺图标那一次调用；任务管理器按包名查单个应用的
 * 标题/图标，包名非空，原样放行。{@code getProfileIds} 恢复系统原样，别的组件不再被误伤。
 */
final class LauncherProfileHook {

    private static final String TAG = "CubCloneHook";
    private static final String IMPL =
            "com.android.server.pm.LauncherAppsService$LauncherAppsImpl";

    static final String PROP_HITS = "sys.cubclone.hook.lp.hits";
    static final String PROP_LAST = "sys.cubclone.hook.lp";

    /** 默认 HOME 解析失败时兜底认这些包名 */
    private static final String[] KNOWN_LAUNCHERS = {
            "com.meizu.flyme.launcher",           // Flyme
            "com.oppo.launcher",                  // ColorOS
            "com.coloros.launcher",
            "com.android.launcher3",              // AOSP / LineageOS
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",                      // MIUI / HyperOS
            "com.huawei.android.launcher",
            "com.bbk.launcher2",
            "com.sec.android.app.launcher",
    };

    private static final AtomicInteger HITS = new AtomicInteger();
    /** -2 = 尚未解析；-1 = 解析失败；>0 = 桌面 uid */
    private static volatile int homeUid = -2;

    private final XposedInterface xp;

    LauncherProfileHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        Class<?> impl;
        try {
            impl = Class.forName(IMPL, false, cl);
        } catch (Throwable t) {
            Log.w(TAG, "lp: no " + IMPL);
            return 0;
        }
        int n = 0;
        for (Method m : impl.getDeclaredMethods()) {
            if (!"getActivityList".equals(m.getName())) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length < 3 || pt[0] != String.class) {
                continue;
            }
            int userArg = -1;
            for (int i = 1; i < pt.length; i++) {
                if (pt[i] == UserHandle.class) {
                    userArg = i;
                    break;
                }
            }
            if (userArg < 0) {
                continue;
            }
            final int ua = userArg;
            try {
                m.setAccessible(true);
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            // 按包名查单个应用（任务管理器取标题/图标）必须放行，
                            // 否则最近任务又会变成没有名字的空白卡片。
                            if (chain.getArg(0) != null) {
                                return chain.proceed();
                            }
                            if (!HookMain.propOn(LauncherAppsHook.PROP_ENABLE)) {
                                return chain.proceed();
                            }
                            Object r = chain.proceed();
                            Context ctx = null;
                            try {
                                ctx = WmVisibleHook.ctxOf(chain.getThisObject());
                            } catch (Throwable ignored) {
                            }
                            int uid = uidOf(chain.getArg(ua));
                            if (uid <= 0 || !WmVisibleHook.ours(uid, ctx)) {
                                return r;
                            }
                            if (!fromLauncher(ctx)) {
                                return r;
                            }
                            int k = HITS.incrementAndGet();
                            HookMain.mark(PROP_HITS, String.valueOf(k));
                            HookMain.mark(PROP_LAST, "empty uid=" + uid);
                            if (k == 1) {
                                Log.i(TAG, "lp: hide activity list of uid " + uid);
                            }
                            return emptyList(r);
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lp hook failed: " + m + " " + t);
            }
        }
        Log.i(TAG, "lp: hooks=" + n);
        return n;
    }

    /**
     * 把结果清空。返回类型各版本不同（{@code List} 或 {@code ParceledListSlice}），
     * 统一按「原来的对象、清掉里面的列表」处理，类型一定对得上；失败就原样返回（等于没拦，
     * 但不会崩）。
     */
    private static Object emptyList(Object r) {
        if (r == null) {
            return null;
        }
        try {
            if (r instanceof List) {
                ((List<?>) r).clear();
                return r;
            }
            Method get = r.getClass().getMethod("getList");
            Object list = get.invoke(r);
            if (list instanceof List) {
                ((List<?>) list).clear();
                return r;
            }
        } catch (Throwable t) {
            Log.w(TAG, "lp: clear list failed: " + t);
        }
        return r;
    }

    /** 当前 Binder 调用者是不是桌面。 */
    private static boolean fromLauncher(Context ctx) {
        int uid = Binder.getCallingUid();
        int home = homeUid(ctx);
        if (home > 0 && uid == home) {
            return true;
        }
        if (ctx == null) {
            return false;
        }
        try {
            String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) {
                return false;
            }
            for (String p : pkgs) {
                for (String k : KNOWN_LAUNCHERS) {
                    if (k.equals(p)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int homeUid(Context ctx) {
        int v = homeUid;
        if (v != -2) {
            return v;
        }
        if (ctx == null) {
            return -2;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                v = pm.getPackageUid(ri.activityInfo.packageName, 0);
            } else {
                v = -1;
            }
        } catch (Throwable t) {
            v = -1;
        }
        homeUid = v;
        return v;
    }

    private static volatile Method UID_OF;

    private static int uidOf(Object userHandle) {
        try {
            if (!(userHandle instanceof UserHandle)) {
                return 0;
            }
            Method m = UID_OF;
            if (m == null) {
                m = UserHandle.class.getMethod("getIdentifier");
                UID_OF = m;
            }
            return ((Integer) m.invoke(userHandle)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }
}
