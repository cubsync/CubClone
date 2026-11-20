package com.cubsync.cubclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量备份：选一个空间，勾要备份的应用，一次跑完。
 *
 * 交互跟「数据备份」列表同一套：搜索、多选、全选/反选，底部固定显示已选数量和开始按钮。
 * 从「数据备份」页右上角的「＋」进来，默认主空间，进页面就能在顶部换空间。
 */
public class BatchBackupActivity extends Activity {

    private LinearLayout root;
    private LinearLayout header;
    private TextView title;
    private TextView spaceTv;
    private TextView selAll;
    private TextView selInvert;
    private TextView countTv;
    private TextView goTv;
    private TextView empty;
    private ImageButton btnBack;
    private EditText search;
    private ListView list;
    private View divider;

    private final List<Integer> spaces = new ArrayList<Integer>();
    private final List<String> pkgs = new ArrayList<String>();
    private final List<String> labels = new ArrayList<String>();
    private final List<Integer> shown = new ArrayList<Integer>();
    private final Set<String> checked = new HashSet<String>();
    private final java.util.HashMap<String, String> labelCache = new java.util.HashMap<String, String>();

    private int user = 0;
    private String query = "";
    private boolean running = false;

    private Adapter adapter;
    private int fg = 0xFF1B1B1F;
    private int sec = 0xFF5F6368;
    private int accent = 0xFF0B57D0;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_batch_backup);

        root = findViewById(R.id.bb_root);
        header = findViewById(R.id.bb_header);
        title = findViewById(R.id.bb_title);
        spaceTv = findViewById(R.id.bb_space);
        selAll = findViewById(R.id.bb_sel_all);
        selInvert = findViewById(R.id.bb_sel_invert);
        countTv = findViewById(R.id.bb_count);
        goTv = findViewById(R.id.bb_go);
        empty = findViewById(R.id.bb_empty);
        btnBack = findViewById(R.id.bb_back);
        search = findViewById(R.id.bb_search);
        list = findViewById(R.id.bb_list);
        divider = findViewById(R.id.bb_divider);

        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        spaceTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickSpace(); }
        });
        selAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleAll(); }
        });
        selInvert.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { invert(); }
        });
        goTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startBatch(); }
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase();
                refilter();
            }

            public void afterTextChanged(Editable s) { }
        });

        adapter = new Adapter();
        list.setAdapter(adapter);
        applyChrome();
        loadSpaces();
        loadApps();
    }

    protected void onResume() {
        super.onResume();
        applyChrome();
    }

    // ── 空间与应用 ───────────────────────────────────────────────────
    private void loadSpaces() {
        new Thread(new Runnable() {
            public void run() {
                final List<Integer> r = new ArrayList<Integer>();
                r.add(Integer.valueOf(0));
                for (Integer u : CloneStore.listSpaceUids()) r.add(u);
                runOnUiThread(new Runnable() {
                    public void run() {
                        spaces.clear();
                        spaces.addAll(r);
                        renderSpace();
                    }
                });
            }
        }).start();
    }

    private void pickSpace() {
        if (spaces.isEmpty()) return;
        String[] items = new String[spaces.size()];
        int cur = 0;
        for (int i = 0; i < spaces.size(); i++) {
            items[i] = spaceLabel(spaces.get(i).intValue());
            if (spaces.get(i).intValue() == user) cur = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_pick_space)
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        int u = spaces.get(which).intValue();
                        if (u == user) return;
                        user = u;
                        checked.clear();
                        renderSpace();
                        loadApps();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void loadApps() {
        final int u = user;
        empty.setVisibility(View.VISIBLE);
        empty.setText(R.string.backup_working);
        list.setVisibility(View.GONE);
        new Thread(new Runnable() {
            public void run() {
                java.util.TreeSet<String> uniq = new java.util.TreeSet<String>();
                String out = Shell.exec("pm list packages -3 --user " + u + " 2>/dev/null");
                for (String line : out.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("package:")) uniq.add(t.substring(8).trim());
                }
                final List<String> ps = new ArrayList<String>();
                final List<String> ls = new ArrayList<String>();
                for (String p : uniq) {
                    ps.add(p);
                    ls.add(labelOf(p));
                }
                java.util.List<Integer> order = new ArrayList<Integer>();
                for (int i = 0; i < ps.size(); i++) order.add(Integer.valueOf(i));
                java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
                    public int compare(Integer a, Integer b) {
                        return ls.get(a.intValue()).compareToIgnoreCase(ls.get(b.intValue()));
                    }
                });
                final List<String> sp = new ArrayList<String>();
                final List<String> sl = new ArrayList<String>();
                for (Integer i : order) {
                    sp.add(ps.get(i.intValue()));
                    sl.add(ls.get(i.intValue()));
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (u != user) return;   // 半路换了空间，丢掉旧结果
                        pkgs.clear();
                        pkgs.addAll(sp);
                        labels.clear();
                        labels.addAll(sl);
                        checked.clear();
                        refilter();
                    }
                });
            }
        }).start();
    }

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

    private void renderSpace() {
        spaceTv.setText(spaceLabel(user) + " ▾");
    }

    // ── 列表与选择 ───────────────────────────────────────────────────
    private void refilter() {
        shown.clear();
        for (int i = 0; i < pkgs.size(); i++) {
            if (query.length() > 0 && !labels.get(i).toLowerCase().contains(query)
                    && !pkgs.get(i).toLowerCase().contains(query)) continue;
            shown.add(Integer.valueOf(i));
        }
        // 过滤掉的项取消勾选，免得备份到看不见的东西
        Set<String> keep = new HashSet<String>();
        for (Integer i : shown) {
            String p = pkgs.get(i.intValue());
            if (checked.contains(p)) keep.add(p);
        }
        checked.retainAll(keep);

        adapter.notifyDataSetChanged();
        boolean none = shown.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        if (none) {
            empty.setText(pkgs.isEmpty() ? getString(R.string.backup_batch_empty)
                    : getString(R.string.backup_no_match));
        }
        renderBar();
    }

    private void renderBar() {
        int n = checked.size();
        countTv.setText(getString(R.string.backup_sel_count, n));
        boolean all = shown.size() > 0 && n == shown.size();
        selAll.setText(all ? R.string.backup_sel_none : R.string.backup_sel_all);
        goTv.setAlpha(n > 0 && !running ? 1f : 0.4f);
    }

    private void toggleAll() {
        boolean all = shown.size() > 0 && checked.size() == shown.size();
        checked.clear();
        if (!all) {
            for (Integer i : shown) checked.add(pkgs.get(i.intValue()));
        }
        adapter.notifyDataSetChanged();
        renderBar();
    }

    /** 反选：只看当前过滤出来的这批 */
    private void invert() {
        for (Integer i : shown) {
            String p = pkgs.get(i.intValue());
            if (checked.contains(p)) checked.remove(p);
            else checked.add(p);
        }
        adapter.notifyDataSetChanged();
        renderBar();
    }

    // ── 备份 ─────────────────────────────────────────────────────────
    private void startBatch() {
        if (running) return;
        final List<String> sel = new ArrayList<String>();
        for (String p : pkgs) {
            if (checked.contains(p)) sel.add(p);
        }
        if (sel.isEmpty()) {
            toast(getString(R.string.backup_batch_none));
            return;
        }
        final String algo = sp().getString("backup_algo", "zst");
        final int lv = sp().getInt("backup_level_" + algo, AppData.defaultLevel(algo));
        running = true;
        renderBar();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(new Runnable() {
            public void run() {
                int ok = 0, bad = 0;
                for (int i = 0; i < sel.size(); i++) {
                    final int no = i + 1, total = sel.size();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            title.setText(getString(R.string.backup_batch_running, no, total));
                        }
                    });
                    String r = AppData.backupNow(user, sel.get(i), algo, lv);
                    if (r.startsWith("OK ")) ok++;
                    else bad++;
                }
                final int fok = ok, fbad = bad;
                runOnUiThread(new Runnable() {
                    public void run() {
                        running = false;
                        getWindow().clearFlags(
                                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        title.setText(R.string.batch_backup_title);
                        toast(getString(R.string.backup_done_many, fok, fbad));
                        checked.clear();
                        adapter.notifyDataSetChanged();
                        renderBar();
                    }
                });
            }
        }).start();
    }

    // ── 列表适配 ─────────────────────────────────────────────────────
    private class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }

        public Object getItem(int i) { return shown.get(i); }

        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(BatchBackupActivity.this)
                        .inflate(R.layout.item_backup, parent, false);
            }
            final String pkg = pkgs.get(shown.get(i).intValue());
            TextView t1 = v.findViewById(R.id.item_title);
            TextView t2 = v.findViewById(R.id.item_sub);
            TextView more = v.findViewById(R.id.item_more);
            CheckBox check = v.findViewById(R.id.item_check);

            t1.setText(labelOf(pkg));
            t1.setTextColor(fg);
            t2.setText(pkg);
            t2.setTextColor(sec);
            more.setVisibility(View.GONE);
            check.setVisibility(View.VISIBLE);
            check.setChecked(checked.contains(pkg));

            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    if (checked.contains(pkg)) checked.remove(pkg);
                    else checked.add(pkg);
                    adapter.notifyDataSetChanged();
                    renderBar();
                }
            });
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

        root.setBackgroundColor(bg);
        header.setBackgroundColor(bg);
        title.setTextColor(fg);
        btnBack.setColorFilter(fg);
        spaceTv.setTextColor(accent);
        selAll.setTextColor(accent);
        selInvert.setTextColor(accent);
        countTv.setTextColor(sec);
        goTv.setTextColor(accent);
        divider.setBackgroundColor(line);
        empty.setTextColor(sec);
        list.setBackgroundColor(bg);
        search.setTextColor(fg);
        search.setHintTextColor(sec);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
