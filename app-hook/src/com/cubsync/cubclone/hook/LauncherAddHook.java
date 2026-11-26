package com.cubsync.cubclone.hook;

import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 拦 launcher 把「我们建的空间」里的应用补到主空间桌面。
 *
 * <p>现象（OP12 实测 2026-11-26）：建一个 clone 空间，桌面立刻多出 44 个图标，全是主空间
 * 已安装但没摆上桌面的应用，按包名字母序铺满两页；删号只撤掉 1 个（"文件"），其余留在桌面。
 *
 * <p>根因：ColorOS 桌面收到 PACKAGE_ADDED 后走
 * {@code OplusLauncherModel.onPackageAdded(pkg, user)}，对非当前用户一律按「应用分身」
 * 处理，最终把条目写进 workspace。AOSP 同位置是 {@code ManagedProfileHeuristic}：
 * 同样会自动加，但记的是 profile 归属，删号会跟着删；ColorOS 记成主空间，所以删不掉。
 *
 * <p>做法两层，都只在「user 属于我们的空间」时生效，正常主空间装应用完全不受影响：
 * <ol>
 *   <li><b>源头</b>：{@code onPackageAdded} 的 user 是我们的 space → 直接不入模型；</li>
 *   <li><b>兜底</b>：{@code addAndBindAddedWorkspaceItems} 里 item.user 是我們的 space
 *       → 从待写列表剔除（防别的路径绕过第一层）。</li>
 * </ol>
 * 类/方法不存在（非 ColorOS、非 Launcher3）就返回 0，整套跳过，不产生任何副作用。
 */
final class LauncherAddHook {

    private static final String TAG = "CubCloneHook";
    private static final String OPLUS_MODEL = "com.android.launcher3.OplusLauncherModel";
    private static final String MODEL = "com.android.launcher3.LauncherModel";

    static final String PROP_HITS = "sys.cubclone.hook.lw.hits";

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger Q = new AtomicInteger();

    private final XposedInterface xp;

    LauncherAddHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        int n = 0;
        // OplusLauncherModel 是 ColorOS 的入口；AOSP Launcher3 没有这个类，自动落到下面的
        // LauncherModel 同名方法（AOSP 不会加桌面，钩上只是无害的 no-op）。
        n += hookOnPackageAdded(cl, OPLUS_MODEL);
        if (n == 0) {
            n += hookOnPackageAdded(cl, MODEL);
        }
        n += hookProvision(cl, OPLUS_MODEL);
        // 真正的源头：ColorOS 桌面收到包变更广播后，会在 PackageUpdateReceiverUtil
        // .handlePackageChanged 里对「主空间所有包」派 OplusPackageUpdatedTask，
        // 把没在桌面的应用全铺上去（实测 45 个）。第 3 个参数就是广播的 UserHandle，
        // 是我们建的空间就直接不处理 —— 主空间、系统分身（MultiApp）原样。
        n += hookPackageChanged(cl);
        // 建号时 ColorOS 给每个应用派 OplusPackageUpdatedTask（子类重写了 execute，
        // 钩父类 PackageUpdatedTask.execute 不触发）。两个类都要挂。
        n += hookPackageTask(cl, "com.android.launcher3.model.OplusPackageUpdatedTask");
        n += hookPackageTask(cl, "com.android.launcher3.model.PackageUpdatedTask");
        n += hookAddItems(cl, MODEL);
        n += hookProvider(cl, "com.android.launcher3.LauncherProvider");
        n += hookProvider(cl, "com.android.launcher3.OplusLauncherProvider");
        n += hookPackageTask(cl, "com.android.launcher3.model.OplusPackageUpdatedTask");
        n += hookPackageTask(cl, "com.android.launcher3.model.PackageUpdatedTask");
        Log.i(TAG, "lw: hooks=" + n);
        return n;
    }

    /**
     * 真正收口：建号时 ColorOS 会给 profile 里的每个应用派一个 PackageUpdatedTask，
     * 它的 mUser 就是那个 profile。属于我们的空间就整个任务不执行 —— 条目从源头不产生。
     */
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
            try {
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            int uid = fieldUid(chain.getThisObject(), "mUser");
                            int pkgs = fieldSize(chain.getThisObject(), "mPackages");
                            Log.i(TAG, "lw: texec " + chain.getThisObject().getClass().getSimpleName()
                                    + " user=" + uid + " pkgs=" + pkgs
                                    + " ours=" + (uid > 0 && WmVisibleHook.ours(uid, appCtx())));
                            if (uid > 0 && WmVisibleHook.ours(uid, appCtx())) {
                                hit("task", "execute", uid);
                                return null;
                            }
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lw task exec hook failed: " + m + " " + t);
            }
        }
        return n;
    }

    /**
     * 源头：ColorOS 桌面收到 {@code MANAGED_PROFILE_PROVISIONED} / {@code PROVISIONING_SUCCESSFUL}
     * 广播后，会 {@code UserCache.enableAndResetCache()} + {@code ProvisionManager.onManagedStateChange()}
     * 重建桌面（实测：建 clone 空间后补 45 条主空间应用到桌面）。
     *
     * <p>这两个 action 只在 profile 创建/配置成功时发出，正常使用不会收到。这里只拦
     * 「广播里带的 user 属于我们空间」的那一次，原生分身（{@code MultiApp}）和工作资料照旧。
     */
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
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                Object arg = chain.getArg(0);
                                String action = actionOf(arg);
                                if ("android.app.action.PROVISIONING_SUCCESSFUL".equals(action)
                                        || "android.app.action.MANAGED_PROFILE_PROVISIONED".equals(action)) {
                                    Object uh = extraUser(arg);
                                    int uid = uidOf(uh);
                                    Log.i(TAG, "lw: provision " + action + " user=" + uid);
                                    if (uid > 0 && WmVisibleHook.ours(uid, appCtx())) {
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
                Log.w(TAG, "lw provision hook failed: " + t);
            }
        }
        return n;
    }

    /**
     * 最源头：launcher 每次初始化/刷新 UserCache 都会问 UserManager 要用户列表。
     * 这里把我们的空间摘掉，launcher 就看不到「多了一个用户」，不会触发任何重建。
     *
     * <p>只过滤 uid 属于我们空间的条目；主空间、原生分身（MultiApp）原样返回。
     * 拿不到 UserHandle 的条目一律保留 —— 宁可漏过滤也不误删。
     */
    private int hookUserList(ClassLoader cl) {
        Class<?> c;
        try {
            c = Class.forName("android.os.UserManager", false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            if (!"getAliveUsers".equals(name) && !"getUsers".equals(name)
                    && !"getUserProfiles".equals(name) && !"getEnabledProfiles".equals(name)) {
                continue;
            }
            if (!List.class.isAssignableFrom(m.getReturnType())) {
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
                            if (!(r instanceof List)) {
                                return r;
                            }
                            List<?> in = (List<?>) r;
                            List<Object> out = new java.util.ArrayList<>(in.size());
                            int dropped = 0;
                            for (Object o : in) {
                                int uid = uidOf(o);
                                if (uid > 0 && WmVisibleHook.ours(uid, appCtx())) {
                                    dropped++;
                                } else {
                                    out.add(o);
                                }
                            }
                            if (dropped == 0) {
                                return r;
                            }
                            Log.i(TAG, "lw: userlist " + name + " " + in.size() + "->" + out.size());
                            hit("userlist", name + " " + dropped, 0);
                            return out;
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lw userlist hook failed: " + name + " " + t);
            }
        }
        return n;
    }

    private static String actionOf(Object intent) {
        try {
            return (String) intent.getClass().getMethod("getAction").invoke(intent);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code android.intent.extra.USER} 是 UserHandle，@SystemApi，编译桩里没有，反射取。 */
    private static Object extraUser(Object intent) {
        try {
            Method g = intent.getClass().getMethod("getParcelableExtra", String.class);
            return g.invoke(intent, "android.intent.extra.USER");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 源头拦截：{@code PackageUpdateReceiverUtil.handlePackageChanged}。
     *
     * <p>签名 {@code (Context, Intent, UserHandle, String, LauncherModel, boolean)}，第 3 个
     * 参数就是这次包变更所属的用户。建号时系统会为 clone 用户补装应用，广播打过来后
     * ColorOS 不区分用户，直接对主空间跑全量更新（实测 45 个应用被铺到桌面）。
     * 广播的 user 属于我们的空间就直接跳过，后面的派发、写库、内存模型都不会发生。
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
            try {
                m.setAccessible(true);
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            int uid = uidOf(chain.getArg(2));
                            if (uid > 0 && WmVisibleHook.ours(uid, appCtx())) {
                                hit("pkgchange", String.valueOf(chain.getArg(3)), uid);
                                return null;
                            }
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lw pkgchange hook failed: " + t);
            }
        }
        return n;
    }

    /**
     * 桌面库已有多少条。用 Provider 自己的连接读（同一线程、同一连接，不开新连接）。
     * 返回 -1 表示读不到 —— 调用方按「空库」处理（保守放行，不影响正常初始化）。
     */
    private static int tableRows(Object provider) {
        try {
            Object helper = null;
            for (Class<?> k = provider.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    Field f = k.getDeclaredField("mOpenHelper");
                    f.setAccessible(true);
                    helper = f.get(provider);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (helper == null) {
                return -1;
            }
            Object db = helper.getClass().getMethod("getReadableDatabase").invoke(helper);
            if (db == null) {
                return -1;
            }
            int total = 0;
            for (String t : new String[]{"singledesktopitems_draw", "singledesktopitems"}) {
                Object c = null;
                try {
                    Method raw = db.getClass().getMethod("rawQuery", String.class, String[].class);
                    c = raw.invoke(db, "select count(*) from " + t, null);
                    Method first = c.getClass().getMethod("moveToFirst");
                    if (Boolean.TRUE.equals(first.invoke(c))) {
                        total += (Integer) c.getClass().getMethod("getInt", int.class).invoke(c, 0);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) {
                        try {
                            c.getClass().getMethod("close").invoke(c);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            return total;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 沿继承链找集合/数组字段的大小（mPackages 在父类 PackageUpdatedTask 上）。 */
    private static int fieldSize(Object obj, String name) {
        if (obj == null) {
            return -1;
        }
        for (Class<?> k = obj.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof java.util.Collection) {
                    return ((java.util.Collection<?>) v).size();
                }
                if (v instanceof Object[]) {
                    return ((Object[]) v).length;
                }
                return -1;
            } catch (NoSuchFieldException ignored) {
                // 继续找父类
            } catch (Throwable t) {
                return -1;
            }
        }
        return -1;
    }

    /** 沿继承链找 UserHandle 字段（mUser 在父类 PackageUpdatedTask 上）。 */
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

    /** 终极探针：直接钩框架的 SQLiteDatabase.insert，只看桌面表，抓真实调用栈。 */
    private int hookSqlite() {
        try {
            Class<?> c = Class.forName("android.database.sqlite.SQLiteDatabase");
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if ("query".equals(name)) {
                    m.setAccessible(true);
                    try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                    xp.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    if (Q.getAndIncrement() < 3) {
                                        Log.i(TAG, "lp: query-probe arg0=" + chain.getArg(0));
                                    }
                                } catch (Throwable ignored) {
                                }
                                return chain.proceed();
                            });
                    n++;
                    continue;
                }
                if ("execSQL".equals(name)) {
                    m.setAccessible(true);
                    try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                    xp.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    Object sql = chain.getArg(0);
                                    if (sql instanceof String && ((String) sql).contains("desktopitems")) {
                                        String s = (String) sql;
                                        Log.i(TAG, "lp: execSQL " + s.substring(0, Math.min(180, s.length())));
                                        Log.i(TAG, "lp: estack=" + stack());
                                    }
                                } catch (Throwable ignored) {
                                }
                                return chain.proceed();
                            });
                    n++;
                    continue;
                }
                if (!"insert".equals(name) && !"insertWithOnConflict".equals(name)
                        && !"insertOrThrow".equals(name) && !"replace".equals(name)) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 3 || pt[0] != String.class) {
                    continue;
                }
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                Object t = chain.getArg(0);
                                if (t instanceof String && ((String) t).contains("desktopitems")) {
                                    Log.i(TAG, "lp: sqlite " + t + " v=" + chain.getArg(2));
                                    Log.i(TAG, "lp: sstack=" + stack());
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                n++;
            }
            return n;
        } catch (Throwable t) {
            Log.w(TAG, "lw sqlite hook: " + t);
            return 0;
        }
    }

    /** 终极探针 2：SQLiteStatement.executeInsert（所有插入的最后一站）。 */
    private int hookStmt() {
        try {
            Class<?> c = Class.forName("android.database.sqlite.SQLiteStatement");
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!"executeInsert".equals(m.getName())) {
                    continue;
                }
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                Object sql = null;
                                try {
                                    Field f = c.getDeclaredField("mSql");
                                    f.setAccessible(true);
                                    sql = f.get(chain.getThisObject());
                                } catch (Throwable ignored) {
                                }
                                if (sql instanceof String && ((String) sql).contains("desktopitems")) {
                                    Log.i(TAG, "lp: stmt " + sql);
                                    Log.i(TAG, "lp: stmtstack=" + stack());
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }
    private int hookDbTracker(ClassLoader cl) {
        Class<?> c;
        try {
            c = Class.forName("com.android.launcher.db.DbTracker", false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"insert".equals(m.getName()) || m.getParameterTypes().length < 5) {
                continue;
            }
            try {
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                Log.i(TAG, "lp: dbT a0=" + chain.getArg(0)
                                        + " a2=" + chain.getArg(2)
                                        + " v=" + chain.getArg(4));
                                Log.i(TAG, "lp: tstack=" + stack());
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lw dbt hook failed: " + m + " " + t);
            }
        }
        return n;
    }

    /** 探针：AutoInstallsLayout 直接用 SQLiteDatabase 写，绕开 Provider；这里抓它的调用栈。 */
    private int hookAutoInstall(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> c = Class.forName("com.android.launcher3.AutoInstallsLayout", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"loadLayout".equals(m.getName())) {
                    continue;
                }
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                Log.i(TAG, "lp: loadLayout stack=" + stack());
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                n++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "lw auto hook: " + t);
        }
        try {
            Class<?> c = Class.forName("com.android.launcher3.LauncherProvider$DatabaseHelper", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"loadFavorites".equals(m.getName())) {
                    continue;
                }
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                Log.i(TAG, "lp: loadFavorites stack=" + stack());
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                n++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "lw favorites hook: " + t);
        }
        return n;
    }

    /** 探针：所有写库最终都走 dbInsertAndCheck（静态），在这里抓 values 与调用栈。 */
    private int hookDbInsert(ClassLoader cl, String cls) {
        Class<?> c;
        try {
            c = Class.forName(cls, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"dbInsertAndCheck".equals(m.getName()) || m.getParameterTypes().length < 5) {
                continue;
            }
            try {
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                Object table = chain.getArg(2);
                                Object values = chain.getArg(4);
                                Log.i(TAG, "lp: dbInsert table=" + table + " v=" + values);
                                Log.i(TAG, "lp: dstack=" + stack());
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lw db hook failed: " + m + " " + t);
            }
        }
        return n;
    }

    /** 探针：记录所有模型更新任务，看建号时 launcher 到底收到哪些 task。 */
    private int hookTasks(ClassLoader cl, String cls) {
        Class<?> c;
        try {
            c = Class.forName(cls, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"enqueueModelUpdateTask".equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            try {
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                Object t = chain.getArg(0);
                                Log.i(TAG, "lw: task " + (t == null ? "null" : t.getClass().getName()));
                                Log.i(TAG, "lw: tstack=" + stack());
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lw task hook failed: " + m + " " + t);
            }
        }
        return n;
    }

    /** 探针：记录桌面一次写库的入口与调用栈，用来定位「建号后补 44 条」的真实路径。 */
    private int hookProvider(ClassLoader cl, String cls) {
        Class<?> c;
        try {
            c = Class.forName(cls, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            if ("call".equals(name) && m.getParameterTypes().length == 3) {
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                String method = String.valueOf(chain.getArg(0));
                                if ("load_default_favorites".equals(method)) {
                                    // AOSP 只在 mIsNewDb 时铺默认布局，ColorOS 每次 loadWorkspace 都调。
                                    // 库里已有条目就跳过 —— 建号时不再补 84 条。
                                    int rows = tableRows(chain.getThisObject());
                                    Log.i(TAG, "lw: defaults rows=" + rows);
                                    if (rows > 0) {
                                        hit("defaults", "rows=" + rows, 0);
                                        return null;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                n++;
                continue;
            }
            boolean batch = "applyBatch".equals(name);
            // insert/applyBatch 由 LauncherGuard 统一处理，这里只管 call。
        }
        return n;
    }

    private static String stack() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < st.length && i <= 12; i++) {
                sb.append(st[i].getClassName()).append('.').append(st[i].getMethodName())
                        .append(':').append(st[i].getLineNumber()).append(" <- ");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "-";
        }
    }

    /** ① 源头：非当前用户的应用进来时，若 user 是我们的空间，直接不执行原方法。 */
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
            try {
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            int uid = uidOf(chain.getArg(1));
                            if (uid > 0 && WmVisibleHook.ours(uid, appCtx())) {
                                hit("onPackageAdded", String.valueOf(chain.getArg(0)), uid);
                                return null;
                            }
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lw hook failed: " + m + " " + t);
            }
        }
        return n;
    }

    /** ② 兜底：写库前把属于我们空间的 item 剔掉；剔空了就不落库。 */
    private int hookAddItems(ClassLoader cl, String cls) {
        Class<?> c;
        try {
            c = Class.forName(cls, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"addAndBindAddedWorkspaceItems".equals(m.getName())) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length < 1 || !List.class.isAssignableFrom(pt[0])) {
                continue;
            }
            try {
                m.setAccessible(true);
                try { xp.deoptimize(m); } catch (Throwable ignored2) { }
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object arg = chain.getArg(0);
                            if (arg instanceof List) {
                                List<?> list = (List<?>) arg;
                                int before = list.size();
                                int dropped = 0;
                                Log.i(TAG, "lw: addItems n=" + before + " uid0="
                                        + (before > 0 ? itemUid(list.get(0)) : -1));
                                try {
                                    for (Iterator<?> it = list.iterator(); it.hasNext(); ) {
                                        Object o = it.next();
                                        int uid = itemUid(o);
                                        if (uid > 0 && WmVisibleHook.ours(uid, appCtx())) {
                                            it.remove();
                                            dropped++;
                                        }
                                    }
                                } catch (Throwable t) {
                                    Log.w(TAG, "lw filter failed: " + t);
                                }
                                if (dropped > 0) {
                                    hit("addItems", before + "->" + (before - dropped), 0);
                                }
                                if (list.isEmpty()) {
                                    return null;
                                }
                            }
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "lw hook failed: " + m + " " + t);
            }
        }
        return n;
    }

    /** item 可能是 Pair&lt;ItemInfo, Object&gt;，也可能直接是 ItemInfo。 */
    private static int itemUid(Object o) {
        try {
            Object info = o;
            if (o instanceof android.util.Pair) {
                info = ((android.util.Pair<?, ?>) o).first;
            }
            if (info == null) {
                return 0;
            }
            Field f = info.getClass().getField("user");
            Object v = f.get(info);
            return uidOf(v);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static volatile Method UID_OF;

    /** UserHandle.getIdentifier() 是 @SystemApi，编译桩里没有，只能反射。 */
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
            HookMain.mark(PROP_HITS, String.valueOf(k));
            Log.i(TAG, "lw: drop " + where + " " + what + (uid > 0 ? " user=" + uid : ""));
        } catch (Throwable ignored) {
        }
    }

    /** launcher 进程里的 Application；拿不到就只靠属性判断（ours 内部有 fallback）。 */
    private static Context appCtx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
