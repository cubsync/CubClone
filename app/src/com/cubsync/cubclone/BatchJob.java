package com.cubsync.cubclone;

import android.content.Context;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 一次批量备份 / 恢复的全部状态。
 *
 * 为什么放在进程里而不是 Activity 里：页面退出后任务要能继续跑，回来还得看得见
 * 进度和日志。Activity 只是这份状态的一个视图，随时可以销毁重建。
 *
 * 进程被系统杀掉时任务确实会中断——已经写好的备份文件都在，日志也落了盘，
 * 下次进来能看到上次那份。
 */
public final class BatchJob {

    public static final int ST_WAIT = 0;
    public static final int ST_RUN = 1;
    public static final int ST_OK = 2;
    public static final int ST_BAD = 3;
    public static final int ST_SKIP = 4;

    /** 上一次批量任务的日志，备份页在进程重启后从这里读 */
    public static final String LOG_NAME = "batch-last.log";

    public static final class Row {
        public final String pkg;
        public final int user;
        public final String path;
        public volatile int state = ST_WAIT;
        public volatile String detail = "";
        public volatile String time = "";

        public Row(String pkg, int user, String path) {
            this.pkg = pkg;
            this.user = user;
            this.path = path;
        }
    }

    private static volatile BatchJob current;

    /** 当前（或最近一次）的批量任务；null = 本进程还没跑过 */
    public static BatchJob get() { return current; }

    public final boolean restore;
    public final List<Row> rows = new ArrayList<Row>();
    /** 恢复到哪个空间；-1 = 按备份里的空间 */
    public final int targetUser;
    public final int parts;

    public volatile boolean running;
    public volatile boolean finished;
    /** 「正在备份 3/12」 */
    public volatile String head = "";
    /** 「8.4 MB/s · 已写入 132 MB · 已用 0:48」 */
    public volatile String speed = "";
    public volatile String summary = "";
    public volatile int done;

    private final Context ctx;
    private final StringBuilder log = new StringBuilder();
    private PowerManager.WakeLock wake;
    private String algo;
    private int level;

    private BatchJob(Context c, boolean restore, int targetUser, int parts) {
        // Zstd / CloneStore 都靠 appCtx 找 Context；过程页可能先于任何页面被拉起
        CloneStore.attach(c);
        this.ctx = c.getApplicationContext();
        this.restore = restore;
        this.targetUser = targetUser;
        this.parts = parts;
    }

    // ── 起一个任务 ───────────────────────────────────────────────────
    public static BatchJob startBackup(Context c, int user, List<String> pkgs) {
        BatchJob j = new BatchJob(c, false, user, 0);
        for (String p : pkgs) j.rows.add(new Row(p, user, ""));
        j.algo = sp(c).getString("backup_algo", "zst");
        j.level = sp(c).getInt("backup_level_" + j.algo, AppData.defaultLevel(j.algo));
        return j.launch();
    }

    /**
     * 一次备份多个空间：条目形如 "10|com.foo"（竖线分隔，包名里不会有竖线）。
     * 支持「主空间 + 空间 1」这种组合，选中的应用按各自空间分开跑。
     */
    public static BatchJob startBackupMulti(Context c, List<String> items) {
        BatchJob j = new BatchJob(c, false, 0, 0);
        for (String s : items) {
            int i = s.indexOf('|');
            if (i <= 0) continue;
            int u;
            try { u = Integer.parseInt(s.substring(0, i).trim()); } catch (Throwable t) { continue; }
            String pkg = s.substring(i + 1);
            if (pkg.length() > 0) j.rows.add(new Row(pkg, u, ""));
        }
        j.algo = sp(c).getString("backup_algo", "zst");
        j.level = sp(c).getInt("backup_level_" + j.algo, AppData.defaultLevel(j.algo));
        return j.launch();
    }

