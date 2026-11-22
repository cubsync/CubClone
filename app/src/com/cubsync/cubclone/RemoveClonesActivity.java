package com.cubsync.cubclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量删除分身。
 *
 * 为什么单独一页：主界面多选只能「建」，删要靠长按一个个点；分身多起来（几十上百个）
 * 就没法用了。这里把所有空间里的分身摊平成一列，带每个分身的数据体积，
 * **默认只勾没有数据的空壳**——有数据的要自己动手勾，删了不可恢复。
 * 顶部筛选/排序与「数据备份」页保持一致：搜索框 + 空间多选 + 排序。
 */
public class RemoveClonesActivity extends Activity {

    static final String EXTRA_PKGS = "pkgs";
    /** 进来时预选哪个分身，格式 uid:pkg；传空 = 列全部分身 */
    static final String EXTRA_SEL = "sel";

    private static final long LIGHT_KB = 1024;   // < 1 MB 视为空壳

    private static final class Row {
        final String pkg;
        final int uid;
        final String label;
        final String space;
        long kb = -1;      // -1 = 还没量
        boolean on;

        Row(String pkg, int uid, String label, String space) {
            this.pkg = pkg;
            this.uid = uid;
            this.label = label;
            this.space = space;
        }
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final List<Row> shown = new ArrayList<Row>();
    /** 空间筛选，空 = 全部 */
    private final Set<Integer> filterUsers = new HashSet<Integer>();

    private ListView list;
    private TextView empty;
    private TextView title;
    private TextView filter;
    private TextView sortTv;
    private Button btnRemove;
    private android.widget.EditText search;
    private String query = "";
    private int fg = 0xFF1B1B1F;
    private int sec = 0xFF5F6368;

    private boolean loading = true;

    private Adapter adapter;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CloneStore.attach(this);
        buildUi();
        final String[] pkgs = getIntent().getStringArrayExtra(EXTRA_PKGS);
        load(pkgs == null ? new String[0] : pkgs, getIntent().getStringExtra(EXTRA_SEL));
    }

