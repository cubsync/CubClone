package com.cubsync.cubclone.hook;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * Flyme 跨用户启动放行 —— 让「我们建的空间」里的应用能打开。
 *
 * <p>Flyme 改写了 {@code ActivityStarter}，启动 Activity 时插了一段多开控制：
 * <pre>
 *   if (multiOpen.isMultiOpenEnabled()) {
 *       intent = multiOpen.checkMultiOpenControl(ctx, aInfo, intent, ...);
 *       aInfo  = multiOpen.resolveMultiOpenIntent(aInfo, intent, ...);
 *   }
 *   if (aInfo == null) return;          // 启动请求就此丢掉
 * </pre>
 * 两处实现都在 {@code com.android.server.multiopen.FlymeMultiOpenServiceInject} 的实现类里
 * （接口只有声明，实现在 Flyme 自己的 jar 中，类名不固定，只能从 {@code get()} 拿实例再挂）。
 *
 * <p>它只认自己的分身空间 {@code FlymeParallelSpace(999)}：日志命中时打
 * "call MultiOpen directly" 并返回改写后的值；我们的 {@code full.SECONDARY} 空间
 * （G7 上 uid=11/12）命中不到，两个方法都返回 null，启动就被静默丢弃 ——
 * 现象是 Splash 一闪、进程都不建（G7 实测，2026-11-25）。8A 是原生系统，没有这层。
 *
 * <p>处理只做一件事：<b>返回 null 时把原参数还回去</b>（Intent / ActivityInfo），
 * 让它按 AOSP 原生路径继续跨用户启动。非 null（Flyme 自己的分身、正常放行）一律不碰。
 */
final class FlymeHook {

    private static final String TAG = "CubCloneHook";
    private static final String INJECT =
            "com.android.server.multiopen.FlymeMultiOpenServiceInject";

    static final String PROP_HITS = "sys.cubclone.hook.flyme.hits";
    static final String PROP_LAST = "sys.cubclone.hook.flyme";

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicBoolean IMPL = new AtomicBoolean();
    private static final AtomicInteger HOOKED = new AtomicInteger();

    private final XposedInterface xp;

    FlymeHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        Class<?> inject;
        try {
            inject = Class.forName(INJECT, false, cl);
        } catch (Throwable t) {
            // 非 Flyme 系统（8A 等）没有这个类，正常跳过
            Log.i(TAG, "no flyme multiopen inject; skip");
            return 0;
        }
        Method get;
        try {
            get = inject.getDeclaredMethod("get");
        } catch (Throwable t) {
            Log.w(TAG, "inject get() not found: " + t);
            return 0;
        }
        // 先直接取一次：服务若已就绪，立刻挂上；没就绪就靠下面的 get() 兜底
        try {
            Object inst = get.invoke(null);
            if (inst != null) {
                hookImpl(inst.getClass());
            }
        } catch (Throwable t) {
            Log.w(TAG, "inject get() call failed: " + t);
        }
        try {
            try {
                xp.deoptimize(get);
            } catch (Throwable ignored) {
            }
            xp.hook(get)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        if (r != null) {
                            hookImpl(r.getClass());
                        }
                        return r;
                    });
            HookMain.mark(PROP_LAST, "get-hooked");
        } catch (Throwable t) {
            Log.w(TAG, "hook inject get() failed: " + t);
        }
        return HOOKED.get();
    }

    /**
     * 参数里是否出现「我们自己的空间」的 uid。
     *
     * <p>两个方法都用 {@code int} 传调用者/目标用户（签名里 check 是 index 4/5/7，
     * resolve 是 5/6/8），但索引随 ROM 版本会变；这里逐个看 int 参数，
     * 命中我们的空间就算——比写死索引稳，也不会误伤 999。
     */
    private static boolean ours(io.github.libxposed.api.XposedInterface.Chain chain) {
        java.util.List<Object> args = chain.getArgs();
        for (int i = 0; i < args.size(); i++) {
            Object a = args.get(i);
            if (a instanceof Integer && WmVisibleHook.ours(((Integer) a).intValue())) {
                return true;
            }
        }
        return false;
    }

    /** 实现类只在第一次拿到时挂一次；后续 get() 再触发直接返回。 */
    private void hookImpl(Class<?> c) {
        if (c == null || !IMPL.compareAndSet(false, true)) {
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            boolean check = "checkMultiOpenControl".equals(name) && m.getParameterTypes().length == 8;
            boolean resolve = "resolveMultiOpenIntent".equals(name) && m.getParameterTypes().length == 9;
            if (!check && !resolve) {
                continue;
            }
            final String mn = name;
            final int origArg = check ? 2 : 0;
            try {
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object orig = chain.getArg(origArg);
                            // 我们自己的空间：Flyme 的改写一律不采纳。
                            // 它把任意 clone profile 都当成自己的多开用户，把启动目标
                            // 改写成 u0，结果 ATS 在 u0 里找不到组件——G7 实测
                            // 「Activity class does not exist」，自建的 clone profile
                            // 因此完全用不了。原参数还回去，走 AOSP 自己的跨用户路径。
                            // 999 不在我们的空间列表里，这一段对它完全无效。
                            if (orig != null && ours(chain)) {
                                int k = HITS.incrementAndGet();
                                if (k == 1) {
                                    Log.i(TAG, "flyme " + mn + " -> keep original (our space)");
                                }
                                HookMain.mark(PROP_HITS, String.valueOf(k));
                                HookMain.mark(PROP_LAST, mn);
                                return orig;
                            }
                            Object r = chain.proceed();
                            if (r != null) {
                                return r;
                            }
                            // null 语义＝Flyme 决定不接管。还回原参数，走 AOSP 原生路径。
                            if (orig != null) {
                                int k = HITS.incrementAndGet();
                                if (k == 1) {
                                    Log.i(TAG, "flyme " + mn + " null -> pass-through");
                                }
                                HookMain.mark(PROP_HITS, String.valueOf(k));
                                HookMain.mark(PROP_LAST, mn);
                            }
                            return orig;
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "hook " + name + " failed: " + t);
            }
        }
        HOOKED.set(n);
        HookMain.mark("sys.cubclone.hook.flyme.apply", "impl=" + c.getName() + ",n=" + n);
        Log.i(TAG, "flyme impl hooked: " + c.getName() + " n=" + n);
    }
}
