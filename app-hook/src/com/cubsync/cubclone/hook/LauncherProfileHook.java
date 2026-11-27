package com.cubsync.cubclone.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 让系统桌面（launcher）看不到我们建的空间里的应用，同时不影响最近任务。
 *
 * <p>现象（G7 实测，2026-11-25）：建一个 clone 空间并装进应用后，桌面自动多出一页——
 * 空间里的电话/设置/图库/CubDesk 全被摆上去了。根因是 {@code LauncherApps} 会把当前用户
 * 同组的 profile 一起拉进来。
 *
 * <h3>拦截点怎么定的（2026-11-27，全部来自反编译）</h3>
 * 桌面要「全量应用列表」和最近任务要「单个应用的图标/名字」走的是不同方法：
 * <ul>
 *   <li><b>全量列表</b>：Flyme 走 {@code queryIntentLauncherActivities(Intent, int, UserHandle)}
 *       ——魅族桌面 dex 里 {@code queryIntentActivities} 出现 6 次、{@code getLauncherActivities} 0 次；
 *       AOSP/ColorOS 走 {@code getActivityList}/{@code getLauncherActivities} 的
 *       {@code packageName == null} 分支。这两条**清空结果**，桌面就看不到空间应用。</li>
 *   <li><b>单包查询</b>：最近任务拿任务所属用户的图标和名字时，带包名调
 *       {@code getLauncherActivities(pkg, …)}（实测打开最近任务 {@code hits} 从 6 涨到 23）。
 *       这条**原样放行**，否则任务卡片的图标和标题会一起消失。</li>
 * </ul>
 *
 * <p>所以别再按「调用者是桌面」一刀切：Flyme 的最近任务就跑在桌面进程里
 * （{@code isHomeRecentsComponent=true}），按进程分不开，只能按方法分。
 * {@code getProfileIds} / {@code UserManagerService} 一律不碰，系统其它组件不受影响。
 *
 * <p>可调：{@code persist.sys.cubclone.lp.mode}
 * <ul>
 *   <li>0 = 全放行（对照用）</li>
 *   <li>1 = 默认：全量列表清空 + 单包查询放行</li>
 *   <li>2 = 激进：单包查询也清空（旧版行为，会伤最近任务）</li>
 * </ul>
 * 每次调用现读，改完立即生效。
 */
final class LauncherProfileHook {

    private static final String TAG = "CubCloneHook";
    private static final String IMPL =
            "com.android.server.pm.LauncherAppsService$LauncherAppsImpl";

    static final String PROP_HITS = "sys.cubclone.hook.lp.hits";
    static final String PROP_LAST = "sys.cubclone.hook.lp";
    /** 全量列表被清空的次数（桌面铺图标） */
    static final String PROP_HITS_LIST = "sys.cubclone.hook.lp.q";
    /** 单包查询被清空的次数（只有 mode=2 才会涨） */
    static final String PROP_HITS_ONE = "sys.cubclone.hook.lp.a";
    static final String PROP_MODE = "persist.sys.cubclone.lp.mode";

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

    /** 全量列表接口：参数里带 UserHandle，结果里是该用户全部可启动应用 */
    private static final String[] LIST_METHODS = {
            "queryIntentLauncherActivities",      // Flyme：桌面抽屉
            "queryActivitiesForUser",             // Flyme/AOSP：同上，上层封装
            "getActivityList",                    // AOSP / ColorOS
            "getLauncherActivities",              // Flyme 服务端方法名
    };

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger HITS_LIST = new AtomicInteger();
    private static final AtomicInteger HITS_ONE = new AtomicInteger();
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
            String name = m.getName();
            if (!contains(LIST_METHODS, name)) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length < 2 || pt[0] != String.class) {
                // queryIntentLauncherActivities 第一个参数是 Intent
                if (pt.length < 2 || pt[0] != Intent.class) {
                    continue;
                }
            }
            int userArg = -1;
            for (int i = 0; i < pt.length; i++) {
                if (pt[i] == UserHandle.class) {
                    userArg = i;
                    break;
                }
            }
            if (userArg < 0) {
                continue;
            }
            // 单包查询：带包名（String 第一个参数）的是最近任务那条，只有 mode=2 才拦
            final boolean single = pt[0] == String.class;
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
                            if (!HookMain.propOn(LauncherAppsHook.PROP_ENABLE)) {
                                return chain.proceed();
                            }
                            int mode = mode();
                            if (mode <= 0) {
                                return chain.proceed();
                            }
                            Object pkg = chain.getArg(0);
                            boolean full = !single || pkg == null;
                            if (single && !full && mode < 2) {
                                // 最近任务按包名查图标/名字，必须放行
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
                            int total = HITS.incrementAndGet();
                            HookMain.mark(PROP_HITS, String.valueOf(total));
                            if (full) {
                                int k = HITS_LIST.incrementAndGet();
                                HookMain.mark(PROP_HITS_LIST, String.valueOf(k));
                            } else {
                                int k = HITS_ONE.incrementAndGet();
                                HookMain.mark(PROP_HITS_ONE, String.valueOf(k));
                            }
                            HookMain.mark(PROP_LAST, (full ? "list " : "one ")
                                    + name + " uid=" + uid);
                            if (total == 1) {
                                Log.i(TAG, "lp: hide " + name + " of uid " + uid);
                            }
                            return empty(r);
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lp hook failed: " + m + " " + t);
            }
        }
        Log.i(TAG, "lp: hooks=" + n);
        return n;
    }

    /** 0=全放行 1=默认 2=连单包查询也拦 */
    private static int mode() {
        String v = HookMain.getProp(PROP_MODE);
        if (v.length() == 0) {
            return 1;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return 1;
        }
    }

    private static boolean contains(String[] a, String s) {
        for (String x : a) {
            if (x.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把结果清空。返回类型各版本不同（{@code List} 或 {@code ParceledListSlice}），
     * 优先按「同类型 + 空列表」新建一个（ParceledListSlice 的 mTotalCount 是构造时算的，
     * 直接 clear 内部 list 会让计数和内容对不上）；失败就清原列表，再失败原样返回。
     */
    private static Object empty(Object r) {
        if (r == null) {
            return null;
        }
        try {
            java.lang.reflect.Constructor<?> c = r.getClass().getConstructor(List.class);
            return c.newInstance(java.util.Collections.emptyList());
        } catch (Throwable ignored) {
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
