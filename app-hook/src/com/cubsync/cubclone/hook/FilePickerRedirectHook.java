package com.cubsync.cubclone.hook;

import android.content.Intent;
import android.os.Binder;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 让空间里的应用发起「选文件」时，直接用主用户那份文件选择器。
 *
 * <p>为什么必须换用户：文件选择器的内容来自它所在用户的存储视图。自建空间的
 * {@code /storage/emulated/<uid>} 在 sdcardfs 机型上拿不到挂载点，选择器里必然是
 * 「无任何文件」；换到主用户启动，看到的就是主空间真实的相册和下载。
 *
 * <p>入口是 AOSP 的 {@code ActivityTaskManagerService.startActivityAsUser(...)}：
 * 所有带 {@code --user} / 指定用户的 Activity 启动都从这里过，最后两个参数是
 * {@code int userId, boolean validate}。只改「调用者是我们空间、目标也是这个空间、
 * intent 是文件选择类」的那一种，其它一律原样放行。
 *
 * <p>选完的结果由 {@code resultTo} 的 token 回传——token 是全局的，跨用户能找回
 * 发起方的 ActivityRecord，所以空间应用能正常收到 URI。
 */
final class FilePickerRedirectHook {

    private static final String TAG = "CubCloneHook";
    static final String PROP = "sys.cubclone.hook.pick";

    private static final String ATM = "com.android.server.wm.ActivityTaskManagerService";
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger FIRST = new AtomicInteger();

    private final XposedInterface xp;

    FilePickerRedirectHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        Class<?> c;
        try {
            c = Class.forName(ATM, false, cl);
        } catch (Throwable t) {
            HookMain.mark(PROP, "no-class");
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"startActivityAsUser".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            // 不绑死签名：只要参数里有 Intent、且 Intent 之后还有 int，就当候选。
            // userId 的具体位置在运行期按「值命中我们空间」判定，命中后锁定。
            int intentAt = indexOf(p, Intent.class);
            if (intentAt < 0) continue;
            int[] cand = intsAfter(p, intentAt);
            if (cand.length == 0) continue;
            final int intentIdx = intentAt;
            final int[] cands = cand;
            try {
                xp.deoptimize(m);
            } catch (Throwable ignored) {
            }
            try {
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object[] a = chain.getArgs().toArray();
                            if (a.length > intentIdx && a[intentIdx] instanceof Intent
                                    && isFilePicker((Intent) a[intentIdx])) {
                                int at = userIdIndex(a, cands);
                                if (at >= 0) {
                                    int target = ((Integer) a[at]).intValue();
                                    a[at] = Integer.valueOf(0);
                                    int k = HITS.incrementAndGet();
                                    if (FIRST.getAndIncrement() == 0) {
                                        Log.i(TAG, "file picker: user " + target + " -> 0 @arg" + at);
                                    }
                                    HookMain.mark(PROP, "hit:" + k);
                                    // 换用户后系统会立刻校验 INTERACT_ACROSS_USERS_FULL，
                                    // 调用方是空间里的普通应用，必然被拒并抛出到应用进程（闪退）。
                                    // 这一次调用按系统身份执行——与系统自己跨用户拉起选择器同一条路径，
                                    // 返回前恢复，线程内生效，不影响任何其它调用。
                                    long token = Binder.clearCallingIdentity();
                                    try {
                                        return chain.proceed(a);
                                    } finally {
                                        Binder.restoreCallingIdentity(token);
                                    }
                                }
                            }
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "pick hook failed on " + m + ": " + t);
            }
        }
        HookMain.mark(PROP, n > 0 ? "hooked" : "no-method");
        Log.i(TAG, "file picker hooks: " + n);
        return n;
    }

    /** 文件选择类 action：打开/获取/新建文档、选目录、通用取内容 */
    private static boolean isFilePicker(Intent it) {
        String a = it == null ? null : it.getAction();
        if (a == null) return false;
        switch (a) {
            case Intent.ACTION_OPEN_DOCUMENT:
            case Intent.ACTION_GET_CONTENT:
            case Intent.ACTION_OPEN_DOCUMENT_TREE:
            case Intent.ACTION_CREATE_DOCUMENT:
            case Intent.ACTION_PICK:
                return true;
            default:
                return false;
        }
    }

    private static int indexOf(Class<?>[] p, Class<?> want) {
        for (int i = 0; i < p.length; i++) if (p[i] == want) return i;
        return -1;
    }

    private static int[] intsAfter(Class<?>[] p, int from) {
        int c = 0;
        for (int i = from + 1; i < p.length; i++) if (p[i] == int.class) c++;
        int[] r = new int[c];
        c = 0;
        for (int i = from + 1; i < p.length; i++) if (p[i] == int.class) r[c++] = i;
        return r;
    }

    /** 已锁定过的参数位置；不同重载布局不同，越界就退回扫描。 */
    private static final AtomicInteger LOCKED_AT = new AtomicInteger(-1);

    /**
     * 在候选 int 参数里找出 userId 的位置：值必须是「我们自建空间」的用户 id。
     *
     * <p>不按固定索引取，是因为各版本会往里加参数；只在值真正命中时才改写，
     * 其它 int（flags、displayId 等）不会等于空间用户 id，不会被误改。
     */
    private static int userIdIndex(Object[] a, int[] cands) {
        int lock = LOCKED_AT.get();
        if (lock >= 0) {
            for (int i : cands) if (i == lock) return asTarget(a, i) ? i : -1;
        }
        for (int i : cands) {
            if (asTarget(a, i)) {
                if (LOCKED_AT.compareAndSet(-1, i)) {
                    Log.i(TAG, "file picker: userId at arg " + i);
                }
                return i;
            }
        }
        return -1;
    }

    private static boolean asTarget(Object[] a, int i) {
        if (i >= a.length || !(a[i] instanceof Integer)) return false;
        int v = ((Integer) a[i]).intValue();
        return v > 0 && WmVisibleHook.ours(v);
    }
}
