package com.cubsync.cubclone.hook;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 让「我们建的空间」里的应用窗口真正显示出来。
 *
 * <p>现象（G7 实测，2026-11-25）：启动 user 11 的 Activity 已经成功——框架日志有
 * {@code START u11}、Task 也建出来了，但状态是 {@code Task{... U=11 visible=false}}：
 * 窗口在，系统不给它显示。所以「分身打不开」不是启动被拦，是最后一步可见性没过。
 *
 * <p>AOSP 判断「某个用户的窗口要不要显示给当前用户」只有一道闸，三个类各一份，
 * 实现都落在同一个方法上：
 * <pre>
 *   ActivityRecord#showToCurrentUser() -> mShowForAllUsers || wms.isCurrentProfile(mUserId)
 *   Task#showToCurrentUser()           -> mForceShowForAllUsers || showForAllUsers()
 *                                         || wms.isCurrentProfile(getTopMostTask().mUserId)
 *   WindowState#showToCurrentUser()    -> ... || wms.isCurrentProfile(mShowUserId)
 *
 *   WindowManagerService#isCurrentProfile(int uid)      // A13 反汇编确认
 *       return uid == mCurrentUserId || uid in mCurrentProfileIds;
 * </pre>
 *
 * <p>Flyme 自带的分身是 {@code profile.CLONE}（parentId=0），天然属于 user 0 的 profile 组，
 * 所以 {@code isCurrentProfile(999)} 为真、同屏可见；我们建的是 {@code full.SECONDARY}
 * 独立用户，不在这个列表里，于是 task 建出来也是 invisible。
 *
 * <p>本 hook 只把「我们自己建的 uid」补进这个判断，<b>不改用户类型、不碰 Flyme 的多开实现、
 * 不去冒充它的并行空间</b>——空间仍是完整用户，不会出现在 Flyme 的应用分身设置里，
 * 也不会被它当成自己的空间删掉。
 *
 * <p>影响面：这个 ROM 里 {@code isCurrentProfile} 全仓只有 3 个调用点，全是
 * {@code showToCurrentUser()}，即「窗口要不要显示」，不涉及权限、通知、输入法。
 */
final class WmVisibleHook {

    private static final String TAG = "CubCloneHook";
    private static final String WMS = "com.android.server.wm.WindowManagerService";

    /** 我们建的空间 uid 列表，逗号分隔；主应用在空间变化时写，这里每次现读，改完不用重启 */
    static final String PROP_SPACES = "persist.sys.cubclone.spaces";

    static final String PROP_HITS = "sys.cubclone.hook.wm.hits";
    static final String PROP_LAST = "sys.cubclone.hook.wm";

    private static final AtomicInteger HITS = new AtomicInteger();
    /** 只在第一次命中时打日志，避免每次 draw 都刷 */
    private static final AtomicInteger FIRST = new AtomicInteger();

    private final XposedInterface xp;

    WmVisibleHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        try {
            Class<?> wms = Class.forName(WMS, false, cl);
            Method m = wms.getDeclaredMethod("isCurrentProfile", int.class);
            m.setAccessible(true);
            try {
                xp.deoptimize(m);
            } catch (Throwable ignored) {
            }
            xp.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        // 原本可见的一律不动（Flyme 自己的分身、当前用户都走这一支）
                        if (Boolean.TRUE.equals(r)) return r;
                        int uid = ((Integer) chain.getArg(0)).intValue();
                        if (!ours(uid, ctxOf(chain.getThisObject()))) return r;
                        int k = HITS.incrementAndGet();
                        if (FIRST.getAndIncrement() == 0) {
                            Log.i(TAG, "wm visible: uid " + uid + " -> true");
                            HookMain.mark(PROP_LAST, String.valueOf(uid));
                        }
                        HookMain.mark(PROP_HITS, String.valueOf(k));
                        return Boolean.TRUE;
                    });
            Log.i(TAG, "wm isCurrentProfile hooked");
            return 1;
        } catch (Throwable t) {
            // 非 AOSP 结构 / 方法被内联时跳过：8A 本来就能显示，不影响
            Log.w(TAG, "wm hook failed: " + t);
            return 0;
        }
    }

    /** uid 是不是我们建的空间（属性优先，读不到再查该用户自己的归属标记） */
    static boolean ours(int uid) {
        return ours(uid, null);
    }

    /**
     * @param ctx WMS 的 system context；为 null 时只查属性（老调用点兼容）
     */
    static boolean ours(int uid, android.content.Context ctx) {
        if (uid <= 0) return false;
        String csv = read(PROP_SPACES);
        if (csv.length() > 0) {
            String want = String.valueOf(uid);
            for (String s : csv.split(",")) {
                if (s.trim().equals(want)) return true;
            }
        }
        // 属性可能还没同步（CLI 直接建号、App 尚未刷新）：查该用户自己的归属标记，
        // 这个标记是建号时必写的，比属性可靠
        if (ctx == null) return false;
        try {
            // getIntForUser 是 @SystemApi，编译桩里没有，只能反射；system_server 不受 hidden API 限制
            java.lang.reflect.Method m = android.provider.Settings.Secure.class.getMethod(
                    "getIntForUser", android.content.ContentResolver.class,
                    String.class, int.class, int.class);
            Object v = m.invoke(null, ctx.getContentResolver(), "cubclone_owned",
                    Integer.valueOf(0), Integer.valueOf(uid));
            return ((Integer) v).intValue() == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 WMS 实例取 system context（读别的用户 settings 要用） */
    static android.content.Context ctxOf(Object wms) {
        try {
            java.lang.reflect.Field f = wms.getClass().getDeclaredField("mContext");
            f.setAccessible(true);
            return (android.content.Context) f.get(wms);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String read(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, key, "");
            return v == null ? "" : v.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
