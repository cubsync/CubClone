package com.cubsync.cubclone.hook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * CubClone Hook —— 现代 Xposed API（libxposed API 102）入口，只作用于 system_server。
 *
 * <p>目标：放开 {@code android.os.usertype.profile.CLONE} 的数量限制。建号链上有两道闸都要过：
 * <ol>
 *   <li>{@code canAddMoreUsersOfType()} 读 {@code mMaxAllowed}（按类型总数）——
 *       AOSP 原生是 UNLIMITED，但 LineageOS 设成 3，8A 实测就卡在这里；</li>
 *   <li>{@code canAddMoreProfilesToUser()} 读 {@code mMaxAllowedPerParent}（每父用户）——
 *       AOSP 原生是 1，所有 ROM 都需要改。</li>
 * </ol>
 *
 * <p><b>两条腿走路</b>（2026-11-24 实测教训）：
 * <ol>
 *   <li><b>改数据</b>（主）：{@code UserManagerService} 构造完成后，把 {@code mUserTypes} 里
 *       CLONE 那条 {@code UserTypeDetails} 的两个上限字段直接改掉。
 *       不依赖方法调用、不受 ART 内联影响，是唯一能保证「全版本都真的生效」的做法。</li>
 *   <li><b>hook</b>（辅）：{@code canAddMoreProfilesToUser} 的全部重载，只对 CLONE 返回 true。
 *       万一某些 ROM 不把上限存在 {@code UserTypeDetails} 里，这条还能兜住。</li>
 * </ol>
 *
 * <p>为什么不是只 hook：A16（LOS 实测）里 {@code createUserInternalUncheckedNoTracing} 调的是
 * {@code canAddMoreProfilesToUser(UserTypeDetails,int)} 这个内部重载，而且 services 是 AOT 编译的 ——
 * 钩子挂上了、{@code deoptimize} 也返回成功，实际调用仍走内联副本，属性计数一次都不涨。
 *
 * <p>风险面：改的是 system_server 内存里的一个 int，不落盘、不改 XML、不写用户数据；
 * 只动 CLONE 类型，托管资料/隐私空间/受限资料原样。全部 try/catch，失败即降级为「功能不生效」。
 */
public final class HookMain extends XposedModule {

    private static final String TAG = "CubCloneHook";
    private static final String UMS = "com.android.server.pm.UserManagerService";
    private static final String METHOD = "canAddMoreProfilesToUser";
    private static final String CLONE = "android.os.usertype.profile.CLONE";

    /**
     * 目标上限。主应用通过 {@code setprop sys.cubclone.hook.limit N} 指定，这里只读。
     * 读不到就用默认值；上限夹到 {@link #MAX_LIMIT}。21474 = UserHandle.MAX_USER_ID（uid = userId*100000，再大就溢出）：
     * 但服务器/大存储设备上够用，离 Android 自己的 UserHandle.MAX_USER_ID(21474) 还很远。
     */
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 21474;

    static final String PROP = "sys.cubclone.hook";
    static final String PROP_HITS = "sys.cubclone.hook.hits";
    static final String PROP_FIELD = "sys.cubclone.hook.field";
    static final String PROP_CREATE = "sys.cubclone.hook.create";
    static final String PROP_APPLY = "sys.cubclone.hook.apply";
    static final String PROP_LIMIT = "sys.cubclone.hook.limit";
    /** 主应用写这个：persist 前缀重启后不丢，hook 开机直接读到用户上次选的值 */
    static final String PROP_LIMIT_PERSIST = "persist.sys.cubclone.hook.limit";