    /**
     * 恢复：同空间同应用有多份时只留最新那份——按时间从旧到新跑等于旧的盖掉新的，白跑还丢数据。
     */
    public static BatchJob startRestore(Context c, List<String> paths, int targetUser, int parts) {
        BatchJob j = new BatchJob(c, true, targetUser, parts);
        LinkedHashMap<String, Row> newest = new LinkedHashMap<String, Row>();
        List<Row> skipped = new ArrayList<Row>();
        for (String p : paths) {
            AppData.Backup bk = AppData.fromPath(p);
            if (bk == null) continue;
            String key = bk.user + "/" + bk.pkg;
            Row old = newest.get(key);
            if (old == null) {
                newest.put(key, new Row(bk.pkg, bk.user, p));
            } else {
                AppData.Backup ob = AppData.fromPath(old.path);
                if (ob == null || bk.ts.compareTo(ob.ts) > 0) {
                    newest.put(key, new Row(bk.pkg, bk.user, p));
                }
                Row skip = new Row(bk.pkg, bk.user, p);
                skip.state = ST_SKIP;
                skip.detail = c.getString(R.string.batch_skip_older, bk.ts);
                skipped.add(skip);
            }
        }
        j.rows.addAll(newest.values());
        j.rows.addAll(skipped);
        return j.launch();
    }

