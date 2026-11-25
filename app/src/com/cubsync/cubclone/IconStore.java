package com.cubsync.cubclone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图标仓库：固定尺寸 Bitmap，内存 + 磁盘两级缓存。
 *
 * <p>为什么不用 Drawable 缓存（2026-11-25 在 G7 上实测）：
 * <ul>
 *   <li>{@code getApplicationIcon} 取 245 个系统应用共 2.9s，p90 25ms、最慢 175ms；
 *       用户应用 49 个也要 0.9s。第二遍（资源都已热）仍是 2.5s，说明慢的不是首次打开 APK。</li>
 *   <li>堆栈定位到 Flyme 改写了 {@code ApplicationPackageManager.getDrawable}，注入
 *       {@code FlymeThemeHelper.makeThemeIcon} —— 每次取图标都跑一遍主题图标替换。</li>
 *   <li>缓存的 Drawable 虽然省掉这次调用，但它持有 APK 的 Resources，首次 draw 仍会在
 *       绘制路径里加载资源；同一张图在 N 个分身行里各画一次就是 N 次。</li>
 * </ul>
 *
 * <p>所以缓存单位改成「按控件像素尺寸画好的 Bitmap」：主线程只做 setImageBitmap，
 * 取图/绘制/落盘全在一条后台线程；下次启动直接读 PNG。
 * 这与 Launcher3 的 LauncherIcons、AOSP PackageManager 的 LoadedIcon 是同一做法。
 *
 * <p>磁盘文件名带 {@code lastUpdateTime}，应用升级后自动失效，不会显示旧图标。
 */
final class IconStore {

    interface Cb {
        void onIcon(Bitmap bm);
    }

    /** 16MB ≈ 111px × 111px × 4B 的三百多个图标，够放下整机全部应用 */
    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>(16 * 1024 * 1024) {
                protected int sizeOf(String k, Bitmap b) {
                    return b.getByteCount();
                }
            };

    /** pkg → lastUpdateTime，用来判断缓存是否过期（主线程只做一次 long 比较） */
    private static final ConcurrentHashMap<String, Long> STAMP =
            new ConcurrentHashMap<String, Long>();
    /** pkg → 正在等图标的回调（同一图标被多行同时请求时只解一次） */
    private static final ConcurrentHashMap<String, List<Cb>> WAIT =
            new ConcurrentHashMap<String, List<Cb>>();

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static Context app;
    private static File dir;
    private static int px;
    private static ExecutorService pool;

    private IconStore() {
    }

