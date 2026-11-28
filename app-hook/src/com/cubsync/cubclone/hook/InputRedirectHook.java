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
    /**
     * AOSP 常量：{@code InputMethodInfo.SERVICE_INTERFACE} = "android.view.InputMethod"。
     * 之前误写成 InputMethodService 的类名，导致绑定层 hook 一次都没匹配到。
     */
    private static final String IME_ACTION = "android.view.InputMethod";

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
        hookImeQueries(cl);
        hookPackageCheck(cl);
        Class<?> c;
        try {
            c = Class.forName(CONTEXT_IMPL, false, cl);
        } catch (Throwable t) {
            Log.i(TAG, "no ContextImpl; skip ime redirect");
            return 0;
        }
        java.util.List<Method> targets = new java.util.ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            if (!METHOD.equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            // 不绑死参数个数：只要带 Intent 和 UserHandle 就是候选，全部挂上
            // （ContextImpl 里 bindServiceAsUser 有多个重载，IMS 用的是哪一个随版本变）
            if (indexOf(p, Intent.class) >= 0 && indexOf(p, UserHandle.class) >= 0) {
                targets.add(m);
            }
        }
        if (targets.isEmpty()) {
            Log.w(TAG, METHOD + " not found; skip ime redirect");
            return 0;
        }
        int ok = 0;
        for (Method target : targets) {
            try {
                try {
                    xp.deoptimize(target);
                } catch (Throwable ignored) {
                }
                xp.hook(target)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!HookMain.flagOn(HookMain.P_IME)) return chain.proceed();
                            Object[] args = chain.getArgs().toArray();
                            // 只改「绑定输入法服务」这一类，别的绑定原样放行。
                            // 这里只动 UserHandle：同签名里的 int 是 bind flags，不是用户 id。
                            if (anyIme(args) && rewriteUserHandles(args) > 0) {
                                int k = HITS.incrementAndGet();
                                if (FIRST.getAndIncrement() == 0) {
                                    Log.i(TAG, "ime bind: -> user 0");
                                    HookMain.mark(PROP_LAST, "bind");
                                }
                                HookMain.mark(PROP_HITS, String.valueOf(k));
                                return chain.proceed(args);
                            }
                            return chain.proceed();
                        });
                ok++;
            } catch (Throwable t) {
                Log.w(TAG, "ime hook failed: " + t);
            }
        }
        HookMain.mark(PROP_LAST, ok > 0 ? "hooked:" + ok : "fail");
        Log.i(TAG, METHOD + " hooked for ime redirect: " + ok + "/" + targets.size());
        return ok;
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
                        if (!HookMain.flagOn(HookMain.P_IME)) return chain.proceed();
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
     * 第二道闸：IMS 按用户构建「可用的输入法集合」。
     *
     * <p>空间里没装输入法时集合是空的，{@code mCurMethodId} 直接被置 null，后面连绑定都不会发起。
     * AOSP 各版本这里的方法名与签名不一样：
     * <ul>
     *   <li>11–13：{@code queryMethodMapForUser(int)}</li>
     *   <li>12+：{@code queryInputMethodServicesInternal(Context, int, ArrayMap, ArrayMap, ArrayList, int)}</li>
     * </ul>
     * 所以不绑签名：按名字候选 + 名字模式兜底，命中后把「值等于我们空间用户 id」的
     * 参数一律改成 0，空间就拿到主用户那套输入法清单；真正绑定时再由
     * {@code bindServiceAsUser} 的钩子把目标用户换成 0。
     */
    private void hookImeQueries(ClassLoader cl) {
        String name = "sys.cubclone.hook.ime.q";
        Class<?> c;
        try {
            c = Class.forName(IMS, false, cl);
        } catch (Throwable t) {
            HookMain.mark(name, "no-class");
            return;
        }
        Method[] cands = pickImeQueries(c);
        if (cands.length == 0) {
            HookMain.mark(name, "no-method");
            HookMain.mark("sys.cubclone.hook.ime.mm", "no-method");
            return;
        }
        int n = 0;
        for (Method m : cands) {
            try {
                try {
                    xp.deoptimize(m);
                } catch (Throwable ignored) {
                }
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!HookMain.flagOn(HookMain.P_IME)) return chain.proceed();
                            Object[] a = chain.getArgs().toArray();
                            if (rewriteUserArgs(a) > 0) {
                                HookMain.mark(name, m.getName());
                                return chain.proceed(a);
                            }
                            return chain.proceed();
                        });
                n++;
                Log.i(TAG, "ime query hooked: " + m.getName());
            } catch (Throwable t) {
                Log.w(TAG, "ime query hook failed on " + m.getName() + ": " + t);
            }
        }
        HookMain.mark(name, n > 0 ? "hooked:" + n : "fail");
        HookMain.mark("sys.cubclone.hook.ime.mm", n > 0 ? "hooked:" + n : "fail");
    }

    private static final String IMS = "com.android.server.inputmethod.InputMethodManagerService";

    /**
     * 第四道闸：{@code InputMethodUtils.checkIfPackageBelongsToUid(AppOpsManager, uid, pkg)}。
     *
     * <p>{@code startInputUncheckedLocked} 在绑定之前用它确认「客户端的 uid 确实拥有这个输入法包」，
     * 内部就是 {@code appOpsManager.checkPackage(uid, pkg)}。空间的客户端 uid 里没有主空间的输入法包，
     * 校验抛 SecurityException → 返回 false → IMS 直接返回 {@code INVALID_PACKAGE_NAME}，
     * 连绑定都不会发起（表现就是 mCurMethodId 有值、mCurId 为 null、键盘不弹）。
     *
     * <p>只对我们自己空间的 uid 放行；主用户、系统自带分身的判定原样走原实现。
     */
    private void hookPackageCheck(ClassLoader cl) {
        String name = "sys.cubclone.hook.ime.pkg";
        try {
            Class<?> c = Class.forName("com.android.server.inputmethod.InputMethodUtils", false, cl);
            Method m = null;
            for (Method x : c.getDeclaredMethods()) {
                if (!"checkIfPackageBelongsToUid".equals(x.getName())) continue;
                Class<?>[] p = x.getParameterTypes();
                if (p.length == 3 && p[1] == int.class && p[2] == String.class) {
                    m = x;
                    break;
                }
            }
            if (m == null) {
                HookMain.mark(name, "no-method");
                return;
            }
            try {
                xp.deoptimize(m);
            } catch (Throwable ignored) {
            }
            xp.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!HookMain.flagOn(HookMain.P_IME)) return chain.proceed();
                        Object r = chain.proceed();
                        if (Boolean.TRUE.equals(r)) return r;
                        Object a1 = chain.getArg(1);
                        int uid = a1 instanceof Integer ? ((Integer) a1).intValue() : -1;
                        if (uid > 0 && WmVisibleHook.ours(uid / 100000)) {
                            int k = HITS.incrementAndGet();
                            if (FIRST.getAndIncrement() == 0) {
                                Log.i(TAG, "ime package check: uid " + uid + " -> true");
                            }
                            HookMain.mark(name, "hit:" + k);
                            HookMain.mark(PROP_HITS, String.valueOf(k));
                            return Boolean.TRUE;
                        }
                        return r;
                    });
            HookMain.mark(name, "hooked");
            Log.i(TAG, "checkIfPackageBelongsToUid hooked");
        } catch (Throwable t) {
            HookMain.mark(name, "fail");
            Log.w(TAG, "pkg check hook failed: " + t);
        }
    }

    /** 已知名字优先，其次按名字模式兜底；同一次只挂一次。 */
    private static Method[] pickImeQueries(Class<?> c) {
        java.util.LinkedHashSet<Method> out = new java.util.LinkedHashSet<>();
        for (Method x : c.getDeclaredMethods()) {
            String n = x.getName();
            if ("queryMethodMapForUser".equals(n) || "queryInputMethodServicesInternal".equals(n)) {
                if (rewriteCandidate(x.getParameterTypes())) out.add(x);
            }
        }
        if (!out.isEmpty()) return out.toArray(new Method[0]);
        for (Method x : c.getDeclaredMethods()) {
            String n = x.getName().toLowerCase(java.util.Locale.ROOT);
            if (!n.startsWith("query")) continue;
            if (!n.contains("methodmap") && !n.contains("inputmethodservice")) continue;
            if (rewriteCandidate(x.getParameterTypes())) out.add(x);
        }
        return out.toArray(new Method[0]);
    }

    private static boolean rewriteCandidate(Class<?>[] p) {
        for (Class<?> t : p) if (t == int.class || t == UserHandle.class) return true;
        return false;
    }

    private static int indexOf(Class<?>[] p, Class<?> want) {
        for (int i = 0; i < p.length; i++) if (p[i] == want) return i;
        return -1;
    }

    /**
     * 把「值为我们空间用户 id」的 int 参数换成 0，UserHandle 同理会换成 user 0。
     *
     * <p>不按位置取，是因为 AOSP 每个版本都会往里加参数；只有值真正命中我们空间
     * 用户时才改，flags / displayId 之类的 int 不会等于用户 id，不会被误改。
     *
     * @return 改写的参数个数
     */
    private static int rewriteUserArgs(Object[] a) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            Object v = a[i];
            if (v instanceof Integer) {
                int id = ((Integer) v).intValue();
                if (id > 0 && WmVisibleHook.ours(id)) {
                    a[i] = Integer.valueOf(0);
                    n++;
                }
            } else if (v instanceof UserHandle) {
                int id = idOf((UserHandle) v);
                if (id > 0 && WmVisibleHook.ours(id)) {
                    a[i] = user0();
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean anyIme(Object[] a) {
        for (Object v : a) if (v instanceof Intent && isIme((Intent) v)) return true;
        return false;
    }

    /** 只改 UserHandle 形态的用户 id（绑定服务用这个形态，int 是 flags 不能动）。 */
    private static int rewriteUserHandles(Object[] a) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            Object v = a[i];
            if (!(v instanceof UserHandle)) continue;
            int id = idOf((UserHandle) v);
            if (id > 0 && WmVisibleHook.ours(id)) {
                a[i] = user0();
                n++;
            }
        }
        return n;
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