    @SuppressWarnings("deprecation")
    private BatchJob launch() {
        current = this;
        running = true;
        startedAt = System.currentTimeMillis();
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cubclone:batch");
            wake.setReferenceCounted(false);
            // 屏幕关掉后 CPU 一睡，root 侧的 tar 也会被拖慢，跑到一半的时间最难解释
            wake.acquire(60 * 60 * 1000L);
        } catch (Throwable ignored) {
        }
        Thread t = new Thread(new Runnable() {
            public void run() { work(); }
        });
        t.setDaemon(true);
        t.start();
        return this;
    }

    private long startedAt;

    private void work() {
        int ok = 0, bad = 0;
        try {
            Notify.progress(ctx, restore, 0, rows.size(), "", "");
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.state == ST_SKIP) {
                    done = i + 1;
                    continue;
                }
                r.state = ST_RUN;
                r.time = now();
                head = ctx.getString(restore ? R.string.batch_restoring_n
                        : R.string.batch_backing_n, i + 1, rows.size());
                done = i;
                line(r.time + "  " + ctx.getString(R.string.batch_log_start) + "  "
                        + labelOf(r.pkg) + "  " + spaceLabel(r.user));
                Notify.progress(ctx, restore, done, rows.size(), head, labelOf(r.pkg));

                Meter m = new Meter(restore ? null : AppData.backupBase(r.user, r.pkg));
                m.begin();
                AppData.Backup bk = restore ? AppData.fromPath(r.path) : null;
                String res = restore
                        ? (bk == null ? "ERR_PATH"
                        : AppData.restore(bk, appIdOf(r.pkg), targetUser, parts))
                        : AppData.backupNow(r.user, r.pkg, algo, level, m.base);
                m.stop();
                r.time = now();
                if (res.startsWith("OK")) {
                    r.state = ST_OK;
                    r.detail = restore
                            ? spaceLabel(targetUser < 0 ? r.user : targetUser) + " · " + mmss(m.ms())
                            // 体积/速度放前面：文件名一长，单行里先被吃掉的就是它
                            : m.summary() + " · " + fileName(res);
                    ok++;
                } else {
                    r.state = ST_BAD;
                    r.detail = res.replace("ERR ", "");
                    bad++;
                }
                done = i + 1;
                line(r.time + "  " + stateName(r.state) + "  " + labelOf(r.pkg) + "  " + r.detail);
            }
        } finally {
            int skip = 0;
            for (Row r : rows) if (r.state == ST_SKIP) skip++;
            summary = ctx.getString(R.string.batch_done_summary, ok, bad)
                    + (skip > 0 ? " · " + ctx.getString(R.string.batch_skipped_n, skip) : "");
            line("—— " + summary + " ——");
            head = "";
            speed = "";
            running = false;
            finished = true;
            saveLog();
            Notify.finish(ctx, restore, summary, bad > 0);
            try {
                if (wake != null && wake.isHeld()) wake.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // ── 日志 ─────────────────────────────────────────────────────────
    private void line(String s) {
        synchronized (log) {
            if (log.length() > 0) log.append('\n');
            log.append(s);
        }
    }

    public String logText() {
        synchronized (log) {
            return log.toString();
        }
    }

    public String headerLine() {
        return (restore ? ctx.getString(R.string.batch_restore_title)
                : ctx.getString(R.string.batch_backup_title))
                + "  " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date(startedAt));
    }

    /** 落一份到应用私有目录，进程重启后还能在「数据备份 → 日志」里翻到 */
    public void saveLog() {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), LOG_NAME);
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(headerLine() + "\n\n" + logText() + "\n\n" + summary + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────────
    private static android.content.SharedPreferences sp(Context c) {
        return c.getSharedPreferences("clone", Context.MODE_PRIVATE);
    }

    static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    static String mmss(long ms) {
        long s = ms / 1000, m = s / 60, r = s % 60;
        return m > 0 ? String.format(Locale.US, "%d:%02d", m, r)
                : String.format(Locale.US, "%ds", r);
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024L) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    private String labelOf(String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private String spaceLabel(int u) {
        String n = u == 0 ? ctx.getString(R.string.space_main) : CloneStore.nameOf(ctx, u);
        return n + " (" + u + ")";
    }

    /** "OK <路径>|<算法>" 只取文件名 */
    private static String fileName(String r) {
        String rest = r.substring(3).trim();
        int bar = rest.lastIndexOf('|');
        String p = bar > 0 ? rest.substring(0, bar) : rest;
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private int appIdOf(String pkg) {
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0).uid % 100000;
        } catch (Throwable t) {
            return -1;
        }
    }

    public String stateName(int st) {
        if (st == ST_OK) return ctx.getString(R.string.batch_state_ok);
        if (st == ST_BAD) return ctx.getString(R.string.batch_state_bad);
        if (st == ST_SKIP) return ctx.getString(R.string.batch_state_skip);
        if (st == ST_RUN) return ctx.getString(R.string.batch_state_running);
        return ctx.getString(R.string.batch_state_wait);
    }

    /**
     * 备份测速：每秒读一次产物大小。备份是 root 一次跑完的 tar，应用这边拿不到进度回调，
     * 所以反过来量产物——只读，不影响备份本身。恢复没有产物可量，只报已用时间。
     */
    private class Meter {
        final String base;

        private volatile boolean stop;
        private volatile long bytes;
        private final long t0 = System.currentTimeMillis();
        private Thread th;

        Meter(String base) { this.base = base; }

        void begin() {
            th = new Thread(new Runnable() {
                public void run() { loop(); }
            });
            th.setDaemon(true);
            th.start();
        }

        void stop() { stop = true; }

        long ms() { return System.currentTimeMillis() - t0; }

        String summary() {
            long m = Math.max(1, ms());
            return size(bytes) + " · " + size(bytes * 1000L / m) + "/s · " + mmss(m);
        }

        private void loop() {
            final String cmd = "B='" + base + "'; "
                    + "for e in .tar.zst .tar.gz .tar.bz2 .tar; do "
                    + "S=$(nsenter -t 1 -m -- stat -c %s \"$B$e\" 2>/dev/null"
                    + " || stat -c %s \"$B$e\" 2>/dev/null); "
                    + "[ -n \"$S\" ] && { echo \"$S|$e\"; exit 0; }; done; echo 0";
            long lastB = 0, lastT = System.currentTimeMillis();
            double ema = 0;
            while (!stop) {
                try {
                    Thread.sleep(1000);
                } catch (Throwable t) {
                    return;
                }
                if (stop) return;
                if (base == null) {
                    speed = ctx.getString(R.string.batch_elapsed, mmss(ms()));
                    Notify.progress(ctx, restore, done, rows.size(), head, speed);
                    continue;
                }
                String out = Shell.execQuick(cmd).trim();
                long cur = 0;
                int bar = out.indexOf('|');
                if (bar > 0) {

                    try {
                        cur = Long.parseLong(out.substring(0, bar));
                    } catch (Throwable ignored) {
                    }
                }
                long tn = System.currentTimeMillis();
                double dt = (tn - lastT) / 1000.0;
                if (dt > 0 && cur >= lastB) {
                    double inst = (cur - lastB) / dt;
                    ema = ema == 0 ? inst : ema * 0.6 + inst * 0.4;
                }
                lastB = cur;
                lastT = tn;
                bytes = cur;
                speed = ctx.getString(R.string.batch_speed, size((long) ema), size(cur), mmss(ms()));
                Notify.progress(ctx, restore, done, rows.size(), head, speed);
            }
        }
    }
}
