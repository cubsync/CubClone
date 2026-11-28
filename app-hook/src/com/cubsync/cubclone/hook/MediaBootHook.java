package com.cubsync.cubclone.hook;

import android.content.ContentProviderClient;
import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;

/**
 * 让系统在挂载分身的共享存储之前，先把该分身的「媒体存储」进程拉起来。
 *
 * <p>系统挂 emulated 卷的顺序是：vold 挂 FUSE → 把 {@code Android/data} 绑进 FUSE 视图。
 * 绑定前要先卸掉目标上可能存在的旧挂载，这一步的路径解析落在 FUSE 里，而 FUSE 的服务方
 * 就是该用户的 MediaProvider。新启动的用户还没拉起 MediaProvider，内核拿不到属主，
 * {@code umount2} 返回 EACCES，vold 判定整卷失败并回滚成 unmountable。
 *
 * <p>这里 hook 的是 AOSP 自己的用户启动回调 {@code StorageManagerService.onUserStarting}，
 * 在挂载动作之前以该用户身份取出一次媒体内容提供者，把它拉起来。只对自建空间生效，
 * 不判断 ROM。
 *
 * <p>编译用的 android.jar 被裁剪过（缺 {@code UserHandle#getIdentifier}、
 * {@code Context#createContextAsUser}），这几处走反射，运行期都是正规公开接口。
 */
final class MediaBootHook {

    private static final String TAG = "CubCloneHook";
    private static final String PROP = "sys.cubclone.hook.mediaboot";
    /** 系统存储服务：Android 13 起在 storage 子包，早期版本在 server 根包 */
    private static final String[] CANDIDATES = {
            "com.android.server.storage.StorageManagerService",
            "com.android.server.StorageManagerService",
    };
    private static final String METHOD = "onUnlockUser";
    private static final long WAIT_MS = 4000;
    private static final long SETTLE_MS = 700;

    private final XposedInterface xp;

    MediaBootHook(XposedInterface xp) {
        this.xp = xp;
    }

