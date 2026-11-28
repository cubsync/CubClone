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
                        Object r = chain.proceed();
                        if (Boolean.TRUE.equals(r)) return r;
                        Object[] a = chain.getArgs().toArray();
                        if (a.length == 2 && a[0] instanceof Integer && a[1] instanceof Integer) {
                            int callerUser = ((Integer) a[0]).intValue() / 100000;
                            int targetUser = ((Integer) a[1]).intValue();
                            if (callerUser > 0 && callerUser == targetUser
                                    && WmVisibleHook.ours(callerUser)) {
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
            return 1;
        } catch (Throwable t) {
            HookMain.mark(PROP, "fail");
            Log.w(TAG, "media hook failed: " + t);
            return 0;
        }
    }
}
