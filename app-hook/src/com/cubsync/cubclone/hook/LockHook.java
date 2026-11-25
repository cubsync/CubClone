package com.cubsync.cubclone.hook;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 解锁「我们建的空间」时补上 AOSP 本来就有的空值保护。
 *
 * <p>现象（G7 实测，2026-11-25）：{@code am unlock-user <自建 clone profile>} 之后
 * system_server 必崩，dropbox 里是：
 * <pre>
 *   java.lang.NullPointerException: Attempt to read from field 'int android.content.pm.UserInfo.id'
 *     on a null object reference in method
 *     'void com.android.server.locksettings.LockSettingsService
 *          .tieProfileLockIfNecessary(int, com.android.internal.widget.LockscreenCredential)'
 *     at LockSettingsService.tieProfileLockIfNecessary(LockSettingsService.java:394)
 *     at LockSettingsService$1.run(LockSettingsService.java:831)
 * </pre>
 * 崩完 system_server 整个重来（kernel uptime 不变、所有用户掉出 running），
 * 于是「点分身应用」表现为闪一下没反应 —— 启动请求根本没机会执行。
 *
 * <p>AOSP 里这个方法只服务 <b>managed profile（工作资料）</b>：把工作资料的锁屏凭据
 * 绑到主用户上。clone profile 不属于 managed profile，本来就不该走这段；
 * Flyme 改坏了判断，对我们建的 profile 取 {@code UserInfo} 拿到 null 还继续读 {@code .id}。
 *
 * <p>处理：{@code profileId} 是我们的空间时直接返回（等价于 AOSP 对非 managed profile
 * 的早退），其余一律走原实现。不碰 999，也不改任何锁屏数据。
 */
final class LockHook {

    private static final String TAG = "CubCloneHook";
    private static final String LSS = "com.android.server.locksettings.LockSettingsService";
    private static final String METHOD = "tieProfileLockIfNecessary";

    static final String PROP_HITS = "sys.cubclone.hook.lock.hits";
    static final String PROP_LAST = "sys.cubclone.hook.lock";

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger FIRST = new AtomicInteger();

    private final XposedInterface xp;

    LockHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        Class<?> c;
        try {
            c = Class.forName(LSS, false, cl);
        } catch (Throwable t) {
            Log.i(TAG, "no LockSettingsService; skip");
            return 0;
        }
        Method target = null;
        for (Method m : c.getDeclaredMethods()) {
            // 签名 (int profileId, LockscreenCredential)：第一个参数是 int，共两个参数。
            // 按形状匹配而不是写死类型名——LockscreenCredential 在部分 ROM 上是内部类。
            Class<?>[] p = m.getParameterTypes();
            if (METHOD.equals(m.getName()) && p.length == 2
                    && (p[0] == int.class || p[0] == Integer.class)) {
                target = m;
                break;
            }
        }
        if (target == null) {
            Log.w(TAG, METHOD + " not found; skip");
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
                        Object a0 = chain.getArg(0);
                        int profileId = a0 instanceof Integer ? ((Integer) a0).intValue() : -1;
                        if (profileId > 0 && WmVisibleHook.ours(profileId)) {
                            int k = HITS.incrementAndGet();
                            if (FIRST.compareAndSet(0, 1)) {
                                Log.i(TAG, "skip tieProfileLock for our space " + profileId);
                            }
                            HookMain.mark(PROP_HITS, String.valueOf(k));
                            HookMain.mark(PROP_LAST, "skip-" + profileId);
                            // 等价于 AOSP「非 managed profile 直接早退」，不执行原实现
                            return null;
                        }
                        return chain.proceed();
                    });
            HookMain.mark(PROP_LAST, "hooked");
            Log.i(TAG, "tieProfileLockIfNecessary hooked");
            return 1;
        } catch (Throwable t) {
            Log.w(TAG, "hook " + METHOD + " failed: " + t);
            return 0;
        }
    }
}
