package com.cubsync.cubclone.hook;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 让 MediaProvider 认我们自建的空间「与父用户共享媒体」。
 *
 * <p>链路（Android 13 源码）：MediaProvider 的 FUSE 层用
 * {@code shouldAllowLookupForFuse(uid, pathUserId)} 判定跨用户访问，内部要求
 * {@code isAppCloneUserPair()} 为真；它先看
 * {@code UserCache.userSharesMediaWithParent()}，最终落到 system_server 的
 * {@code UserManagerService.isMediaSharedWithParent(userId)}——判断的是 user type 的
 * {@code mediaSharedWithParent} 配置，{@code profile.CLONE} 默认是 false。
 *
 * <p>后果：vold 给 clone 用户挂载时，{@code mountFuseBindMounts()} 要把
 * {@code /mnt/runtime/default/emulated/<uid>/Android/data} bind 进 FUSE 视图，
 * FUSE 的 lookup 被 MediaProvider 以 EACCES 拒绝，整卷回滚 → {@code unmountable}。
 *
 * <p>这里只对我们自己建的空间返回 true，不动系统原有用户。
 */
final class MediaShareHook {

    private static final String TAG = "CubCloneHook";
    static final String PROP = "sys.cubclone.hook.mediashare";
    private static final String CLS = "com.android.server.pm.UserManagerService";
    private static final String METHOD = "isMediaSharedWithParent";

    private static final AtomicInteger HITS = new AtomicInteger();

    private final XposedInterface xp;

    MediaShareHook(XposedInterface xp) {
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
            if (p.length == 1 && p[0] == int.class) {
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
                        Object[] a = chain.getArgs().toArray();
                        if (a.length == 1 && a[0] instanceof Integer) {
                            int uid = ((Integer) a[0]).intValue();
                            if (uid > 0 && WmVisibleHook.ours(uid)) {
                                int k = HITS.incrementAndGet();
                                if (k == 1) {
                                    Log.i(TAG, "mediaShare: user " + uid + " -> true");
                                }
                                HookMain.mark(PROP, "hit:" + k);
                                return Boolean.TRUE;
                            }
                        }
                        return chain.proceed();
                    });
            HookMain.mark(PROP, "hooked");
            Log.i(TAG, METHOD + " hooked");
            return 1;
        } catch (Throwable t) {
            HookMain.mark(PROP, "fail");
            Log.w(TAG, "mediaShare hook failed: " + t);
            return 0;
        }
    }
}
