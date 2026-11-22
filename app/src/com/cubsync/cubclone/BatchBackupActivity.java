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
    private final List<Integer> rowUsers = new ArrayList<Integer>();
    private final List<Integer> shown = new ArrayList<Integer>();
    /** 勾选按「空间 + 包名」记：同一个应用在两个空间里是两条 */
    private final Set<String> checked = new HashSet<String>();
    private final java.util.HashMap<String, String> labelCache = new java.util.HashMap<String, String>();

    /** 选中的空间，可多选（主空间 + 某几个分身） */
    private final java.util.LinkedHashSet<Integer> picked = new java.util.LinkedHashSet<Integer>();
    private String query = "";
    private boolean running = false;

    private Adapter adapter;
    private int fg = 0xFF1B1B1F;
    private int sec = 0xFF5F6368;
    private int accent = 0xFF0B57D0;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        AppData.loadRoot(sp());
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
                        if (picked.isEmpty()) picked.add(Integer.valueOf(0));
                        // 空间被删过：把已经不存在的选择丢掉，实在空了就回主空间
                        picked.retainAll(r);
                        if (picked.isEmpty()) picked.add(Integer.valueOf(0));
                        renderSpace();
                        loadApps();
                    }
                });
            }
        }).start();
    }

    /** 空间筛选：多选，主空间 + 任意几个分身可以一起备份 */
    private void pickSpace() {
        if (spaces.isEmpty()) return;
        final String[] items = new String[spaces.size()];
        final boolean[] on = new boolean[spaces.size()];
        for (int i = 0; i < spaces.size(); i++) {
            items[i] = spaceLabel(spaces.get(i).intValue());
            on[i] = picked.contains(spaces.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_pick_space)
                .setMultiChoiceItems(items, on, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        on[which] = isChecked;
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        java.util.LinkedHashSet<Integer> next = new java.util.LinkedHashSet<Integer>();
                        for (int i = 0; i < spaces.size(); i++) {
                            if (on[i]) next.add(spaces.get(i));
                        }
                        if (next.isEmpty()) next.add(Integer.valueOf(0));   // 一个都不选就当主空间
                        if (next.equals(picked)) return;
                        picked.clear();
                        picked.addAll(next);
                        renderSpace();
                        loadApps();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void loadApps() {
        final List<Integer> us = new ArrayList<Integer>(picked);
        empty.setVisibility(View.VISIBLE);
        empty.setText(R.string.backup_working);
        list.setVisibility(View.GONE);
        new Thread(new Runnable() {
            public void run() {
                List<String> ps = new ArrayList<String>();
                List<String> ls = new ArrayList<String>();
                List<Integer> ru = new ArrayList<Integer>();
                for (Integer u : us) {
                    java.util.TreeSet<String> uniq = new java.util.TreeSet<String>();
                    String out = Shell.exec("pm list packages -3 --user " + u + " 2>/dev/null");
                    for (String line : out.split("\n")) {
                        String t = line.trim();
                        if (t.startsWith("package:")) uniq.add(t.substring(8).trim());
                    }
                    for (String p : uniq) {
                        ps.add(p);
                        ls.add(labelOf(p));
                        ru.add(u);
                    }
                }
                java.util.List<Integer> order = new ArrayList<Integer>();
                for (int i = 0; i < ps.size(); i++) order.add(Integer.valueOf(i));
                java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
                    public int compare(Integer a, Integer b) {
                        int c = ls.get(a.intValue()).compareToIgnoreCase(ls.get(b.intValue()));
                        if (c != 0) return c;
                        int ua = ru.get(a.intValue()).intValue(), ub = ru.get(b.intValue()).intValue();
                        return ua == ub ? 0 : (ua < ub ? -1 : 1);
                    }
                });
                final List<String> sp = new ArrayList<String>();
                final List<String> sl = new ArrayList<String>();
                final List<Integer> su = new ArrayList<Integer>();
                for (Integer i : order) {
                    sp.add(ps.get(i.intValue()));
                    sl.add(ls.get(i.intValue()));
                    su.add(ru.get(i.intValue()));
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!new ArrayList<Integer>(picked).equals(us)) return;   // 半路换了空间，丢掉旧结果
                        pkgs.clear();
                        pkgs.addAll(sp);
                        labels.clear();
                        labels.addAll(sl);
                        rowUsers.clear();
                        rowUsers.addAll(su);
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
        StringBuilder sb = new StringBuilder();
        if (picked.size() <= 2) {
            for (Integer u : picked) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append(spaceLabel(u.intValue()));
            }
        } else {
            sb.append(getString(R.string.backup_spaces_n, picked.size()));
        }
        spaceTv.setText(sb + " ▾");
    }

    private static String key(int u, String pkg) {
        return u + "|" + pkg;
    }

    // ── 列表与选择 ───────────────────────────────────────────────────
    private void refilter() {
        shown.clear();
        for (int i = 0; i < pkgs.size(); i++) {
            if (query.length() > 0 && !labels.get(i).toLowerCase().contains(query)
                    && !pkgs.get(i).toLowerCase().contains(query)) continue;
            shown.add(Integer.valueOf(i));
        }
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
            for (Integer i : shown) {
                int k = i.intValue();
                checked.add(key(rowUsers.get(k).intValue(), pkgs.get(k)));
            }
        }
        adapter.notifyDataSetChanged();
        renderBar();
    }

    /** 反选：只看当前过滤出来的这批 */
    private void invert() {
        for (Integer i : shown) {
            int k = i.intValue();
            String id = key(rowUsers.get(k).intValue(), pkgs.get(k));
            if (checked.contains(id)) checked.remove(id);
            else checked.add(id);
        }
        adapter.notifyDataSetChanged();
        renderBar();
    }

    // ── 开始：跳到过程页 ─────────────────────────────────────────────
    private void startBatch() {
        if (running) return;
        if (BatchJob.get() != null && BatchJob.get().running) {
            toast(getString(R.string.batch_busy));
            return;
        }
        final List<String> sel = new ArrayList<String>();
        for (int k = 0; k < pkgs.size(); k++) {
            String id = key(rowUsers.get(k).intValue(), pkgs.get(k));
            if (checked.contains(id)) sel.add(id);
        }
        if (sel.isEmpty()) {
            toast(getString(R.string.backup_batch_none));
            return;
        }
        android.content.Intent it = new android.content.Intent(this, BatchRunActivity.class);
        it.putExtra(BatchRunActivity.EXTRA_MODE, BatchRunActivity.MODE_BACKUP);
        it.putExtra(BatchRunActivity.EXTRA_ITEMS, sel.toArray(new String[sel.size()]));
        startActivity(it);
        finish();   // 选完就退，回来时备份页 onResume 会重扫列表
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
            final int idx = shown.get(i).intValue();
            final String pkg = pkgs.get(idx);
            final int u = rowUsers.get(idx).intValue();
            final String id = key(u, pkg);
            TextView t1 = v.findViewById(R.id.item_title);
            TextView t2 = v.findViewById(R.id.item_sub);
            TextView more = v.findViewById(R.id.item_more);
            CheckBox check = v.findViewById(R.id.item_check);

            t1.setText(labelOf(pkg));
            t1.setTextColor(fg);
            t2.setText(spaceLabel(u) + " · " + pkg);
            t2.setTextColor(sec);
            more.setVisibility(View.GONE);
            check.setVisibility(View.VISIBLE);
            check.setChecked(checked.contains(id));

            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    if (checked.contains(id)) checked.remove(id);
                    else checked.add(id);
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
        return Theme.isNight(this);
    }

    private void applyChrome() {
        int bg = Theme.bg(this);
        int line = Theme.line(this);
        fg = Theme.fg(this);
        sec = Theme.sec(this);
        accent = Theme.accent(this);

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