    private void buildUi() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        fg = dark ? 0xFFEDEDED : 0xFF1B1B1F;
        sec = dark ? 0xFF9AA0A6 : 0xFF5F6368;
        int bg = dark ? 0xFF121212 : 0xFFFFFFFF;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(6));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(fg);
        back.setPadding(dp(8), 0, dp(12), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        title = new TextView(this);
        title.setText(R.string.rc_title);
        title.setTextSize(18);
        title.setTextColor(fg);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);
        root.addView(bar);

        TextView hint = new TextView(this);
        hint.setText(R.string.rc_hint);
        hint.setTextSize(12);
        hint.setTextColor(sec);
        hint.setPadding(dp(16), 0, dp(16), dp(6));
        root.addView(hint);

        // 搜索 + 排序：和「数据备份」页同一套用法
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(16), 0, dp(12), 0);

        search = new android.widget.EditText(this);
        search.setSingleLine(true);
        search.setHint(R.string.rc_search);
        search.setTextSize(14);
        search.setTextColor(fg);
        search.setHintTextColor(sec);
        search.setBackgroundColor(0x00000000);
        search.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable e) {
                query = e.toString().trim().toLowerCase(java.util.Locale.US);
                refresh();
            }
        });
        searchRow.addView(search);

        sortTv = new TextView(this);
        sortTv.setTextSize(13);
        sortTv.setTextColor(sec);
        sortTv.setPadding(dp(10), dp(8), dp(4), dp(8));
        sortTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickSort(); }
        });
        searchRow.addView(sortTv);
        root.addView(searchRow);

        // 空间筛选：点开多选，全不勾 = 全部
        filter = new TextView(this);
        filter.setTextSize(13);
        filter.setTextColor(sec);
        filter.setPadding(dp(16), 0, dp(16), dp(6));
        filter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFilter(); }
        });
        root.addView(filter);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(dp(12), 0, dp(12), dp(4));
        // 全选就是全选（含数据）；只要空壳用右边那个按钮。删掉的确认框会写明数据量。
        tools.addView(toolBtn(R.string.rc_select_all, new View.OnClickListener() {
            public void onClick(View v) { setAll(true, Long.MAX_VALUE); }
        }));
        tools.addView(toolBtn(R.string.rc_invert, new View.OnClickListener() {
            public void onClick(View v) {
                for (Row r : shown) r.on = !r.on;
                refresh();
            }
        }));
        tools.addView(toolBtn(R.string.rc_only_light, new View.OnClickListener() {
            public void onClick(View v) { setAll(true, LIGHT_KB); }
        }));
        root.addView(tools);

        list = new ListView(this);
        list.setDivider(null);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new Adapter();
        list.setAdapter(adapter);
        root.addView(list);

        empty = new TextView(this);
        empty.setTextSize(14);
        empty.setTextColor(sec);
        empty.setGravity(android.view.Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty);

        btnRemove = new Button(this);
        btnRemove.setText(getString(R.string.rc_btn, 0));
        btnRemove.setEnabled(false);
        btnRemove.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(12), dp(6), dp(12), dp(10));
        root.addView(btnRemove, blp);

        setContentView(root);
    }

    private Button toolBtn(int text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    /** 勾选：maxKb 以内（含未量的）全勾上，超过的清掉 */
    private void setAll(boolean on, long maxKb) {
        for (Row r : shown) r.on = on && (r.kb < 0 || r.kb <= maxKb);
        refresh();
    }

    // ── 数据 ────────────────────────────────────────────────────────
    private void load(final String[] pkgs, final String sel) {
        loading = true;
        refresh();
        new Thread(new Runnable() {
            public void run() {
                // 这一页是「清理分身」用的，不能因为空间不是我们建的就把应用藏起来：
                // 所有 profile.CLONE 空间都列出来（系统的工作资料不是 CLONE，不会被误列）
                if (!Shell.ready()) Shell.checkRoot();
                final boolean canShell = Shell.ready();
                Set<Integer> usable = new HashSet<Integer>();
                if (canShell) {
                    String ul = Shell.exec("cmd user list --all -v");
                    for (String line : ul.split("\n")) {
                        if (line.indexOf("type=profile.CLONE") < 0) continue;
                        if (line.indexOf("<removing>") >= 0) continue;
                        int i = line.indexOf("id=");
                        if (i < 0) continue;
                        int j = line.indexOf(',', i);
                        if (j < 0) continue;
                        int u2 = atoi(line.substring(i + 3, j));
                        if (u2 > 0) usable.add(u2);
                    }
                }
                // 总是列全部：从多选进来只给几个包名时，也只当作「预选」，列表照旧完整
                for (Integer u : usable) {
                    Set<String> want = installedOf(u.intValue(), true);
                    for (String p : want) {
                        if ("com.cubsync.cubclone".equals(p)) continue;
                        rows.add(new Row(p, u.intValue(), labelOf(p),
                                RemoveClonesActivity.spaceName(RemoveClonesActivity.this, u.intValue())));
                    }
                }
                measure();
                runOnUiThread(new Runnable() {
                    public void run() {
                        loading = false;
                        if (!canShell) empty.setText(R.string.backup_need_root);
                        for (Row r : rows) {
                            r.on = r.kb >= 0 && r.kb <= LIGHT_KB;
                            // 从某个分身长按进来的，把那一个勾上（哪怕它有数据，用户点的是它）
                            if (sel != null && sel.equals(r.uid + ":" + r.pkg)) r.on = true;
                            else for (String p : pkgs) if (p.equals(r.pkg)) { r.on = true; break; }
                        }
                        refresh();
                    }
                });
            }
        }).start();
    }

    private android.content.SharedPreferences sp() {
        return getSharedPreferences("clone", MODE_PRIVATE);
    }

    /** 某空间里装着的包（pm list 一次拿全，别逐包问）；thirdOnly 时只要三方的 */
    private Set<String> installedOf(int uid, boolean thirdOnly) {
        Set<String> s = new HashSet<String>();
        String out = Shell.exec("pm list packages " + (thirdOnly ? "-3 " : "") + "--user " + uid);
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) s.add(t.substring(8));
        }
        return s;
    }

    /**
     * 量各分身的数据体积：**每个空间一条 du**，不是每个包一条。
     * 以前逐包 du，400 个分身就是 400 次 fork，二十秒起步，脚本还容易被截断——
     * 界面就变成「刷新慢 + 全部无数据」。整目录一次遍历，两个空间不到一秒。
     * 每量完一个空间就刷一次界面，数字是陆续出现的。
     */
    private void measure() {
        if (rows.isEmpty()) return;
        Set<Integer> users = new java.util.LinkedHashSet<Integer>();
        for (Row r : rows) users.add(Integer.valueOf(r.uid));
        for (final Integer u : users) {
            String out = Shell.execScript("du -sk /data/user/" + u + "/* 2>/dev/null");
            for (String line : out.split("\n")) {
                String t = line.trim();
                int sp = t.indexOf('\t');
                if (sp <= 0) sp = t.indexOf(' ');
                if (sp <= 0) continue;
                String k = t.substring(0, sp).trim();
                String path = t.substring(sp + 1).trim();
                int sl = path.lastIndexOf('/');
                if (sl < 0) continue;
                String pkg = path.substring(sl + 1);
                for (Row r : rows) {
                    if (r.uid == u.intValue() && r.pkg.equals(pkg)) {
                        r.kb = atol(k);
                        break;
                    }
                }
            }
            // 这个空间里没有目录的（空壳被清过、只剩安装记录）就是 0，别让它一直挂「…」
            for (Row r : rows) {
                if (r.uid == u.intValue() && r.kb < 0) r.kb = 0;
            }
            runOnUiThread(new Runnable() {
                public void run() { refresh(); }
            });
        }
    }

    private static int atoi(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    private static long atol(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return 0; }
    }

    private String labelOf(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    static String spaceName(android.content.Context c, int u) {
        String n = u == 0 ? c.getString(R.string.space_main) : CloneStore.nameOf(c, u);
        return n + " (" + u + ")";
    }

    // ── 筛选 / 排序 ──────────────────────────────────────────────────
    private String sortKey() {
        return sp().getString("rc_sort", "space");
    }

    private String sortShort() {
        String k = sortKey();
        if ("size".equals(k)) return getString(R.string.rc_sort_size_short);
        if ("name".equals(k)) return getString(R.string.rc_sort_name);
        return getString(R.string.rc_sort_space_short);
    }

    private void pickSort() {
        final String[] keys = {"space", "size", "name"};
        String[] items = {getString(R.string.rc_sort_space), getString(R.string.rc_sort_size),
                getString(R.string.rc_sort_name)};
        int cur = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(sortKey())) cur = i;
        new AlertDialog.Builder(this)
                .setTitle(R.string.rc_sort)
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        sp().edit().putString("rc_sort", keys[which]).apply();
                        d.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 空间筛选支持多选：勾几个看几个，全不勾等于全部 */
    private void pickFilter() {
        final List<Integer> users = new ArrayList<Integer>();
        for (Row r : rows) if (!users.contains(Integer.valueOf(r.uid))) users.add(Integer.valueOf(r.uid));
        if (users.isEmpty()) return;
        Collections.sort(users);
        String[] items = new String[users.size()];
        final boolean[] checked = new boolean[users.size()];
        for (int i = 0; i < users.size(); i++) {
            int u = users.get(i).intValue();
            items[i] = spaceName(this, u);
            checked[i] = filterUsers.contains(Integer.valueOf(u));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.rc_filter_title)
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        filterUsers.clear();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) filterUsers.add(users.get(i));
                        }
                        refresh();
                    }
                })
                .setNeutralButton(R.string.rc_filter_reset, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        filterUsers.clear();
                        search.setText("");
                        query = "";
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renderFilter() {
        StringBuilder sb = new StringBuilder();
        if (filterUsers.isEmpty()) {
            sb.append(getString(R.string.rc_filter_all));
        } else if (filterUsers.size() == 1) {
            for (Integer u : filterUsers) sb.append(spaceName(this, u.intValue()));
        } else {
            sb.append(getString(R.string.rc_filter_count, filterUsers.size()));
        }
        if (query.length() > 0) sb.append(" • ").append(query);
        sb.append(" ▾");
        filter.setText(sb.toString());
        sortTv.setText(sortShort() + " ▾");
    }

    // ── 列表 ────────────────────────────────────────────────────────
    private void refresh() {
        shown.clear();
        for (Row r : rows) {
            if (!filterUsers.isEmpty() && !filterUsers.contains(Integer.valueOf(r.uid))) continue;
            if (query.length() > 0
                    && r.label.toLowerCase(java.util.Locale.US).indexOf(query) < 0
                    && r.pkg.toLowerCase(java.util.Locale.US).indexOf(query) < 0) continue;
            shown.add(r);
        }
        final String k = sortKey();
        Collections.sort(shown, new Comparator<Row>() {
            public int compare(Row a, Row b) {
                if ("size".equals(k)) {
                    if (a.kb != b.kb) return a.kb > b.kb ? -1 : 1;
                } else if ("name".equals(k)) {
                    int c = a.label.compareToIgnoreCase(b.label);
                    if (c != 0) return c;
                } else {
                    if (a.uid != b.uid) return a.uid < b.uid ? -1 : 1;
                }
                int c = a.label.compareToIgnoreCase(b.label);
                return c != 0 ? c : a.pkg.compareTo(b.pkg);
            }
        });
        int sel = 0;
        for (Row r : rows) if (r.on) sel++;
        btnRemove.setText(getString(R.string.rc_btn, sel));
        btnRemove.setEnabled(sel > 0);
        title.setText(loading ? getString(R.string.rc_loading)
                : getString(R.string.rc_title_count, rows.size(), sel));
        empty.setVisibility(shown.isEmpty() && !loading ? View.VISIBLE : View.GONE);
        if (empty.getText().length() == 0) empty.setText(R.string.rc_empty);
        renderFilter();
        adapter.notifyDataSetChanged();
    }

    private String sizeText(Row r) {
        if (r.kb < 0) return "…";          // 还没量到，别显示成「无数据」
        if (r.kb == 0) return getString(R.string.rc_no_data);
        if (r.kb < 1024) return r.kb + " KB";
        if (r.kb < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", r.kb / 1024.0);
        return String.format(java.util.Locale.US, "%.2f GB", r.kb / 1048576.0);
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup p) {
            LinearLayout box;
            CheckBox cb;
            TextView sub;
            if (v == null) {
                box = new LinearLayout(RemoveClonesActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(14), dp(10), dp(14), dp(10));
                cb = new CheckBox(RemoveClonesActivity.this);
                cb.setTextSize(15);
                cb.setTextColor(fg);
                sub = new TextView(RemoveClonesActivity.this);
                sub.setTextSize(12);
                sub.setTextColor(sec);
                sub.setPadding(dp(34), 0, 0, 0);
                box.addView(cb);
                box.addView(sub);
                box.setTag(new View[]{cb, sub});
                v = box;
            } else {
                box = (LinearLayout) v;
                View[] t = (View[]) box.getTag();
                cb = (CheckBox) t[0];
                sub = (TextView) t[1];
            }
            final Row r = shown.get(i);
            cb.setText(r.label + " · " + r.space);
            cb.setOnCheckedChangeListener(null);
            cb.setChecked(r.on);
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    r.on = on;
                    int sel = 0;
                    for (Row x : rows) if (x.on) sel++;
                    btnRemove.setText(getString(R.string.rc_btn, sel));
                    btnRemove.setEnabled(sel > 0);
                }
            });
            sub.setText(sizeText(r));
            return box;
        }
    }

    // ── 删除 ────────────────────────────────────────────────────────
    private void confirmDelete() {
        final List<Row> sel = new ArrayList<Row>();
        long kb = 0;
        for (Row r : rows) {
            if (r.on) { sel.add(r); if (r.kb > 0) kb += r.kb; }
        }
        if (sel.isEmpty()) return;
        boolean withData = false;
        for (Row r : sel) if (r.kb > LIGHT_KB) withData = true;
        new AlertDialog.Builder(this)
                .setTitle(withData ? R.string.rc_confirm_data_title : R.string.rc_confirm_title)
                .setMessage(withData
                        ? getString(R.string.rc_confirm_data, sel.size(), sizeOf(kb * 1024))
                        : getString(R.string.rc_confirm, sel.size(), sizeOf(kb * 1024)))
                .setPositiveButton(R.string.delete_clone_btn, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { run(sel); }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String sizeOf(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024L) return String.format(java.util.Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
    }

    private void run(final List<Row> sel) {
        btnRemove.setEnabled(false);
        title.setText(getString(R.string.rc_loading));
        new Thread(new Runnable() {
            public void run() {
                int ok = 0, bad = 0;
                for (Row r : sel) {
                    String out = Launcher.uninstall(r.uid, r.pkg);
                    if (out.contains("Success")) {
                        ok++;
                        CloneStore.unmarkKept(RemoveClonesActivity.this, r.uid, r.pkg);
                        MainActivity.unpinShortcut(RemoveClonesActivity.this, r.uid, r.pkg);
                        r.on = false;
                    } else {
                        bad++;
                    }
                }
                final int fok = ok, fbad = bad;
                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(RemoveClonesActivity.this,
                                fbad == 0 ? getString(R.string.rc_done, fok)
                                        : getString(R.string.rc_done_bad, fok, fbad),
                                Toast.LENGTH_LONG).show();
                        setResult(RESULT_OK);
                        finish();
                    }
                });
            }
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
