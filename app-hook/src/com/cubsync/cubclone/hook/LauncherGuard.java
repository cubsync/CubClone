package com.cubsync.cubclone.hook;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 桌面防护：拦住 ColorOS 建号后往主空间桌面铺默认应用。
 *
 * <p>实测现象（OP12，2026-11-26）：建一个 clone 空间，桌面立刻多出 45 个图标，全是主空间
 * 已安装但没摆上桌面的应用，按包名字母序排列，并多出 2 个空页。
 *
 * <p>根因：ColorOS 桌面收到 clone 用户的包变更后，会对主空间跑一次全量刷新，通过
 * {@code OplusLauncherProvider.applyBatch} 把缺失应用成批插入 {@code singledesktopitems_draw}
 * （45 条），再用一个 5 条的批次加空页。
 *
 * <p>做法：只拦「写桌面表」的批量操作 ——
 * <ul>
 *   <li>条目表：单批 ≥ 20 条才算「全量铺默认布局」，正常桌面编辑和系统分身建图标都是个位数；</li>
 *   <li>页表：紧跟在上面那批之后 2 秒内的写页操作一并吞掉（建号加的空页）。</li>
 * </ul>
 * 其它写入原样放行，不动用户任何正常操作。
 */
final class LauncherGuard {

    private static final String TAG = "CubCloneHook";

    /** 最近一次吞掉「批量补桌面」的时间，用于把紧随其后的加空页一起拦住。 */
    private static volatile long LAST_DROP;

    private final XposedInterface xp;
    private final AtomicInteger hits = new AtomicInteger();

    LauncherGuard(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        int n = 0;
        n += provider(cl, "com.android.launcher3.OplusLauncherProvider");
        n += provider(cl, "com.android.launcher3.LauncherProvider");
        return n;
    }

    private int provider(ClassLoader cl, String cls) {
        Class<?> c;
        try {
            c = Class.forName(cls, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            if (!"applyBatch".equals(name) && !"insert".equals(name)) {
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
                                if ("applyBatch".equals(name)) {
                                    return guardBatch(chain);
                                }
                                return guardInsert(chain);
                            } catch (Throwable t) {
                                return chain.proceed();
                            }
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "guard hook failed: " + cls + "#" + name + " " + t);
            }
        }
        return n;
    }

    private Object guardBatch(XposedInterface.Chain chain) throws Throwable {
        Object last = chain.getArg(chain.getArgs().size() - 1);
        if (!(last instanceof Collection)) {
            return chain.proceed();
        }
        Collection<?> batch = (Collection<?>) last;
        int sz = batch.size();
        boolean desktop = targets(batch, "desktopitems");
        boolean screens = targets(batch, "desktopscreens");
        if (!desktop && !screens) {
            return chain.proceed();
        }
        long now = android.os.SystemClock.elapsedRealtime();
        boolean inWindow = LAST_DROP > 0 && now - LAST_DROP < 2000;
        boolean drop = (desktop && sz >= 20) || (screens && inWindow);
        if (drop) {
            batch.clear();
            if (desktop) {
                LAST_DROP = now;
            }
            mark("batch " + (desktop ? "items" : "screens") + " n=" + sz);
        }
        return chain.proceed();
    }

    private Object guardInsert(XposedInterface.Chain chain) throws Throwable {
        String uri = String.valueOf(chain.getArg(0));
        if (!uri.contains("desktopscreens")) {
            return chain.proceed();
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (LAST_DROP > 0 && now - LAST_DROP < 2000) {
            mark("insert screens");
            return null;
        }
        return chain.proceed();
    }

    private static boolean targets(Collection<?> batch, String key) {
        for (Object op : batch) {
            try {
                Object uri = op.getClass().getMethod("getUri").invoke(op);
                if (uri != null && String.valueOf(uri).contains(key)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void mark(String what) {
        try {
            int k = hits.incrementAndGet();
            HookMain.mark("sys.cubclone.hook.guard", k + ":" + what);
            if (k <= 5) {
                Log.i(TAG, "launcher guard drop " + what);
            }
        } catch (Throwable ignored) {
        }
    }
}
