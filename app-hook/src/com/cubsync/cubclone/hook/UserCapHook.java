package com.cubsync.cubclone.hook;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 放开「整机用户数上限」—— 建号的第二道闸。
 *
 * <p>{@code UserManagerService.createUserInternalUncheckedNoTracing} 先查
 * {@code canAddMoreUsersOfType()}（类型上限，{@link HookMain} 已处理），再查
 * {@code isUserLimitReached()}：
 * <pre>
 *   int count = getAliveUsersExcludingGuestsCountLU();
 *   return count &gt;= UserManager.getMaxSupportedUsers();
 * </pre>
 * Flyme 把 {@code config_multiuserMaximumUsers} 压到 4：主用户 + 两个我们的空间 + 自带分身
 * 就满了，{@code pm create-user} 直接报 "Cannot add user. Maximum user limit is reached."
 * （G7 实测，2026-11-25）。AOSP 原生是 4，LineageOS 也常见 4~8。
 *
 * <p>改 {@code getMaxSupportedUsers()} 的返回值一处顶两处：它是 {@code isUserLimitReached}
 * 的唯一判据，dumpsys user 的 "Max users" 也读它。原值已更大就原样返回，不比目标值小。
 */
final class UserCapHook {

    private static final String TAG = "CubCloneHook";
    private static final AtomicInteger HITS = new AtomicInteger();

    static final String PROP_HITS = "sys.cubclone.hook.maxusers.hits";
    static final String PROP_LAST = "sys.cubclone.hook.maxusers";

    private final XposedInterface xp;

    UserCapHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> um = Class.forName("android.os.UserManager", false, cl);
            Method m = um.getDeclaredMethod("getMaxSupportedUsers");
            m.setAccessible(true);
            try {
                xp.deoptimize(m);
            } catch (Throwable ignored) {
            }
            xp.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        int orig = r instanceof Integer ? ((Integer) r).intValue() : 0;
                        // 每次现读：主应用改上限后不用重启，建空间时立刻按新值判
                        int limit = HookMain.readLimit();
                        if (orig >= limit) {
                            return r;
                        }
                        int k = HITS.incrementAndGet();
                        if (k == 1) {
                            Log.i(TAG, "max users " + orig + " -> " + limit);
                            HookMain.mark(PROP_LAST, orig + "->" + limit);
                        }
                        HookMain.mark(PROP_HITS, String.valueOf(k));
                        return Integer.valueOf(limit);
                    });
            n++;
            Log.i(TAG, "max users hook ok, target=" + HookMain.readLimit());
        } catch (Throwable t) {
            Log.w(TAG, "max users hook failed: " + t);
        }
        n += hookUserLimitReached(cl);
        return n;
    }

    /**
     * 兜底：直接改判 {@code isUserLimitReached()}。
     * services 是 AOT 编译的，静态方法有可能在调用点留着内联副本（A16 上踩过），
     * 这条路一断还有这里 —— 它自己就是判断函数，改成「现存活用户 &lt; 上限」即可。
     */
    private int hookUserLimitReached(ClassLoader cl) {
        try {
            Class<?> ums = Class.forName("com.android.server.pm.UserManagerService", false, cl);
            Method cnt;
            try {
                cnt = ums.getDeclaredMethod("getAliveUsersExcludingGuestsCountLU");
                cnt.setAccessible(true);
            } catch (Throwable t) {
                cnt = null;
            }
            final Method countM = cnt;
            for (Method m : ums.getDeclaredMethods()) {
                if (!"isUserLimitReached".equals(m.getName())
                        || m.getParameterTypes().length != 0) {
                    continue;
                }
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (countM != null) {
                                try {
                                    int alive = ((Integer) countM.invoke(chain.getThisObject())).intValue();
                                    boolean reached = alive >= HookMain.readLimit();
                                    HookMain.mark(PROP_LAST, "alive=" + alive + ",reached=" + reached);
                                    return Boolean.valueOf(reached);
                                } catch (Throwable ignored) {
                                }
                            }
                            return chain.proceed();
                        });
                Log.i(TAG, "isUserLimitReached hooked");
                return 1;
            }
        } catch (Throwable t) {
            Log.w(TAG, "isUserLimitReached hook failed: " + t);
        }
        return 0;
    }
}