    int apply(ClassLoader cl) {
        Class<?> c = null;
        for (String name : CANDIDATES) {
            try {
                c = Class.forName(name, false, cl);
                break;
            } catch (Throwable ignored) {
            }
        }
        if (c == null) {
            HookMain.mark(PROP, "no-class");
            return 0;
        }
        Method target = null;
        for (Method m : c.getDeclaredMethods()) {
            if (!METHOD.equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == int.class) {
                target = m;
                break;
            }
        }
        if (target == null) {
            HookMain.mark(PROP, "no-method");
            Log.w(TAG, METHOD + " not found");
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
                        try {
                            Object[] a = chain.getArgs().toArray();
                            if (a.length == 1 && a[0] instanceof Integer) {
                                int userId = ((Integer) a[0]).intValue();
                                if (userId > 0 && WmVisibleHook.ours(userId)) {
                                    warmUp(chain.getThisObject(), userId);
                                }
                            }
                        } catch (Throwable t) {
                            HookMain.mark(PROP, "err:" + t.getClass().getSimpleName());
                        }
                        return chain.proceed();
                    });
            HookMain.mark(PROP, "hooked");
            Log.i(TAG, METHOD + " hooked");
            try {
                hookSession(cl);
            } catch (Throwable ignored) {
            }
            return 1;
        } catch (Throwable t) {
            HookMain.mark(PROP, "fail");
            Log.w(TAG, "mediaBoot hook failed: " + t);
            return 0;
        }
    }

    /** 卷的挂载用户；拿不到返回 -1 */
    private static int mountUserIdOf(Object vol) {
        if (vol == null) return -1;
        try {
            Field f = vol.getClass().getField("mountUserId");
            Object v = f.get(vol);
            if (v instanceof Integer) return ((Integer) v).intValue();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * 会话建立后停顿一下再返回。
     *
     * <p>系统把 FUSE 设备句柄交给媒体存储是异步的：会话登记完就返回 true，vold 紧接着
     * 去把 {@code Android/data} 绑进 FUSE 视图，而这时 FUSE 守护线程往往还没开始应答。
     * 绑定的第一步是卸载目标上可能存在的旧挂载，路径解析落在 FUSE 里，没人应答就返回
     * EACCES，整卷回滚。这里在会话建立后留几百毫秒，让守护线程就位，属于对时序的等待，
     * 不改变任何挂载方式。
     */
    private int hookSession(ClassLoader cl) {
        Class<?> c;
        try {
            c = Class.forName("com.android.server.storage.StorageSessionController", false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"onVolumeMount".equals(m.getName())) continue;
            if (m.getParameterTypes().length != 2) continue;
            try {
                xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            try {
                                Object[] a = chain.getArgs().toArray();
                                int uid = mountUserIdOf(a.length == 2 ? a[1] : null);
                                if (uid > 0 && WmVisibleHook.ours(uid)) {
                                    Thread.sleep(SETTLE_MS);
                                    HookMain.mark(PROP, "settle:" + uid);
                                }
                            } catch (Throwable t) {
                                HookMain.mark(PROP, "settle-err:" + t.getClass().getSimpleName());
                            }
                            return r;
                        });
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    private static int userIdOf(Object targetUser) {
        Object uh = null;
        try {
            uh = targetUser.getClass().getMethod("getUserHandle").invoke(targetUser);
        } catch (Throwable ignored) {
        }
        if (uh == null) {
            try {
                Field f = targetUser.getClass().getDeclaredField("mUserHandle");
                f.setAccessible(true);
                uh = f.get(targetUser);
            } catch (Throwable ignored) {
            }
        }
        if (uh == null) {
            try {
                Field f = targetUser.getClass().getField("id");
                Object v = f.get(targetUser);
                if (v instanceof Integer) return ((Integer) v).intValue();
            } catch (Throwable ignored) {
            }
        }
        if (uh == null) return -1;
        try {
            Object id = uh.getClass().getMethod("getIdentifier").invoke(uh);
            if (id instanceof Integer) return ((Integer) id).intValue();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static UserHandle handleOf(int userId) {
        try {
            Method of = UserHandle.class.getMethod("of", int.class);
            return (UserHandle) of.invoke(null, userId);
        } catch (Throwable ignored) {
        }
        try {
            Method g = UserHandle.class.getMethod("getUserHandleForUid", int.class);
            return (UserHandle) g.invoke(null, userId * 100000);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Context contextAsUser(Context ctx, UserHandle uh) {
        try {
            Method m = Context.class.getMethod("createContextAsUser", UserHandle.class, int.class);
            return (Context) m.invoke(ctx, uh, 0);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 在独立线程里拉起该用户的媒体存储。不能占用系统服务的回调线程，那里在等我们返回
     * 之后才去挂载；开线程再限时等待，最长 {@link #WAIT_MS}，超时就放行，不拖死启动。
     */
    private static void warmUp(Object service, final int userId) {
        final Context ctx;
        try {
            Field f = service.getClass().getDeclaredField("mContext");
            f.setAccessible(true);
            ctx = (Context) f.get(service);
        } catch (Throwable t) {
            HookMain.mark(PROP, "no-ctx");
            return;
        }
        final UserHandle uh = handleOf(userId);
        if (ctx == null || uh == null) {
            HookMain.mark(PROP, "no-handle");
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                ContentProviderClient c = null;
                try {
                    Context uc = contextAsUser(ctx, uh);
                    if (uc != null) {
                        c = uc.getContentResolver().acquireUnstableContentProviderClient("media");
                    }
                } catch (Throwable t) {
                    HookMain.mark(PROP, "warm-err:" + t.getClass().getSimpleName());
                } finally {
                    if (c != null) c.close();
                    done.countDown();
                }
            }
        }, "cubclone-mediaboot").start();
        try {
            boolean ok = done.await(WAIT_MS, TimeUnit.MILLISECONDS);
            HookMain.mark(PROP, (ok ? "warm:" : "timeout:") + userId);
        } catch (InterruptedException ignored) {
        }
    }
}
