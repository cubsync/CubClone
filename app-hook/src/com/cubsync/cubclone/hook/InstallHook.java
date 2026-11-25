package com.cubsync.cubclone.hook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 安装拦截：把「没指定用户」的安装收敛到 user 0。
 *
 * <p>实测（8A，6 个 CLONE 空间）：{@code pm install --user 0} 装完只有主空间可见；
 * 不带 {@code --user} 则 7 个用户全可见 —— AOSP 对「未指定用户」是按
 * {@code UserHandle.USER_ALL} 分发的。所以在安装会话建立时把目标用户钉成 0 即可，
 * 不需要装完再逐个空间卸载（那样会闪装闪卸、触发包广播）。
 *
 * <p>拦截点选 {@code PackageInstallerService.createSession}：adb、系统安装器、
 * 三方商店都走 PackageInstaller，一处覆盖全部；另在
 * {@code PackageManagerService.installPackageAsUser} 兜底改 userId。
 *
 * <p>只处理两种「未指定」表达：{@code userId == USER_ALL(-1)} 或
 * {@code installFlags} 带 {@code INSTALL_ALL_USERS(0x40)}。显式指定 0..N 的安装
 * （包括本应用「装到空间 N」的路径）一律不动。全部 try/catch，hook 失败就放行原参数。
 *
 * <p>开关：{@code persist.sys.cubclone.hook.install}，缺省 1（开）。每次拦截现读，
 * 所以主应用里关掉/打开立即生效，不需要重启（钩子本身总是挂着）。
 */
final class InstallHook {

    private static final String TAG = "CubCloneHook";
    private static final int USER_ALL = -1;
    private static final int INSTALL_ALL_USERS = 0x00000040;

    static final String PROP_ON = "persist.sys.cubclone.hook.install";
    static final String PROP_HITS = "sys.cubclone.hook.install.hits";
    static final String PROP_LAST = "sys.cubclone.hook.install.last";

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger CTOR = new AtomicInteger();
    private final XposedInterface xp;

    InstallHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        // 开关在每次拦截时读（见 gate），所以这里**总是**挂钩子：
        // 关掉再打开不需要重启，代价只是一次 SystemProperties 读取（安装本来就不频繁）。
        Log.i(TAG, "install hook on=" + on());
        int a = hookSessions(cl);
        int b = hookInstallAsUser(cl);
        int c = hookSessionCtor(cl);
        int d = hookSessionCommit(cl);
        dumpFields(cl);
        Log.i(TAG, "install hook: sessions=" + a + ", pms=" + b + ", ctor=" + c + ", commit=" + d);
        HookMain.mark("sys.cubclone.hook.install.apply",
                "s=" + a + ",p=" + b + ",c=" + c + ",k=" + d + ",on=" + (on() ? 1 : 0));
        return a + b + c + d;
    }

    // ───────── ① 会话层：所有安装都从这里建会话 ─────────

    private int hookSessions(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> c = Class.forName("com.android.server.pm.PackageInstallerService", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"createSession".equals(m.getName())) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 0
                        || !"android.content.pm.PackageInstaller$SessionParams".equals(pt[0].getName())) {
                    continue;
                }
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                try {
                    xp.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                if (!gate()) {
                                    return chain.proceed();
                                }
                                rewriteParams(chain.getArg(0));
                                // 老签名把目标用户放在参数里（-1 = 所有用户）
                                List<Object> args = chain.getArgs();
                                for (int i = 1; i < args.size(); i++) {
                                    Object v = args.get(i);
                                    if (v instanceof Integer
                                            && ((Integer) v).intValue() == USER_ALL) {
                                        Object[] copy = args.toArray(new Object[0]);
                                        copy[i] = Integer.valueOf(0);
                                        hit("createSession arg" + i);
                                        return chain.proceed(copy);
                                    }
                                }
                                return chain.proceed();
                            });
                    n++;
                } catch (Throwable t) {
                    Log.w(TAG, "hook createSession failed: " + m + " / " + t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "PackageInstallerService unavailable: " + t);
        }
        return n;
    }

    /** 
     * 先看 A13/A14 的 SessionParams.userId（A16 已删除该字段），
     * 再看所有版本都有的 installFlags.INSTALL_ALL_USERS。
     */
    private static void rewriteParams(Object p) {
        if (p == null) {
            return;
        }
        try {
            Field uf = p.getClass().getDeclaredField("userId");
            uf.setAccessible(true);
            int uid = uf.getInt(p);
            if (uid == USER_ALL) {
                uf.setInt(p, 0);
                hit("params user=-1");
            }
        } catch (Throwable ignored) {
            // A16 没有这个字段，正常
        }
        try {
            Field ff = p.getClass().getDeclaredField("installFlags");
            ff.setAccessible(true);
            int flags = ff.getInt(p);
            HookMain.mark("sys.cubclone.hook.install.seen",
                    "f=0x" + Integer.toHexString(flags));
            if ((flags & INSTALL_ALL_USERS) != 0) {
                ff.setInt(p, flags & ~INSTALL_ALL_USERS);
                hit("flags 0x" + Integer.toHexString(flags));
            }
        } catch (Throwable t) {
            Log.w(TAG, "rewrite SessionParams failed: " + t);
        }
    }

    // ───────── ② 兜底层：直接调 PMS 的安装入口 ─────────

    private int hookInstallAsUser(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> c = Class.forName("com.android.server.pm.PackageManagerService", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"installPackageAsUser".equals(m.getName())) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                int lastInt = -1;
                for (int i = 0; i < pt.length; i++) {
                    if (pt[i] == int.class) {
                        lastInt = i;
                    }
                }
                if (lastInt < 0) {
                    continue;   // 新版本用参数对象，交给会话层
                }
                final int ui = lastInt;
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                try {
                    xp.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                if (!gate()) {
                                    return chain.proceed();
                                }
                                List<Object> args = chain.getArgs();
                                Object cur = args.get(ui);
                                if (cur instanceof Integer
                                        && ((Integer) cur).intValue() == USER_ALL) {
                                    Object[] copy = args.toArray(new Object[0]);
                                    copy[ui] = Integer.valueOf(0);
                                    hit("pms uid=-1");
                                    return chain.proceed(copy);
                                }
                                return chain.proceed();
                            });
                    n++;
                } catch (Throwable t) {
                    Log.w(TAG, "hook installPackageAsUser failed: " + m + " / " + t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "PackageManagerService unavailable: " + t);
        }
        return n;
    }

    // ───────── ③ session 对象：兜住不走 createSession 的路径 ─────────

    private int hookSessionCtor(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> c = Class.forName("com.android.server.pm.PackageInstallerSession", false, cl);
            for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                try {
                    xp.hook(ctor)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object r = chain.proceed();
                                try {
                                    forceUser(chain.getThisObject());
                                } catch (Throwable t) {
                                    Log.w(TAG, "forceUser failed: " + t);
                                }
                                return r;
                            });
                    n++;
                } catch (Throwable t) {
                    Log.w(TAG, "hook session ctor failed: " + ctor + " / " + t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "PackageInstallerSession unavailable: " + t);
        }
        return n;
    }

    /**
     * commit / open 是从 binder 线程进来的（IPackageInstallerSession.Stub 虚分派），
     * 不会被 AOT 内联 —— 构造器和方法 hook 在 A16 实测都挂上不触发，只能靠这里。
     */
    private int hookSessionCommit(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> c = Class.forName("com.android.server.pm.PackageInstallerSession", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (!"commit".equals(name) && !"open".equals(name) && !"transfer".equals(name)) {
                    continue;
                }
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                try {
                    xp.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    forceUser(chain.getThisObject());
                                } catch (Throwable t) {
                                    Log.w(TAG, "forceUser at " + m.getName() + " failed: " + t);
                                }
                                return chain.proceed();
                            });
                    n++;
                } catch (Throwable t) {
                    Log.w(TAG, "hook " + name + " failed: " + t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "PackageInstallerSession (commit) unavailable: " + t);
        }
        return n;
    }

    /** session 里的 userId 只要还是「所有用户」就钉到 0（A16 字段名是 userId，不是 mUserId） */
    private static void forceUser(Object session) throws Exception {
        if (!gate()) {
            return;
        }
        Class<?> c = session.getClass();
        Field uf = null;
        for (String n : new String[]{"userId", "mUserId"}) {
            try {
                uf = c.getDeclaredField(n);
                break;
            } catch (Throwable ignored) {
            }
        }
        if (uf == null) {
            return;
        }
        uf.setAccessible(true);
        int uid = uf.getInt(session);
        HookMain.mark("sys.cubclone.hook.install.ctor", String.valueOf(CTOR.incrementAndGet()));
        // 先看 params 的 flags（userId 可能已被解析成具体值，flags 仍带 ALL_USERS）
        try {
            Field pf = c.getDeclaredField("params");
            pf.setAccessible(true);
            rewriteParams(pf.get(session));
        } catch (Throwable ignored) {
        }
        if (uid != USER_ALL) {
            return;   // 显式指定了用户（含 0..N）一律不动
        }
        try {
            uf.setInt(session, 0);
            hit("session user=-1");
        } catch (Throwable t) {
            Log.w(TAG, "userId is final? " + t);
        }
    }

    /** 诊断：把两个类的字段名打到日志（A16 改过字段名，写死会踩空） */
    private static void dumpFields(ClassLoader cl) {
        for (String cn : new String[]{
                "android.content.pm.PackageInstaller$SessionParams",
                "com.android.server.pm.PackageInstallerSession"}) {
            try {
                Class<?> c = Class.forName(cn, false, cl);
                StringBuilder sb = new StringBuilder();
                for (Field f : c.getDeclaredFields()) {
                    sb.append(f.getName()).append(' ');
                }
                Log.i(TAG, "fields[" + cn + "]: " + sb);
            } catch (Throwable t) {
                Log.w(TAG, "dump " + cn + " failed: " + t);
            }
        }
    }

    /**
     * 开关：{@code persist.sys.cubclone.hook.install}，缺省 1（开）。
     * 每次拦截都读一次，改开关立即生效；读不到按开处理（宁可按目标行为）。
     */
    private static boolean gate() {
        return on();
    }

    private static boolean on() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("getInt", String.class, int.class);
            return ((Integer) get.invoke(null, PROP_ON, Integer.valueOf(1))).intValue() != 0;
        } catch (Throwable t) {
            return true;   // 读不到就按「开」走，宁可按目标行为
        }
    }

    private static void hit(String detail) {
        int k = HITS.incrementAndGet();
        HookMain.mark(PROP_HITS, String.valueOf(k));
        HookMain.mark(PROP_LAST, detail);
        if (k == 1) {
            Log.i(TAG, "install rewrite #1 " + detail);
        }
    }
}
