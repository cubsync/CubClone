package com.cubsync.cubclone.hook;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 让自建空间能访问自己的共享存储。
 *
 * <p>AOSP 从 Android 11 起共享存储走 FUSE：所有用户共用 {@code /storage/emulated} 一个挂载，
 * 路径按调用者 uid 映射到 {@code /data/media/<uid>}。放不放行由 MediaProvider 的
 * {@code shouldAllowLookupForFuse(int uid, int targetUserId)} 决定，AOSP 只允许
 * 「同一用户」和「父用户 ↔ 子用户」；Flyme 又加了一层只认 999。
 *
 * <p>运行时新建的 CLONE 用户不在任何白名单里，所以 {@code /storage/emulated/<uid>}
 * 直接返回 EACCES——文件选择器「无任何文件」、应用保存到相册失败，都是这一条。
 *
 * <p>这里只放行一种情况：**调用者和目标用户是同一个、且是我们自己建的空间**。
 * 也就是「空间访问自己的存储」，等于 MYUI 纯 FUSE 下每个分身有独立可写卷，
 * 不打开「空间读主空间」的口子。
 */
final class MediaUserHook {

    private static final String TAG = "CubCloneHook";
    static final String PROP = "sys.cubclone.hook.media";
    private static final String CLS = "com.android.providers.media.MediaProvider";
    private static final String METHOD = "shouldAllowLookupForFuse";

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger FIRST = new AtomicInteger();
    private static final AtomicInteger CALLS = new AtomicInteger();
    static final String PROP_CLONE = "sys.cubclone.hook.cloneuser";

    private final XposedInterface xp;

    MediaUserHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        Class<?> c;
        try {
            c = Class.forName(CLS, false, cl);
        } catch (Throwable t) {
            HookMain.mark(PROP, "no-class");
            return 0;
        }
        Method target = null;
        for (Method m : c.getDeclaredMethods()) {
            if (!METHOD.equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == int.class && p[1] == int.class) {
                target = m;
                break;
            }
        }
        if (target == null) {
            HookMain.mark(PROP, "no-method");
            Log.w(TAG, METHOD + " not found");
            return 0;
        }
        try {
            try {
                xp.deoptimize(target);
            } catch (Throwable ignored) {
            }
            xp.hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        int seq = CALLS.getAndIncrement();
                        if (seq < 6) {
                            Log.i(TAG, "shouldAllowLookup ENTER #" + seq);
                        }
                        Object r;
                        Object[] a;
                        try {
                            r = chain.proceed();
                            a = chain.getArgs().toArray();
                        } catch (Throwable t) {
                            Log.w(TAG, "shouldAllowLookup failed at #" + seq, t);
                            return Boolean.FALSE;
                        }
                        int callerUid = -1, targetUser = -1;
                        if (a.length == 2 && a[0] instanceof Integer && a[1] instanceof Integer) {
                            callerUid = ((Integer) a[0]).intValue();
                            targetUser = ((Integer) a[1]).intValue();
                        }
                        // 与自建空间相关的调用一律记录，便于定位
                        if (seq < 12 || WmVisibleHook.ours(targetUser)
                                || (callerUid >= 0 && WmVisibleHook.ours(callerUid / 100000))) {
                            Log.i(TAG, "shouldAllowLookup# " + seq + " callerUid=" + callerUid
                                    + " targetUser=" + targetUser + " r=" + r);
                        }
                        if (Boolean.TRUE.equals(r)) return r;
                        if (callerUid >= 0) {
                            int callerUser = callerUid / 100000;
                            // 放行两种系统自身就会放行的情形，不区分 ROM：
                            //   ① 空间内的应用访问自己的存储；
                            //   ② 系统进程（root/vold，uid 0）访问空间的存储——
                            //      vold 挂载时要先卸掉 Android/data 上的旧视图，这一步被拒就整卷回滚。
                            if (WmVisibleHook.ours(targetUser)
                                    && (callerUser == targetUser || callerUid == 0)) {
                                int k = HITS.incrementAndGet();
                                if (FIRST.getAndIncrement() == 0) {
                                    Log.i(TAG, "media fuse: user " + callerUser + " -> allow");
                                }
                                HookMain.mark(PROP, "hit:" + k);
                                return Boolean.TRUE;
                            }
                        }
                        return r;
                    });
            HookMain.mark(PROP, "hooked");
            Log.i(TAG, METHOD + " hooked");
            hookCloneUser(cl);
            hookLookupEntry(cl);
            return 1;
        } catch (Throwable t) {
            HookMain.mark(PROP, "fail");
            Log.w(TAG, "media hook failed: " + t);
            return 0;
        }
    }

    /**
     * native 每次 lookup 都会进 {@code onFileLookupForFuse}；只记录前若干次，
     * 用来判断「Java 层到底有没有参与这次 lookup」。
     */
    private void hookLookupEntry(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(CLS, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"onFileLookupForFuse".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 3 || p[0] != String.class) continue;
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            int seq = CALLS.getAndIncrement();
                            if (seq < 20) {
                                Object[] a = chain.getArgs().toArray();
                                Log.i(TAG, "onFileLookupForFuse#" + seq + " " + a[0]
                                        + " uid=" + a[1] + " userId=" + a[2]);
                            }
                            return r;
                        });
                Log.i(TAG, "onFileLookupForFuse hooked");
            }
        } catch (Throwable t) {
            Log.w(TAG, "lookup entry hook failed: " + t);
        }
    }

    /**
     * native 的 {@code IsAppCloneUser} 对应 Java 的 {@code isAppCloneUserForFuse(int)}：
     * FUSE 用它判断「这个用户是不是分身用户」。AOSP 只看 userType 的 mediaSharedWithParent，
     * 自建空间默认不是，这里对自建空间直接返回 true。
     */
    /** 当前是否存在自建空间（属性非空即可，逐个解析交给 ours()） */
    private static boolean anySpace() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String csv = (String) sp.getMethod("get", String.class)
                    .invoke(null, "persist.sys.cubclone.spaces");
            return csv != null && csv.trim().length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookCloneUser(ClassLoader cl) {        try {
            Class<?> c = Class.forName(CLS, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"isAppCloneUserForFuse".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != int.class) continue;
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            Object[] a = chain.getArgs().toArray();
                            if (a.length == 1 && a[0] instanceof Integer) {
                                int u = ((Integer) a[0]).intValue();
                                int seq = CALLS.getAndIncrement();
                                if (seq < 24 || WmVisibleHook.ours(u)) {
                                    Log.i(TAG, "isAppCloneUserForFuse(" + u + ") = " + r);
                                }
                                if (!Boolean.TRUE.equals(r) && WmVisibleHook.ours(u)) {
                                    Log.i(TAG, "isAppCloneUserForFuse: " + u + " -> true (ours)");
                                    return Boolean.TRUE;
                                }
                                // FUSE 的 native 判定会拿「主用户」也问一次；
                                // 只要存在自建空间，主用户这边同样按分身场景放行。
                                if (!Boolean.TRUE.equals(r) && u == 0 && anySpace()) {
                                    Log.i(TAG, "isAppCloneUserForFuse: 0 -> true (has spaces)");
                                    return Boolean.TRUE;
                                }
                            }
                            return r;
                        });
                HookMain.mark(PROP_CLONE, "hooked");
                Log.i(TAG, "isAppCloneUserForFuse hooked");
            }
        } catch (Throwable t) {
            HookMain.mark(PROP_CLONE, "fail");
            Log.w(TAG, "clone user hook failed: " + t);
        }
    }
}
