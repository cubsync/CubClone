package com.cubsync.cubclone.hook;

import android.content.Intent;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 让自建空间直接用主空间的输入法，不用往每个空间里装一份。
 *
 * <p>原生分身的做法（MYUI 反编译实证）：{@code InputMethodManagerService} 里
 * {@code if (isAppCloneUser(userId)) userId = 0}——分身用户的输入法请求整个按主用户处理，
 * 所以分身里一个输入法包都没有，照样能打字。
 *
 * <p>我们的空间是独立用户，IMS 会按空间的 userId 去解析并绑定输入法服务；空间里没装
 * （本来也不该装），绑定就失败，表现为「点输入框没反应」。
 *
 * <p>做法：不碰 IMS 内部结构（各 ROM 差异大），只在 {@code ContextImpl.bindServiceAsUser}
 * 这一个系统级入口上做重定向——当 system_server 为「我们的空间」绑定输入法服务时，
 * 把目标用户换成 0（主用户）。输入法进程本来就是跨进程服务，client 在哪个用户不影响通信。
 *
 * <p>配套（主应用侧）：空间的 {@code default_input_method} / {@code enabled_input_methods}
 * 直接写成主空间那套（settings put --user），只是不装 APK。
 *
 * <p>影响面：只匹配 action 为输入法服务、且目标用户是我们空间的绑定；其它绑定原样放行。
 */
final class InputRedirectHook {

    private static final String TAG = "CubCloneHook";
    private static final String CONTEXT_IMPL = "android.app.ContextImpl";
    private static final String METHOD = "bindServiceAsUser";
    /** InputMethodService.SERVICE_INTERFACE */
    private static final String IME_ACTION = "android.inputmethodservice.InputMethodService";

    static final String PROP_HITS = "sys.cubclone.hook.ime.hits";
    static final String PROP_LAST = "sys.cubclone.hook.ime";

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger FIRST = new AtomicInteger();

    private final XposedInterface xp;

    InputRedirectHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        hookCurrentProfile(cl);
        hookMethodMap(cl);
        Class<?> c;
        try {
            c = Class.forName(CONTEXT_IMPL, false, cl);
        } catch (Throwable t) {
            Log.i(TAG, "no ContextImpl; skip ime redirect");
            return 0;
        }
        Method target = null;
        for (Method m : c.getDeclaredMethods()) {
            if (!METHOD.equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            // (Intent, ServiceConnection, int, UserHandle)
            if (p.length == 4 && p[0] == Intent.class && p[3] == UserHandle.class) {
                target = m;
                break;
            }
        }
        if (target == null) {
            Log.w(TAG, METHOD + " not found; skip ime redirect");
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
                        Object[] args = chain.getArgs().toArray();
                        if (args.length == 4 && args[0] instanceof Intent && args[3] instanceof UserHandle) {
                            int uid = idOf((UserHandle) args[3]);
                            if (uid > 0 && WmVisibleHook.ours(uid) && isIme((Intent) args[0])) {
                                args[3] = user0();
                                int k = HITS.incrementAndGet();
                                if (FIRST.getAndIncrement() == 0) {
                                    Log.i(TAG, "ime bind: uid " + uid + " -> 0");
                                    HookMain.mark(PROP_LAST, String.valueOf(uid));
                                }
                                HookMain.mark(PROP_HITS, String.valueOf(k));
                                return chain.proceed(args);
                            }
                        }
                        return chain.proceed();
                    });
            HookMain.mark(PROP_LAST, "hooked");
            Log.i(TAG, METHOD + " hooked for ime redirect");
            return 1;
        } catch (Throwable t) {
            Log.w(TAG, "ime hook failed: " + t);
            return 0;
        }
    }

    /**
     * 第二道闸：{@code InputMethodUtils$InputMethodSettings.isCurrentProfile(int)}。
     *
     * <p>IMS 在 startInputOrWindowGainedFocusInternal 里用它判断「请求方是不是前台用户/profile」，
     * 不是就记 {@code A background user is requesting window. Hiding IME.} 并返回 INVALID_USER。
     * 空间的 userId 不在 {@code mCurrentProfileIds} 里，所以输入法请求根本进不到绑定那一步。
     * 原生分身不受影响，是因为它们的号段被算进当前 profile（父用户 0 的 profile 组）。
     *
     * <p>这里只对我们自己建的空间返回 true，其它一律走原实现。
     */
    private void hookCurrentProfile(ClassLoader cl) {
        String cls = "com.android.server.inputmethod.InputMethodUtils$InputMethodSettings";
        try {
            Class<?> c = Class.forName(cls, false, cl);
            Method m = null;
            for (Method x : c.getDeclaredMethods()) {
                if ("isCurrentProfile".equals(x.getName())
                        && x.getParameterTypes().length == 1
                        && x.getParameterTypes()[0] == int.class) {
                    m = x;
                    break;
                }
            }
            if (m == null) {
                HookMain.mark("sys.cubclone.hook.ime.cp", "no-method");
                Log.w(TAG, "isCurrentProfile not found");
                return;
            }
            try {
                xp.deoptimize(m);
            } catch (Throwable ignored) {
            }
            xp.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        if (Boolean.TRUE.equals(r)) return r;
                        Object a0 = chain.getArg(0);
                        int userId = a0 instanceof Integer ? ((Integer) a0).intValue() : -1;
                        if (userId > 0 && WmVisibleHook.ours(userId)) {
                            int k = HITS.incrementAndGet();
                            if (FIRST.getAndIncrement() == 0) {
                                Log.i(TAG, "ime current profile: uid " + userId + " -> true");
                            }
                            HookMain.mark(PROP_HITS, String.valueOf(k));
                            return Boolean.TRUE;
                        }
                        return r;
                    });
            HookMain.mark("sys.cubclone.hook.ime.cp", "hooked");
            Log.i(TAG, "isCurrentProfile hooked");
        } catch (Throwable t) {
            HookMain.mark("sys.cubclone.hook.ime.cp", "fail:" + t);
            Log.w(TAG, "isCurrentProfile hook failed: " + t);
        }
    }

    /**
     * 第三道闸：IMS 按用户构建「可用的输入法集合」（{@code queryMethodMapForUser(int)}）。
     * 空间里没装输入法时这个集合是空的，{@code mCurMethodId} 直接被置 null，后面连绑定都不会发起。
     * 这里把空间的查询换成主用户（参数改 0），集合里就有主空间那套输入法；
     * 真正绑定时再由 bindServiceAsUser 的 hook 把目标用户换到 0。
     */
    private void hookMethodMap(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.android.server.inputmethod.InputMethodManagerService", false, cl);
            Method m = null;
            for (Method x : c.getDeclaredMethods()) {
                if ("queryMethodMapForUser".equals(x.getName())
                        && x.getParameterTypes().length == 1
                        && x.getParameterTypes()[0] == int.class) {
                    m = x;
                    break;
                }
            }
            if (m == null) {
                HookMain.mark("sys.cubclone.hook.ime.mm", "no-method");
                return;
            }
            try {
                xp.deoptimize(m);
            } catch (Throwable ignored) {
            }
            xp.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object a0 = chain.getArg(0);
                        int userId = a0 instanceof Integer ? ((Integer) a0).intValue() : -1;
                        if (userId > 0 && WmVisibleHook.ours(userId)) {
                            HookMain.mark("sys.cubclone.hook.ime.mm", String.valueOf(userId));
                            return chain.proceed(new Object[]{Integer.valueOf(0)});
                        }
                        return chain.proceed();
                    });
            HookMain.mark("sys.cubclone.hook.ime.mm", "hooked");
        } catch (Throwable t) {
            HookMain.mark("sys.cubclone.hook.ime.mm", "fail");
            Log.w(TAG, "method map hook failed: " + t);
        }
    }

    /** UserHandle.getIdentifier 是 @hide，编译桩里没有，只能反射 */
    private static int idOf(UserHandle uh) {
        if (uh == null) return -1;
        try {
            return ((Number) UserHandle.class.getMethod("getIdentifier").invoke(uh)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** UserHandle.of(int) 同样是 @hide；拿不到就返回 null，调用方原样放行 */
    private static UserHandle user0() {
        try {
            return (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isIme(Intent it) {
        if (it == null) return false;
        if (IME_ACTION.equals(it.getAction())) return true;
        // 少数 ROM 用显式组件而不是 action
        if (it.getComponent() != null) {
            String p = it.getComponent().getPackageName();
            return p != null && p.toLowerCase().contains("inputmethod");
        }
        return false;
    }
}
