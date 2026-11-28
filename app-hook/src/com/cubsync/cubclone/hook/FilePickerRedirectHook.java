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
            // AOSP 12–14：(IApplicationThread, String, String, Intent, String, IBinder,
            //               String, int, int, ProfilerInfo, Bundle, int userId[, boolean])
            if (p.length < 12 || p.length > 13) continue;
            if (p[3] != Intent.class || p[11] != int.class) continue;
            try {
                xp.deoptimize(m);
            } catch (Throwable ignored) {
            }
            try {
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object[] a = chain.getArgs().toArray();
                            if (a.length >= 12 && a[3] instanceof Intent && a[11] instanceof Integer) {
                                int target = ((Integer) a[11]).intValue();
                                // 只要是「发给我们自己空间的选文件请求」就换到主用户。
                                // 不绑调用者：空间里的应用、shell、系统代理都可能替它发起。
                                if (target > 0 && WmVisibleHook.ours(target)
                                        && isFilePicker((Intent) a[3])) {
                                    a[11] = Integer.valueOf(0);
                                    int k = HITS.incrementAndGet();
                                    if (FIRST.getAndIncrement() == 0) {
                                        Log.i(TAG, "file picker: user " + target + " -> 0");
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
}
