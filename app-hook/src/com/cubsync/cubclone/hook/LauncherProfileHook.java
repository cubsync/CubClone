package com.cubsync.cubclone.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 让系统桌面（launcher）看不到我们建的空间。
 *
 * <p>现象（G7 实测，2026-11-25）：建一个 clone 空间并装进应用后，桌面自动多出一页——
 * 空间里的电话/设置/图库/CubDesk 全被摆上去了，点开就是那个空间的数据。
 * launcher 的 {@code app_icons.db} 里能查到 {@code profileId=19} 与 {@code profileId=1022}
 * （19 是该空间的 userId，1022 是它的 serialNo）。
 *
 * <p>根因是 AOSP 语义而不是厂商特例：{@code UserManager.getUserProfiles()} 的定义就是
 * 「当前用户 + 同一个 profile 组里的所有用户」，而 clone profile 的 {@code profileGroupId}
 * 就是父用户 0。launcher 拿这个列表去 {@code LauncherApps} 拉应用，自然把分身应用也当自己的。
 * ColorOS / Flyme / AOSP Launcher3 走的是同一套接口。
 *
 * <p>做法：hook {@code UserManagerService.getProfileIds()}，<b>只在调用者是桌面时</b>把返回数组里
 * 我们建的 uid 去掉。系统设置、权限管理、安装器拿这个列表不受影响；Flyme 自己的 999 和其他
 * 用户一律不动。
 *
 * <p>为什么不动 launcher：动它要么给它加模块作用域（违背零配置），要么改它的数据库
 * （每个 ROM 一套 schema，升级即碎）。这里改的是 system_server 里 AOSP 自己的返回值。
 */
final class LauncherProfileHook {

    private static final String TAG = "CubCloneHook";
    private static final String UMS = "com.android.server.pm.UserManagerService";

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
        Class<?> ums;
        try {
            ums = Class.forName(UMS, false, cl);
        } catch (Throwable t) {
            Log.w(TAG, "lp: no " + UMS);
            return 0;
        }
        int n = 0;
        for (Method m : ums.getDeclaredMethods()) {
            if (!"getProfileIds".equals(m.getName())) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length < 2 || pt[0] != int.class) {
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
                            if (!(r instanceof int[])) {
                                return r;
                            }
                            if (!fromLauncher(chain.getThisObject())) {
                                return r;
                            }
                            int[] arr = (int[]) r;
                            int kept = 0;
                            for (int uid : arr) {
                                if (!WmVisibleHook.ours(uid)) {
                                    kept++;
                                }
                            }
                            if (kept == arr.length) {
                                return r;
                            }
                            int[] out = new int[kept];
                            int i = 0;
                            for (int uid : arr) {
                                if (!WmVisibleHook.ours(uid)) {
                                    out[i++] = uid;
                                }
                            }
                            int k = HITS.incrementAndGet();
                            HookMain.mark(PROP_HITS, String.valueOf(k));
                            HookMain.mark(PROP_LAST, arr.length + "->" + out.length);
                            if (k == 1) {
                                Log.i(TAG, "lp: launcher profile list " + arr.length + " -> " + kept);
                            }
                            return out;
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lp hook failed: " + m + " " + t);
            }
        }
        Log.i(TAG, "lp: hooks=" + n);
        return n;
    }

    /** 当前 Binder 调用者是不是桌面 */
    private static boolean fromLauncher(Object ums) {
        int uid = Binder.getCallingUid();
        int home = homeUid(ums);
        if (home > 0 && uid == home) {
            return true;
        }
        return isKnownLauncher(ums, uid);
    }

    private static int homeUid(Object ums) {
        int v = homeUid;
        if (v != -2) {
            return v;
        }
        try {
            PackageManager pm = contextOf(ums).getPackageManager();
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

    private static boolean isKnownLauncher(Object ums, int uid) {
        try {
            String[] pkgs = contextOf(ums).getPackageManager().getPackagesForUid(uid);
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

    private static Context contextOf(Object ums) throws Exception {
        Field f = ums.getClass().getDeclaredField("mContext");
        f.setAccessible(true);
        return (Context) f.get(ums);
    }
}
