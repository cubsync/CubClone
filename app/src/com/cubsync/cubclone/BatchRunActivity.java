package com.cubsync.cubclone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量备份 / 恢复的**过程页**。
 *
 * 任务本体在 {@link BatchJob} 里，页面只是它的一个视图：随时可以退出去干别的，
 * 任务继续跑；回来看（从「数据备份 → 日志」进）还是同一份进度和日志。
 *
 * 新建任务走 intent（backup: pkgs+user / restore: paths），
 * 不传 extras 时就是**挂到当前任务上**看。
 */
public class BatchRunActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_PKGS = "pkgs";
    public static final String EXTRA_PATHS = "paths";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_TARGET_USER = "target_user";
    public static final String EXTRA_PARTS = "parts";
    public static final String MODE_BACKUP = "backup";
    public static final String MODE_RESTORE = "restore";

    private static final int FILTER_ALL = 0;
    private static final int FILTER_OK = 1;
    private static final int FILTER_BAD = 2;

    private TextView title;
    private TextView countTv;
    private ImageButton btnBack;
    private ProgressBar progress;
    private View filters;
    private TextView fAll, fOk, fBad;
    private ListView list;
    private TextView empty;
    private TextView summary;
    private TextView copyTv;
    private TextView doneTv;
    private TextView speedTv;
    private TextView logTv;
    private android.widget.ScrollView logScroll;
    private final List<Integer> shown = new ArrayList<Integer>();

    private BatchJob job;
    private int filter = FILTER_ALL;
    private int logSeen = 0;
    private final Handler tick = new Handler();

    private Adapter adapter;
    private int fg = 0xFF1B1B1F;
    private int sec = 0xFF5F6368;
    private int accent = 0xFF0B57D0;
    private int green = 0xFF2E7D32;
    private int red = 0xFFC62828;

    private final Runnable poll = new Runnable() {
        public void run() {
            render();
            if (job != null && job.running) tick.postDelayed(this, 500);
        }
    };

    public void onCreate(Bundle b) {
        super.onCreate(b);
        CloneStore.attach(this);
        AppData.loadRoot(sp());
        setContentView(R.layout.activity_batch_run);

        title = findViewById(R.id.br_title);
        countTv = findViewById(R.id.br_count);
        btnBack = findViewById(R.id.br_back);
        progress = findViewById(R.id.br_progress);
        filters = findViewById(R.id.br_filters);
        fAll = findViewById(R.id.br_f_all);
        fOk = findViewById(R.id.br_f_ok);
        fBad = findViewById(R.id.br_f_bad);
        list = findViewById(R.id.br_list);
        empty = findViewById(R.id.br_empty);
        summary = findViewById(R.id.br_summary);
        copyTv = findViewById(R.id.br_copy);
        doneTv = findViewById(R.id.br_done);
        speedTv = findViewById(R.id.br_speed);
        logTv = findViewById(R.id.br_log);
        logScroll = findViewById(R.id.br_log_scroll);

        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { leave(); }
        });
        doneTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { leave(); }
        });
        copyTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyLog(); }
        });
        fAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { filter = FILTER_ALL; refilter(); }
        });
        fOk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { filter = FILTER_OK; refilter(); }
        });
        fBad.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { filter = FILTER_BAD; refilter(); }
        });

        adapter = new Adapter();
        list.setAdapter(adapter);

        if (!applyIntent(getIntent())) job = BatchJob.get();   // 没带参数 = 挂到当前任务上看

        applyChrome();
        if (job == null || job.rows.isEmpty()) {
            list.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            doneTv.setText(R.string.batch_close);
            String saved = readSavedLog();
            if (saved.length() > 0) {          // 进程重启过：至少还能看到上次那份
                logTv.setText(saved);
                logScroll.post(new Runnable() {
                    public void run() { logScroll.fullScroll(View.FOCUS_UP); }
                });
            } else {
                empty.setVisibility(View.VISIBLE);
                empty.setText(job == null ? R.string.batch_log_none : R.string.batch_nothing);
            }
            return;
        }
        render();
        tick.post(poll);
    }

    /**
     * 带参数进来就起新任务；返回 true 表示起了新的。
     * 单独拎出来是因为 Activity 已经存在时 am start 只会走 onNewIntent，
     * 不处理的话第二次点「开始」什么都不会发生。
     */
    private boolean applyIntent(Intent it) {
        String mode = it.getStringExtra(EXTRA_MODE);
        boolean backup = MODE_BACKUP.equals(mode), restore = MODE_RESTORE.equals(mode);
        if (!backup && !restore) return false;
        if (job != null && job.running) {
            toast(getString(R.string.batch_busy));
            return true;
        }
        if (backup) {
            String[] pkgs = it.getStringArrayExtra(EXTRA_PKGS);
            List<String> l = new ArrayList<String>();
            if (pkgs != null) for (String p : pkgs) l.add(p);
            job = BatchJob.startBackup(this, it.getIntExtra(EXTRA_USER, 0), l);
        } else {
            String[] paths = it.getStringArrayExtra(EXTRA_PATHS);
            List<String> l = new ArrayList<String>();
            if (paths != null) for (String p : paths) l.add(p);
            job = BatchJob.startRestore(this, l,
                    it.getIntExtra(EXTRA_TARGET_USER, -1),
                    it.getIntExtra(EXTRA_PARTS,
                            AppData.PART_CE | AppData.PART_DE | AppData.PART_APK | AppData.PART_PERM));
        }
        return true;
    }

    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        if (applyIntent(it)) {
            logSeen = 0;
            render();
            tick.removeCallbacks(poll);
            tick.post(poll);
        }
    }

    protected void onDestroy() {
        tick.removeCallbacks(poll);
        super.onDestroy();
    }

    // ── 渲染 ─────────────────────────────────────────────────────────
    private void render() {
        if (job == null) return;
        boolean run = job.running;

        progress.setVisibility(run ? View.VISIBLE : View.GONE);
        progress.setMax(job.rows.size());
        progress.setProgress(run ? Math.max(1, job.done) : job.rows.size());
        countTv.setText(run ? Math.min(job.done + 1, job.rows.size()) + "/" + job.rows.size() : "");
        title.setText(run && job.head.length() > 0 ? job.head
                : getString(job.restore ? R.string.batch_restore_title : R.string.batch_backup_title));

        speedTv.setVisibility(run && job.speed.length() > 0 ? View.VISIBLE : View.GONE);
        speedTv.setText(job.speed);

        filters.setVisibility(run ? View.GONE : View.VISIBLE);
        summary.setText(run ? getString(R.string.batch_running_hint) : job.summary);
        doneTv.setText(R.string.batch_close);

        String log = job.logText();
        if (log.length() != logSeen) {
            logSeen = log.length();
            logTv.setText(log);
            logScroll.post(new Runnable() {
                public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
            });
        }
        refilter();
    }

    private void refilter() {
        shown.clear();
        if (job != null) {
            for (int i = 0; i < job.rows.size(); i++) {
                int st = job.rows.get(i).state;
                if (filter == FILTER_OK && st != BatchJob.ST_OK) continue;
                if (filter == FILTER_BAD && st != BatchJob.ST_BAD) continue;
                shown.add(Integer.valueOf(i));
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        boolean none = shown.isEmpty();
        if (job != null && !job.rows.isEmpty()) {
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            list.setVisibility(none ? View.GONE : View.VISIBLE);
            if (none) {
                empty.setText(filter == FILTER_BAD ? R.string.batch_no_fail
                        : filter == FILTER_OK ? R.string.batch_no_ok : R.string.batch_nothing);
            }
        }
        chrome();
    }

    private void chrome() {
        int ok = 0, bad = 0;
        if (job != null) {
            for (BatchJob.Row r : job.rows) {
                if (r.state == BatchJob.ST_OK) ok++;
                else if (r.state == BatchJob.ST_BAD) bad++;
            }
        }
        int total = job == null ? 0 : job.rows.size();
        fAll.setText(getString(R.string.batch_filter_all, total));
        fOk.setText(getString(R.string.batch_filter_ok, ok));
        fBad.setText(getString(R.string.batch_filter_bad, bad));
        int on = accent, off = sec;
        fAll.setTextColor(filter == FILTER_ALL ? on : off);
        fOk.setTextColor(filter == FILTER_OK ? on : off);
        fBad.setTextColor(filter == FILTER_BAD ? on : off);
        fAll.getPaint().setFakeBoldText(filter == FILTER_ALL);
        fOk.getPaint().setFakeBoldText(filter == FILTER_OK);
        fBad.getPaint().setFakeBoldText(filter == FILTER_BAD);
        fAll.invalidate();
        fOk.invalidate();
        fBad.invalidate();
    }

    private String readSavedLog() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), BatchJob.LOG_NAME);
            if (!f.exists()) return "";
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    // ── 动作 ─────────────────────────────────────────────────────────
    private void copyLog() {
        if (job == null) return;
        String text = job.headerLine() + "\n\n" + job.logText() + "\n\n" + job.summary;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("CubClone", text));
        toast(getString(R.string.batch_log_copied));
    }

    /** 退出页面 ≠ 停止任务：任务在进程里继续跑，回来还是这一页 */
    private void leave() {
        if (job != null && job.running) toast(getString(R.string.batch_leave_bg));
        finish();
    }

    public void onBackPressed() {
        leave();
    }

    // ── 适配 ─────────────────────────────────────────────────────────
    private class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }

        public Object getItem(int i) { return shown.get(i); }

        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(BatchRunActivity.this)
                        .inflate(R.layout.item_backup, parent, false);
            }
            BatchJob.Row r = job.rows.get(shown.get(i).intValue());
            TextView t1 = v.findViewById(R.id.item_title);
            TextView t2 = v.findViewById(R.id.item_sub);
            TextView more = v.findViewById(R.id.item_more);
            CheckBox check = v.findViewById(R.id.item_check);

            t1.setText(labelOf(r.pkg));
            t1.setTextColor(fg);
            StringBuilder sub = new StringBuilder();
            if (r.time.length() > 0) sub.append(r.time).append("  ");
            sub.append(job.stateName(r.state));
            if (r.state != BatchJob.ST_WAIT && r.state != BatchJob.ST_RUN) {
                sub.append(" · ").append(spaceLabel(r.user));
            }
            if (r.detail.length() > 0) sub.append(" · ").append(r.detail);
            t2.setText(sub.toString());
            t2.setTextColor(r.state == BatchJob.ST_BAD ? red
                    : r.state == BatchJob.ST_OK ? green : sec);
            more.setVisibility(View.GONE);
            check.setVisibility(View.GONE);
            v.setOnClickListener(null);
            return v;
        }
    }

    private String labelOf(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private String spaceLabel(int u) {
        String n = u == 0 ? getString(R.string.space_main) : CloneStore.nameOf(this, u);
        return n + " (" + u + ")";
    }

    // ── 配色 ─────────────────────────────────────────────────────────
    private SharedPreferences sp() {
        return getSharedPreferences("clone", MODE_PRIVATE);
    }

    private boolean isNight() {
        int t = sp().getInt("theme", 0);
        if (t == 2) return true;
        if (t == 1) return false;
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyChrome() {
        boolean night = isNight();
        boolean black = night && sp().getBoolean("amoled", false);
        int bg = black ? 0xFF000000 : (night ? 0xFF121317 : 0xFFFDFDFE);
        int line = black ? 0xFF1C1C1C : (night ? 0xFF2C2D31 : 0xFFE3E3E8);
        fg = night ? 0xFFEDEDED : 0xFF1B1B1F;
        sec = black ? 0xFF8A8F96 : (night ? 0xFF9AA0A6 : 0xFF5F6368);
        accent = night ? 0xFF9EC1FF : 0xFF0B57D0;
        green = night ? 0xFF7BC67E : 0xFF2E7D32;
        red = night ? 0xFFFF8A80 : 0xFFC62828;

        findViewById(R.id.br_root).setBackgroundColor(bg);
        findViewById(R.id.br_header).setBackgroundColor(bg);
        findViewById(R.id.br_divider).setBackgroundColor(line);
        title.setTextColor(fg);
        btnBack.setColorFilter(fg);
        countTv.setTextColor(sec);
        empty.setTextColor(sec);
        list.setBackgroundColor(bg);
        summary.setTextColor(sec);
        copyTv.setTextColor(accent);
        doneTv.setTextColor(accent);
        speedTv.setTextColor(sec);
        logTv.setTextColor(sec);
        ((TextView) findViewById(R.id.br_log_title)).setTextColor(sec);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