    static void init(Context c) {
        if (app != null) return;
        synchronized (IconStore.class) {
            if (app != null) return;
            app = c.getApplicationContext();
            dir = new File(app.getFilesDir(), "icons");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            px = Math.max(1, Math.round(40 * app.getResources().getDisplayMetrics().density));
            pool = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cubclone-icon");
                // 图标解码不该跟手势/绘制抢 CPU
                t.setPriority(Thread.MIN_PRIORITY + 1);
                t.setDaemon(true);
                return t;
            });
        }
    }

    /** 主线程查内存；没命中返回 null（调用方先放占位，再 request） */
    static Bitmap mem(String pkg) {
        return MEM.get(pkg);
    }

    /**
     * 应用列表刷新时对账：按 {@code lastUpdateTime} 判断哪些包升级过，只丢那些包的缓存。
     *
     * <p>整机三百多个包一次 {@code getPackageInfo} 约几十毫秒，所以固定丢到图标线程做，
     * 调用方在哪个线程都行。以前这里是 Drawable 缓存的 {@code evictAll()} —— 每次刷新
     * 把 245 个系统应用全丢，重解一遍就是 2.9s（G7 实测）。
     */
    static void syncAll(final java.util.Collection<String> pkgs) {
        final ExecutorService p = pool;
        if (p == null || pkgs == null || pkgs.isEmpty()) return;
        final String[] arr = pkgs.toArray(new String[0]);
        p.execute(() -> {
            final android.content.pm.PackageManager pm = app.getPackageManager();
            boolean drop = false;
            for (String pkg : arr) {
                long st;
                try {
                    st = pm.getPackageInfo(pkg, 0).lastUpdateTime;
                } catch (Throwable t) {
                    continue;   // 已卸载
                }
                Long old = STAMP.put(pkg, Long.valueOf(st));
                if (old == null || old.longValue() == st) continue;
                MEM.remove(pkg);
                drop = true;
            }
            if (!drop) return;
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) return;
            for (File f : fs) {
                String n = f.getName();
                // 文件名 pkg_<lastUpdateTime>_<px>.png；包名本身允许含 '_'，
                // 所以从右往左切两刀：最后一段是 px，倒数第二段是 stamp。
                int a = n.lastIndexOf('_');
                int b = a <= 0 ? -1 : n.lastIndexOf('_', a - 1);
                if (b <= 0) continue;
                String pkg = n.substring(0, b);
                Long cur = STAMP.get(pkg);
                String keep = pkg + "_" + (cur == null ? -1 : cur.longValue()) + "_" + px + ".png";
                if (!n.equals(keep)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        });
    }

    /** 请求图标。内存命中就在当前线程同步回调，否则排队，回调一律在主线程。 */
    static void request(final String pkg, final Cb cb) {
        Bitmap b = MEM.get(pkg);
        if (b != null) {
            cb.onIcon(b);
            return;
        }
        boolean first;
        List<Cb> list = new ArrayList<Cb>(2);
        synchronized (WAIT) {
            List<Cb> l = WAIT.get(pkg);
            first = l == null;
            if (first) {
                l = list;
                WAIT.put(pkg, l);
            }
            l.add(cb);
        }
        if (!first) return;
        final ExecutorService p = pool;
        if (p == null) {
            WAIT.remove(pkg);
            return;
        }
        p.execute(() -> {
            final Bitmap r = load(pkg);
            List<Cb> waiters;
            synchronized (WAIT) {
                waiters = WAIT.remove(pkg);
            }
            if (r == null || waiters == null) return;
            final List<Cb> ws = waiters;
            UI.post(() -> {
                for (Cb c : ws) c.onIcon(r);
            });
        });
    }

    // ── 后台 ──────────────────────────────────────────────────────────────

    private static Bitmap load(String pkg) {
        Bitmap b = MEM.get(pkg);       // 双检：排队期间可能已被别的任务填好
        if (b != null) return b;

        long stamp = stampOf(pkg);
        File f = new File(dir, pkg + "_" + stamp + "_" + px + ".png");
        Bitmap disk = decode(f);
        if (disk != null) {
            MEM.put(pkg, disk);
            STAMP.put(pkg, Long.valueOf(stamp));
            return disk;
        }

        Drawable d = iconOf(pkg);
        if (d == null) return null;
        Bitmap bm = render(d, px);
        if (bm == null) return null;
        MEM.put(pkg, bm);
        STAMP.put(pkg, Long.valueOf(stamp));
        save(f, bm);
        return bm;
    }

    private static long stampOf(String pkg) {
        Long v = STAMP.get(pkg);
        if (v != null) return v.longValue();
        try {
            return app.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static Drawable iconOf(String pkg) {
        try {
            return app.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decode(File f) {
        if (!f.isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return null;
            if (b.getWidth() != px || b.getHeight() != px) {
                // 换了 density 或旧版本留下的：尺寸不对就丢掉重画
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                b.recycle();
                return null;
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按 ImageView {@code centerInside} 的规则把系统 Drawable 画进 px×px。
     * 尺寸和缩放比与「直接把 Drawable 交给 ImageView」完全一致，视觉不变；
     * 区别只是这一步在后台做一次，之后主线程只贴位图。
     */
    private static Bitmap render(Drawable d, int size) {
        Bitmap bm = null;
        try {
            bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            int iw = d.getIntrinsicWidth();
            int ih = d.getIntrinsicHeight();
            if (iw <= 0) iw = size;
            if (ih <= 0) ih = size;
            float s = Math.min((float) size / iw, (float) size / ih);
            int w = Math.max(1, Math.round(iw * s));
            int h = Math.max(1, Math.round(ih * s));
            int l = (size - w) / 2;
            int t = (size - h) / 2;
            d.setBounds(l, t, l + w, t + h);
            d.draw(c);
            return bm;
        } catch (Throwable t) {
            if (bm != null) bm.recycle();
            return null;
        }
    }

    private static void save(File f, Bitmap bm) {
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(f);
            bm.compress(Bitmap.CompressFormat.PNG, 100, fo);
        } catch (Throwable ignored) {
        } finally {
            if (fo != null) {
                try {
                    fo.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