    private static final AtomicInteger HITS = new AtomicInteger();
    /** rewrite 会被多个构造器各触发一次，只有第一次的前后值有参考意义。 */
    private static final AtomicInteger FIRST = new AtomicInteger();
    /** 缓存 SystemProperties.getInt 的 Method（readLimit 会被反复调用）。 */
    private static volatile Method SP_GET_INT;

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            Log.i(TAG, "system_server starting, api=" + getApiVersion());
            apply(param.getClassLoader());
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
            try {
                log(Log.ERROR, TAG, "init failed: " + t, t);
            } catch (Throwable ignored) {
            }
        }
    }

    private void apply(ClassLoader cl) {
        // 版本先上报：主应用靠它区分「已装 1.0.7」和「正在跑 1.0.7」，重装后没重启能提示出来
        mark("sys.cubclone.hook.ver", BuildVer.V);
        Class<?> c = find(cl);
        if (c == null) {
            Log.w(TAG, "UserManagerService not found; nothing done");
            return;
        }
        int ctors = hookConstructors(c);
        int methods = hookMethods(c);
        // 安装拦截：把「未指定用户」的安装收敛到 user 0（与空间上限互不影响，各自 try/catch）
        int install = 0;
        try {
            install = new InstallHook(this).apply(cl);
        } catch (Throwable t) {
            Log.w(TAG, "install hook failed: " + t);
        }
        // 整机用户数上限（Flyme/Lineage 压得比目标值小，建到一半就 "Maximum user limit"）
        int cap = 0;
        try {
            cap = new UserCapHook(this).apply(cl);
        } catch (Throwable t) {
            Log.w(TAG, "user cap hook failed: " + t);
        }
        // Flyme 跨用户启动拦截：只放行返回值 null 的情况（我们的空间否则打不开）
        int flyme = 0;
        try {
            flyme = new FlymeHook(this).apply(cl);
        } catch (Throwable t) {
            Log.w(TAG, "flyme hook failed: " + t);
        }
        mark(PROP_APPLY, "ctor=" + ctors + ",m=" + methods + ",i=" + install
                + ",u=" + cap + ",f=" + flyme);
        if (ctors > 0 || methods > 0) {
            mark(PROP, "1");
        }
    }

    // ─────────────────── ① 改数据：构造完成后改写 mUserTypes ───────────────────

    private int hookConstructors(Class<?> c) {
        int n = 0;
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            try {
                hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            try {
                                rewrite(chain.getThisObject());
                            } catch (Throwable t) {
                                Log.w(TAG, "rewrite failed: " + t);
                            }
                            return r;
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "hook ctor failed: " + t);
            }
        }
        return n;
    }

    /** 把 mUserTypes 里 CLONE 那条的两个上限字段一起改到目标值。 */
    private static void rewrite(Object ums) throws Exception {
        Field mf = ums.getClass().getDeclaredField("mUserTypes");
        mf.setAccessible(true);
        Object mapObj = mf.get(ums);
        if (!(mapObj instanceof Map)) {
            Log.w(TAG, "mUserTypes is " + (mapObj == null ? "null" : mapObj.getClass().getName()));
            return;
        }
        Object detail = ((Map<?, ?>) mapObj).get(CLONE);
        if (detail == null) {
            Log.w(TAG, "no CLONE entry in mUserTypes");
            mark(PROP_FIELD, "nocls");
            return;
        }
        int limit = readLimit();

        // ③ 每父用户上限：所有 ROM 都设了（AOSP 原生 1），必须改。
        Field pf = detail.getClass().getDeclaredField("mMaxAllowedPerParent");
        pf.setAccessible(true);
        int beforeP = pf.getInt(detail);
        pf.setInt(detail, limit);
        int afterP = pf.getInt(detail);

        // ① 类型总数上限：AOSP 原生是 UNLIMITED(-1)，但 LineageOS 会设成 3，必须一起放开。
        //    -1 表示本来就无限制，比目标值大，不动它。
        String all;
        try {
            Field af = detail.getClass().getDeclaredField("mMaxAllowed");
            af.setAccessible(true);
            int beforeA = af.getInt(detail);
            if (beforeA >= 0 && beforeA < limit) {
                af.setInt(detail, limit);
                all = beforeA + "->" + af.getInt(detail);
            } else {
                all = "keep " + beforeA;
            }
        } catch (Throwable t) {
            all = "err " + t.getClass().getSimpleName();
        }

        boolean first = FIRST.getAndIncrement() == 0;
        if (first) {
            Log.i(TAG, "limit=" + limit + " perParent " + beforeP + "->" + afterP + ", maxAllowed " + all);
        }
        mark(PROP_LIMIT, String.valueOf(limit));
        if (first) {
            mark(PROP_FIELD, "p" + beforeP + "->" + afterP + " a" + all);
        }
    }

    static int readLimit() {
        int v = DEFAULT_LIMIT;
        try {
            // 缓存 Method：getMaxSupportedUsers 的 hook 里每次都读上限，别每次反射找一遍
            Method get = SP_GET_INT;
            if (get == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                get = sp.getMethod("getInt", String.class, int.class);
                SP_GET_INT = get;
            }
            // 用户选的值走 persist 属性（重启不丢）；没有才退回运行时属性，最后用默认值
            int p = ((Integer) get.invoke(null, PROP_LIMIT_PERSIST, Integer.valueOf(-1))).intValue();
            v = p > 0 ? p
                    : ((Integer) get.invoke(null, PROP_LIMIT, Integer.valueOf(DEFAULT_LIMIT))).intValue();
        } catch (Throwable ignored) {
        }
        if (v < 1) {
            v = 1;
        }
        if (v > MAX_LIMIT) {
            v = MAX_LIMIT;
        }
        return v;
    }

    // ─────────────────── ② hook：所有 canAddMoreProfilesToUser 重载 ───────────────────

    private int hookMethods(Class<?> c) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!METHOD.equals(m.getName())) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length < 2) {
                continue;
            }
            boolean first = pt[0] == String.class
                    || "UserTypeDetails".equals(pt[0].getSimpleName());
            if (!first) {
                continue;
            }
            try {
                deoptimize(m);
            } catch (Throwable ignored) {
            }
            try {
                hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            if (CLONE.equals(typeName(chain.getArg(0)))) {
                                int k = HITS.incrementAndGet();
                                if (k == 1) {
                                    Log.i(TAG, "hook hit #1");
                                }
                                mark(PROP_HITS, String.valueOf(k));
                                return Boolean.TRUE;
                            }
                            return r;
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "hook method failed: " + m);
            }
        }
        return n;
    }

    /** 第一个参数可能是 String（公开重载）或 UserTypeDetails（A16 起的内部重载）。 */
    private static String typeName(Object o) {
        if (o instanceof String) {
            return (String) o;
        }
        if (o == null) {
            return null;
        }
        try {
            return (String) o.getClass().getMethod("getName").invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> find(ClassLoader cl) {
        try {
            return Class.forName(UMS, false, cl);
        } catch (Throwable t) {
            Log.w(TAG, "lookup failed: " + t);
            return null;
        }
    }

    static void mark(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
        } catch (Throwable ignored) {
        }
    }
}
