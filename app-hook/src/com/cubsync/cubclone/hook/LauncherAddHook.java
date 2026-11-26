package com.cubsync.cubclone.hook;

import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 桌面防护：只按「这次动作属于哪个用户」判断，不猜数量、不猜时间。
 *
 * <p>根因（OP12 反编译 + 实测，2026-11-26）：ColorOS 桌面收到包变更后，把 clone 用户的
 * 变更当成主空间的变更重新加载，并把主空间「已装但没上桌面」的应用全铺一遍。链路上的
 * 每一个入口都带着用户身份：
 * <ul>
 *   <li>{@code PackageUpdateReceiverUtil.handlePackageChanged(Context, Intent, UserHandle,
 *       String, LauncherModel, boolean)} —— 第 3 参就是广播的 UserHandle，整条链的总入口；</li>
 *   <li>{@code OplusLauncherModel.onPackageAdded(String, UserHandle)} —— 单包回调；</li>
 *   <li>{@code PackageUpdatedTask.execute()} —— 任务对象上带 {@code mUser}；</li>
 *   <li>{@code onBroadcastIntent(Intent)} —— PROVISIONING 广播带 {@code extra.USER}。</li>
 * </ul>
 * 只要这次动作的用户是我们建的空间，就在入口处不执行；主空间、系统自带分身（MultiApp /
 * system_clone）的用户不是我们的，原样放行。
 *
 * <p>不做「写库时按批次大小/时间窗」这种猜测式拦截：条数少一点就漏，时间窗还会误伤用户
 * 自己的桌面编辑。类不存在（非 ColorOS）就返回 0，整套跳过。
 */
final class LauncherAddHook {

    private static final String TAG = "CubCloneHook";

    /** 总开关：1=开（默认），0=关。主应用在 Root 功能页改，桌面进程重启后生效。 */
    static final String PROP_ENABLE = "persist.sys.cubclone.guard";

    static final String PROP_HITS = "sys.cubclone.hook.lw.hits";
    static final String PROP_POINTS = "sys.cubclone.hook.lw.pts";
    /** 模块进了桌面进程就写。界面靠它区分「桌面根本没加载插件」和「加载了但类没挂上」。 */
    static final String PROP_ALIVE = "sys.cubclone.hook.lw.alive";

    private static final AtomicInteger HITS = new AtomicInteger();

    private final XposedInterface xp;

    LauncherAddHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        // 第一件事就报到：这行能执行，就证明模块真的进了桌面进程。
        // 界面靠它把「桌面没加载插件」和「加载了但一个入口都没挂上（改版）」分开——
        // 这两种情况的处理完全不同，不能都报成「未生效」。
        mark(PROP_ALIVE, "1");
        // 钩子总是挂上，开关在拦截时现读属性 —— 用户在 Root 功能页关掉立刻生效，不用重启桌面。
        int pc = hookPackageChanged(cl);
        int add = hookOnPackageAdded(cl, "com.android.launcher3.OplusLauncherModel");
        if (add == 0) {
            add = hookOnPackageAdded(cl, "com.android.launcher3.LauncherModel");
        }
        int task = hookPackageTask(cl, "com.android.launcher3.model.OplusPackageUpdatedTask");
        task += hookPackageTask(cl, "com.android.launcher3.model.PackageUpdatedTask");
        int prov = hookProvision(cl, "com.android.launcher3.OplusLauncherModel");
        int n = pc + add + task + prov;
        mark(PROP_POINTS, "pc" + pc + ",add" + add + ",task" + task + ",prov" + prov
                + ",on" + (enabled() ? 1 : 0));
        Log.i(TAG, "launcher guard: " + getProp(PROP_POINTS));
        return n;
    }

    /** 属性没设过就是开；只有显式写 0 才关。 */
    static boolean enabled() {
        String v = getProp(PROP_ENABLE);
        return v.length() == 0 || !"0".equals(v);
    }

    /**
     * 总入口。第 3 参是这次包变更所属的用户，属于我们空间就整个方法不执行 ——
     * 后面的派发、加载、写库、内存模型都不会发生。
     */
    private int hookPackageChanged(ClassLoader cl) {
        Class<?> c;
        try {
            c = Class.forName("com.android.launcher.receiver.PackageUpdateReceiverUtil", false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"handlePackageChanged".equals(m.getName())) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != 6 || pt[2] != UserHandle.class) {
                continue;
            }
            if (hookIdentity(m, 2, "pkgchange", 3)) {
                n++;
            }
        }
        return n;
    }

    /** 单包回调：user 是我们的空间就不进模型。 */
    private int hookOnPackageAdded(ClassLoader cl, String cls) {
        Class<?> c;
        try {
            c = Class.forName(cls, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"onPackageAdded".equals(m.getName())) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length < 2 || pt[1] != UserHandle.class) {
                continue;
            }
            if (hookIdentity(m, 1, "onPackageAdded", 0)) {
                n++;
            }
        }
        return n;
    }

    /** 建号时系统给每个应用派一个任务，任务对象上带 mUser。是我们的就不执行。 */
    private int hookPackageTask(ClassLoader cl, String cls) {
        Class<?> c;
        try {
            c = Class.forName(cls, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"execute".equals(m.getName())) {
                continue;
            }
            // 这个类没有 UserHandle 参数，用户身份在 mUser 字段上（父类），按字段取
            if (hookUserField(m, "task")) {
                n++;
            }
        }
        return n;
    }

    /** PROVISIONING 广播：只有 profile 创建/配置成功时发，带 extra.USER。 */
    private int hookProvision(ClassLoader cl, String cls) {
        Class<?> c;
        try {
            c = Class.forName(cls, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"onBroadcastIntent".equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            try {
                m.setAccessible(true);
                deopt(m);
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!enabled()) {
                                return chain.proceed();
                            }
                            try {
                                Object arg = chain.getArg(0);
                                String action = actionOf(arg);
                                if ("android.app.action.PROVISIONING_SUCCESSFUL".equals(action)
                                        || "android.app.action.MANAGED_PROFILE_PROVISIONED".equals(action)) {
                                    int uid = uidOf(extraUser(arg));
                                    if (ours(uid)) {
                                        hit("provision", action, uid);
                                        return null;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "provision hook failed: " + t);
            }
        }
        return n;
    }

    /** 参数里带 UserHandle 的方法：统一挂「属于我们空间就整体不执行」。 */
    private boolean hookIdentity(Method m, int userArg, String where, int logArg) {
        try {
            m.setAccessible(true);
            deopt(m);
            xp.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!enabled()) {
                            return chain.proceed();
                        }
                        int uid = uidOf(chain.getArg(userArg));
                        if (ours(uid)) {
                            hit(where, logArg >= 0 ? String.valueOf(chain.getArg(logArg)) : "", uid);
                            return null;
                        }
                        return chain.proceed();
                    });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, where + " hook failed: " + m + " " + t);
            return false;
        }
    }

    /** 用户身份在字段上的方法（PackageUpdatedTask.mUser）。 */
    private boolean hookUserField(Method m, String where) {
        try {
            m.setAccessible(true);
            deopt(m);
            xp.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!enabled()) {
                            return chain.proceed();
                        }
                        int uid = fieldUid(chain.getThisObject(), "mUser");
                        if (ours(uid)) {
                            hit(where, chain.getThisObject().getClass().getSimpleName(), uid);
                            return null;
                        }
                        return chain.proceed();
                    });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, where + " hook failed: " + m + " " + t);
            return false;
        }
    }

    private void deopt(Method m) {
        try {
            xp.deoptimize(m);
        } catch (Throwable ignored) {
        }
    }

    private static boolean ours(int uid) {
        return uid > 0 && WmVisibleHook.ours(uid, appCtx());
    }

    private static String actionOf(Object intent) {
        try {
            return (String) intent.getClass().getMethod("getAction").invoke(intent);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code android.intent.extra.USER} 是 UserHandle，@SystemApi，编译桩里没有。 */
    private static Object extraUser(Object intent) {
        try {
            Method g = intent.getClass().getMethod("getParcelableExtra", String.class);
            return g.invoke(intent, "android.intent.extra.USER");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int fieldUid(Object obj, String name) {
        if (obj == null) {
            return 0;
        }
        for (Class<?> k = obj.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return uidOf(f.get(obj));
            } catch (NoSuchFieldException ignored) {
                // 继续找父类
            } catch (Throwable t) {
                return 0;
            }
        }
        return 0;
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

    private static void hit(String where, String what, int uid) {
        try {
            int k = HITS.incrementAndGet();
            mark(PROP_HITS, String.valueOf(k));
            Log.i(TAG, "launcher drop " + where + " " + what + " user=" + uid);
        } catch (Throwable ignored) {
        }
    }

    private static Context appCtx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v == null ? "" : ((String) v).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 属性名统一由 HookMain.mark 写，保持一处分发。 */
    private static void mark(String key, String value) {
        HookMain.mark(key, value);
    }
}
