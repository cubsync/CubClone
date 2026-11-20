package com.cubsync.cubclone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量备份/恢复的**过程页**：选完应用点开始后跳到这里。
 *
 * 为什么单独一页：跑的时候要看到每一项的状态（等待 / 进行中 / 成功 / 失败 + 原因），
 * 跑完要能按成功、失败筛，还要能把整份日志拷走。挤在选择页里两头都做不好。
 *
 * 两个模式共用这一页，靠 intent 的 mode 区分：
 *   backup  → 传 pkgs + user
 *   restore → 传 paths（同空间同应用有多份时这里只留最新那份）
 */
public class BatchRunActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_PKGS = "pkgs";
    public static final String EXTRA_PATHS = "paths";
    public static final String EXTRA_USER = "user";
    public static final String MODE_BACKUP = "backup";
    public static final String MODE_RESTORE = "restore";

    private static final int ST_WAIT = 0;
    private static final int ST_RUN = 1;
    private static final int ST_OK = 2;
    private static final int ST_BAD = 3;
    private static final int ST_SKIP = 4;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_OK = 1;
    private static final int FILTER_BAD = 2;

    private static class Row {
        String pkg;
        int user;
        String path;
        int state = ST_WAIT;
        String detail = "";

        Row(String pkg, int user, String path) {
            this.pkg = pkg;
            this.user = user;
            this.path = path;
        }
    }

    private LinearLayout root;
    private LinearLayout header;
    private TextView title;
    private TextView countTv;
    private ImageButton btnBack;
    private ProgressBar progress;
    private LinearLayout filters;
    private TextView fAll, fOk, fBad;
    private ListView list;
    private TextView empty;
    private View divider;
    private TextView summary;
    private TextView copyTv;
    private TextView doneTv;

    private final List<Row> rows = new ArrayList<Row>();
    private final List<Integer> shown = new ArrayList<Integer>();
    private final Map<String, String> labelCache = new java.util.HashMap<String, String>();

    private boolean restore = false;
    private boolean running = false;
    private boolean finished = false;
    private int filter = FILTER_ALL;

    private Adapter adapter;
    private int fg = 0xFF1B1B1F;
    private int sec = 0xFF5F6368;
    private int accent = 0xFF0B57D0;
    private int green = 0xFF2E7D32;
    private int red = 0xFFC62828;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        AppData.loadRoot(sp());
        setContentView(R.layout.activity_batch_run);

        root = findViewById(R.id.br_root);
        header = findViewById(R.id.br_header);
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
        divider = findViewById(R.id.br_divider);
        summary = findViewById(R.id.br_summary);
        copyTv = findViewById(R.id.br_copy);
        doneTv = findViewById(R.id.br_done);

        Intent it = getIntent();
        restore = MODE_RESTORE.equals(it.getStringExtra(EXTRA_MODE));
        if (restore) collectRestore(it.getStringArrayExtra(EXTRA_PATHS));
        else collectBackup(it.getStringArrayExtra(EXTRA_PKGS), it.getIntExtra(EXTRA_USER, 0));

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
            public void onClick(View v) { setFilter(FILTER_ALL); }
        });
        fOk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setFilter(FILTER_OK); }
        });
        fBad.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setFilter(FILTER_BAD); }
        });

        adapter = new Adapter();
        list.setAdapter(adapter);
        applyChrome();
        title.setText(restore ? getString(R.string.batch_restore_title)
                : getString(R.string.batch_backup_title));

        if (rows.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(R.string.batch_nothing);
            doneTv.setText(R.string.batch_close);
            return;
        }
        start();
    }

    /** 备份：pkgs 原样进来 */
    private void collectBackup(String[] pkgs, int user) {
        if (pkgs == null) return;
        for (String p : pkgs) rows.add(new Row(p, user, ""));
    }

    /**
     * 恢复：同空间同应用有多份时只留最新那份。
     * 按 ts 从旧到新依次恢复，结果是旧的覆盖新的，等于白跑还丢了新数据。
     */
    private void collectRestore(String[] paths) {
        if (paths == null) return;
        LinkedHashMap<String, Row> newest = new LinkedHashMap<String, Row>();
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
                // 被跳过的记一行，跑完在日志里能看到为什么没恢复
                Row skip = new Row(bk.pkg, bk.user, p);
                skip.state = ST_SKIP;
                skip.detail = getString(R.string.batch_skip_older, bk.ts);
                rows.add(skip);
            }
        }
        // 待恢复的放前面，跳过的跟在后面
        List<Row> todo = new ArrayList<Row>(newest.values());
        rows.addAll(0, todo);
    }

    // ── 跑 ───────────────────────────────────────────────────────────
    private void start() {
        running = true;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setMax(rows.size());
        progress.setProgress(0);
        refreshChrome();
        new Thread(new Runnable() {
            public void run() {
                final String algo = sp().getString("backup_algo", "zst");
                final int lv = sp().getInt("backup_level_" + algo, AppData.defaultLevel(algo));
                int ok = 0, bad = 0, skipDone = 0;
                for (int i = 0; i < rows.size(); i++) {
                    final Row r = rows.get(i);
                    if (r.state == ST_SKIP) {
                        skipDone++;
                        continue;
                    }
                    r.state = ST_RUN;
                    final int no = i + 1, total = rows.size();
                    refreshRow();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            title.setText(getString(restore ? R.string.batch_restoring_n
                                    : R.string.batch_backing_n, no, total));
                            countTv.setText(no + "/" + total);
                            progress.setProgress(no);
                        }
                    });
                    AppData.Backup bk = restore ? AppData.fromPath(r.path) : null;
                    String res = restore
                            ? (bk == null ? "ERR_PATH" : AppData.restore(bk, appIdOf(r.pkg)))
                            : AppData.backupNow(r.user, r.pkg, algo, lv);
                    if (res.startsWith("OK")) {
                        r.state = ST_OK;
                        r.detail = restore ? spaceLabel(r.user)
                                : fileName(res);
                        ok++;
                    } else {
                        r.state = ST_BAD;
                        r.detail = res.replace("ERR ", "");
                        bad++;
                    }
                    refreshRow();
                }
                final int fok = ok, fbad = bad;
                runOnUiThread(new Runnable() {
                    public void run() {
                        running = false;
                        finished = true;
                        getWindow().clearFlags(
                                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        title.setText(restore ? getString(R.string.batch_restore_title)
                                : getString(R.string.batch_backup_title));
                        countTv.setText("");
                        progress.setVisibility(View.GONE);
                        filters.setVisibility(View.VISIBLE);
                        copyTv.setVisibility(View.VISIBLE);
                        doneTv.setText(R.string.batch_close);
                        // 有失败就直接筛到失败那档，别让人再找一遍
                        setFilter(fbad > 0 ? FILTER_BAD : FILTER_ALL);
                        if (fbad == 0) {
                            toast(getString(R.string.batch_done_all_ok, fok));
                        } else {
                            toast(getString(R.string.batch_done_summary, fok, fbad));
                        }
                        refreshChrome();
                    }
                });
            }
        }).start();
    }

    private int appIdOf(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0).uid % 100000;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** "OK <路径>|<算法>" 只取文件名 */
    private String fileName(String r) {
        String rest = r.substring(3).trim();
        int bar = rest.lastIndexOf('|');
        String p = bar > 0 ? rest.substring(0, bar) : rest;
        return p.substring(p.lastIndexOf('/') + 1);
    }

    // ── 列表 ─────────────────────────────────────────────────────────
    private void setFilter(int f) {
        filter = f;
        refilter();
        refreshChrome();
    }

    private void refilter() {
        shown.clear();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (filter == FILTER_OK && r.state != ST_OK) continue;
            if (filter == FILTER_BAD && r.state != ST_BAD) continue;
            shown.add(Integer.valueOf(i));
        }
        adapter.notifyDataSetChanged();
        boolean none = shown.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        if (none) {
            empty.setText(filter == FILTER_BAD ? R.string.batch_no_fail
                    : filter == FILTER_OK ? R.string.batch_no_ok : R.string.batch_nothing);
        }
    }

    private void refreshRow() {
        runOnUiThread(new Runnable() {
            public void run() { adapter.notifyDataSetChanged(); }
        });
    }

    private void refreshChrome() {
        int ok = 0, bad = 0, skip = 0;
        for (Row r : rows) {
            if (r.state == ST_OK) ok++;
            else if (r.state == ST_BAD) bad++;
            else if (r.state == ST_SKIP) skip++;
        }
        if (finished) {
            summary.setText(getString(R.string.batch_done_summary, ok, bad)
                    + (skip > 0 ? " · " + getString(R.string.batch_skipped_n, skip) : ""));
        } else {
            summary.setText(getString(R.string.batch_running_hint));
        }
        fAll.setText(getString(R.string.batch_filter_all, rows.size()));
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

    // ── 日志 ─────────────────────────────────────────────────────────
    private void copyLog() {
        StringBuilder sb = new StringBuilder();
        sb.append(restore ? getString(R.string.batch_restore_title)
                : getString(R.string.batch_backup_title)).append("  ")
                .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.US).format(new java.util.Date())).append('\n');
        for (Row r : rows) {
            sb.append('[').append(stateName(r.state)).append("] ")
                    .append(labelOf(r.pkg)).append(" · ").append(spaceLabel(r.user));
            if (r.detail.length() > 0) sb.append(" · ").append(r.detail);
            sb.append('\n');
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("CubClone", sb.toString()));
        toast(getString(R.string.batch_log_copied));
    }

    private String stateName(int st) {
        if (st == ST_OK) return getString(R.string.batch_state_ok);
        if (st == ST_BAD) return getString(R.string.batch_state_bad);
        if (st == ST_SKIP) return getString(R.string.batch_state_skip);
        if (st == ST_RUN) return getString(R.string.batch_state_running);
        return getString(R.string.batch_state_wait);
    }

    // ── 返回 ─────────────────────────────────────────────────────────
    private void leave() {
        if (running) {
            toast(getString(R.string.batch_still_running));
            return;
        }
        finish();
    }

    public void onBackPressed() {
        leave();
    }

    // ── 适配 ─────────────────────────────────────────────────────────
    private String labelOf(String pkg) {
        String c = labelCache.get(pkg);
        if (c != null) return c;
        String r;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            r = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            r = pkg;
        }
        labelCache.put(pkg, r);
        return r;
    }

    private String spaceLabel(int u) {
        String n = u == 0 ? getString(R.string.space_main) : CloneStore.nameOf(this, u);
        return n + " (" + u + ")";
    }

    private class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }

        public Object getItem(int i) { return shown.get(i); }

        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(BatchRunActivity.this)
                        .inflate(R.layout.item_backup, parent, false);
            }
            Row r = rows.get(shown.get(i).intValue());
            TextView t1 = v.findViewById(R.id.item_title);
            TextView t2 = v.findViewById(R.id.item_sub);
            TextView more = v.findViewById(R.id.item_more);
            CheckBox check = v.findViewById(R.id.item_check);

            t1.setText(labelOf(r.pkg));
            t1.setTextColor(fg);
            StringBuilder sub = new StringBuilder();
            sub.append(stateName(r.state));
            if (r.state != ST_WAIT && r.state != ST_RUN) {
                sub.append(" · ").append(spaceLabel(r.user));
            }
            if (r.detail.length() > 0) sub.append(" · ").append(r.detail);
            t2.setText(sub.toString());
            t2.setTextColor(r.state == ST_BAD ? red : r.state == ST_OK ? green : sec);
            more.setVisibility(View.GONE);
            check.setVisibility(View.GONE);
            v.setOnClickListener(null);
            return v;
        }
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

        root.setBackgroundColor(bg);
        header.setBackgroundColor(bg);
        title.setTextColor(fg);
        btnBack.setColorFilter(fg);
        countTv.setTextColor(sec);
        divider.setBackgroundColor(line);
        empty.setTextColor(sec);
        list.setBackgroundColor(bg);
        summary.setTextColor(sec);
        copyTv.setTextColor(accent);
        doneTv.setTextColor(accent);
        refreshChrome();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
