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
 * **默认一个都不勾**；「仅无数据」只勾那些三处目录都不存在的（列表里写「无数据」的），
 * 有数据的一律要自己动手勾，删了不可恢复。
 * 顶部筛选/排序与「数据备份」页保持一致：搜索框 + 空间多选 + 排序。
 */
public class RemoveClonesActivity extends Activity {

    private static final class Row {
        final String pkg;
        final int uid;
        final String label;
        final String space;
        /** 用户数据字节数，口径同系统「应用信息 → 用户数据」：文件的字节总和，缓存另算。
         *  -1 = 还没量。 */
        long bytes = -1;
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
    private TextView hintTv;
    private TextView sortTv;
    private Button btnRemove;
    private android.widget.EditText search;
    private String query = "";
    private int fg = 0xFF1B1B1F;
    private int sec = 0xFF5F6368;
    private int accent = 0xFF0B57D0;

    private boolean loading = true;

    /** 体积缓存：打开页面先用上次结果秒显，只有过期或出现新包才重算。
     *  几百个分身时全量遍历要好几秒（成本按文件数算，十几个空间累加很可观），
     *  而用户看这一页就是想删几个空壳，没必要每次都扫一遍。 */
    private static final long SIZE_TTL_MS = 10 * 60 * 1000L;
    private final java.util.HashMap<String, Long> cacheSize = new java.util.HashMap<String, Long>();
    private final java.util.HashMap<Integer, Long> cacheTs = new java.util.HashMap<Integer, Long>();

    private Adapter adapter;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CloneStore.attach(this);
        // 自测开关：--ez noRoot true 时强制走 Shizuku，验证不依赖 root 的那条路
        if (getIntent().getBooleanExtra("noRoot", false)) Shell.forceShizuku = true;
        buildUi();
        load();
    }

    private void buildUi() {
        fg = Theme.fg(this);
        sec = Theme.sec(this);
        accent = Theme.accent(this);
        int bg = Theme.bg(this);

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

        hintTv = new TextView(this);
        hintTv.setText(R.string.rc_hint);
        hintTv.setTextSize(12);
        hintTv.setTextColor(sec);
        hintTv.setPadding(dp(16), 0, dp(16), dp(6));
        root.addView(hintTv);

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
        // 全选就是全选（含数据）；只勾没数据的用右边那个按钮。删掉的确认框会写明数据量。
        tools.addView(chipBtn(R.string.rc_select_all, new View.OnClickListener() {
            public void onClick(View v) { setAll(true); }
        }));
        tools.addView(chipBtn(R.string.rc_invert, new View.OnClickListener() {
            public void onClick(View v) {
                for (Row r : shown) r.on = !r.on;
                refresh();
            }
        }));
        tools.addView(chipBtn(R.string.rc_only_light, new View.OnClickListener() {
            public void onClick(View v) { selectNoData(); }
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
        Theme.disabledPrimary(btnRemove, this);
        btnRemove.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(12), dp(6), dp(12), dp(10));
        root.addView(btnRemove, blp);

        setContentView(root);
    }

    private TextView chipBtn(int text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        Theme.chip(t, accent);
        t.setOnClickListener(l);
        return t;
    }

    private void setAll(boolean on) {
        for (Row r : shown) r.on = on;
        refresh();
    }

    /**
     * 只勾「没有数据」的：判定与列表显示同一条口径——用户数据 0 字节才显示「无数据」。
     * 有字节的一律不动（哪怕只有 24 B 的系统标记，也说明这个应用在分身里启动过）。
     * 还没量到的（显示「…」）也不勾，宁可少选不可误删。
     */
    private void selectNoData() {
        // 没有 root 就量不到体积，rows 全是「…」——这时候按 0 勾会一个都勾不到，
        // 真按「空」处理又会把有数据的一起勾上，所以直接不给用。
        for (Row r : shown) r.on = r.bytes == 0;
        refresh();
    }

    // ── 数据 ────────────────────────────────────────────────────────
    private void load() {
        loading = true;
        refresh();
        new Thread(new Runnable() {
            public void run() {
                // 这一页是「清理分身」用的，不能因为空间不是我们建的就把应用藏起来：
                // 所有 profile.CLONE 空间都列出来（系统的工作资料不是 CLONE，不会被误列）
                if (!Shell.ready()) Shell.probe();
                final boolean canShell = Shell.ready();
                // 体积只能 root 量：分身的包目录是 0700 uNN_aXXX，shell 身份读不进去。
                // 删除不需要 root —— `pm uninstall --user` 是 shell 能力，Shizuku 就够。
                // 体积走 `cmd package get-package-storage-stats`，shell 身份就够，
                // 不再需要 root（root 只是让 shell 命令跑得更早一点）
                final boolean canMeasure = canShell;
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
                // 列表永远是全部分身：入口（多选工具条 / 长按菜单）都不再带预选
                for (Integer u : usable) {
                    Set<String> want = installedOf(u.intValue(), true);
                    for (String p : want) {
                        if ("com.cubsync.cubclone".equals(p)) continue;
                        rows.add(new Row(p, u.intValue(), labelOf(p),
                                RemoveClonesActivity.spaceName(RemoveClonesActivity.this, u.intValue())));
                    }
                }
                readCache();
                for (Row r : rows) {
                    Long v = cacheSize.get(r.uid + "|" + r.pkg);
                    if (v != null) r.bytes = v.longValue();
                }
                // 先把缓存里的数字画出来，用户不用等遍历
                runOnUiThread(new Runnable() {
                    public void run() { refresh(); }
                });
                if (canMeasure) measure(false);
                // 一行日志能直接看出这条路径靠什么权限跑起来（没有 root 时应是 shizuku）
                android.util.Log.i("CubClone", "rc load spaces=" + usable.size()
                        + " rows=" + rows.size() + " canShell=" + canShell
                        + " canMeasure=" + canMeasure);
                runOnUiThread(new Runnable() {
                    public void run() {
                        loading = false;
                        if (!canShell) empty.setText(R.string.rc_need_shell);
                        // 一个都不勾：只有用户点了才勾，任何自动勾选都可能删掉有数据的分身
                        for (Row r : rows) r.on = false;
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
     * 量各分身的「用户数据」：**每个空间一条命令**，不是每个包一条。
     * 数据来源：`cmd package get-package-storage-stats --user <U> <包>`——这就是系统的
     * 「应用信息 → 存储」用的那套（`IPackageManager.getPackageStorageStats` → installd）。
     * 一次只查一个包（约 40 ms），所以按空间攒成一条 shell 循环，几十个包一秒内跑完。
     *
     * 只要求 shell 身份：root 或 Shizuku 都行，**不需要 root**。
     * 这正是之前挖不到的入口——直接调 `storagestats` 服务在本 ROM 上 data 恒 0，
     * 而走 `cmd package` 这条 CLI 封装拿到的是真值，且天然按空间分开。
     *
     * 口径：`data` 含缓存（系统的 dataBytes 就是这样），减掉 `cache` 才是「用户数据」，
     * 与 Settings 页面显示的一致。实测 u10 芝麻粒 data=143360 cache=139264 → 4096 B，
     * 和「应用信息」里看到的 4 KB 对得上；u11 刚克隆没打开的 XRadiant data=0 → 空壳。
     */
    private void measure(boolean force) {
        if (rows.isEmpty()) return;
        // 后台优先级：几百个分身时别跟界面抢 CPU
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        Set<Integer> users = new java.util.LinkedHashSet<Integer>();
        for (Row r : rows) users.add(Integer.valueOf(r.uid));
        for (final Integer u : users) {
            final int U = u.intValue();
            Long ts = cacheTs.get(u);
            boolean missing = false;
            for (Row r : rows) {
                if (r.uid == U && !cacheSize.containsKey(U + "|" + r.pkg)) { missing = true; break; }
            }
            if (!force && !missing && ts != null
                    && System.currentTimeMillis() - ts.longValue() < SIZE_TTL_MS) {
                for (Row r : rows) {
                    if (r.uid != U) continue;
                    Long v = cacheSize.get(U + "|" + r.pkg);
                    if (v != null) r.bytes = v.longValue();
                }
                continue;
            }
            java.util.List<String> want = new java.util.ArrayList<String>();
            for (Row r : rows) if (r.uid == U) want.add(r.pkg);

            final java.util.HashMap<String, Long> sum = new java.util.HashMap<String, Long>();
            // 一条 shell 里循环；分块是为了不让单条命令长到被截断
            final int CHUNK = 40;
            for (int from = 0; from < want.size(); from += CHUNK) {
                int to = Math.min(from + CHUNK, want.size());
                StringBuilder cmd = new StringBuilder("for p in");
                for (int i = from; i < to; i++) cmd.append(' ').append(want.get(i));
                cmd.append("; do L=$(cmd package get-package-storage-stats --user ").append(U)
                        .append(" $p 2>/dev/null | awk '/^data:/{d=$2} /^cache:/{c=$2}")
                        .append(" END{print d+0\"|\"c+0}'); echo \"$p|$L\"; done");
                String out = Shell.exec(cmd.toString());
                for (String line : out.split("\n")) {
                    String t = line.trim();
                    int b1 = t.indexOf('|');
                    if (b1 <= 0) continue;
                    int b2 = t.indexOf('|', b1 + 1);
                    if (b2 < 0) continue;
                    long data = atol(t.substring(b1 + 1, b2));
                    long cache = atol(t.substring(b2 + 1));
                    long used = data - cache;
                    if (used < 0) used = 0;
                    sum.put(t.substring(0, b1).trim(), Long.valueOf(used));
                }
            }
            for (Row r : rows) {
                if (r.uid != U) continue;
                Long v = sum.get(r.pkg);
                r.bytes = v == null ? 0 : v.longValue();
                cacheSize.put(U + "|" + r.pkg, Long.valueOf(r.bytes));
            }
            cacheTs.put(u, Long.valueOf(System.currentTimeMillis()));
            saveCache();
            runOnUiThread(new Runnable() {
                public void run() { refresh(); }
            });
        }
    }

    // ── 体积缓存 ────────────────────────────────────────────────────
    /** 格式：`<uid>|<pkg> <bytes>` 每行一条，另有 `#<uid> <ts>` 记该空间上次统计时间 */
    private java.io.File cacheFile() {
        return new java.io.File(getFilesDir(), "size-cache.txt");
    }

    private void readCache() {
        cacheSize.clear();
        cacheTs.clear();
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(cacheFile()), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                String k = line.substring(0, sp);
                long v = atol(line.substring(sp + 1));
                if (k.startsWith("#")) {
                    cacheTs.put(Integer.valueOf(atoi(k.substring(1))), Long.valueOf(v));
                } else {
                    cacheSize.put(k, Long.valueOf(v));
                }
            }
        } catch (Throwable t) {
            // 第一次打开没有缓存，正常
        } finally {
            if (r != null) try { r.close(); } catch (Throwable t) { }
        }
    }

    private void saveCache() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Long> e : cacheSize.entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue().longValue()).append('\n');
        }
        for (java.util.Map.Entry<Integer, Long> e : cacheTs.entrySet()) {
            sb.append('#').append(e.getKey()).append(' ').append(e.getValue().longValue()).append('\n');
        }
        java.io.FileOutputStream f = null;
        try {
            f = new java.io.FileOutputStream(cacheFile());
            f.write(sb.toString().getBytes("UTF-8"));
        } catch (Throwable t) {
        } finally {
            if (f != null) try { f.close(); } catch (Throwable t) { }
        }
    }

    /** 删掉一个分身就把它的缓存条目去掉，下次进来不会还挂着旧体积 */
    private void dropCache(int uid, String pkg) {
        if (cacheSize.remove(uid + "|" + pkg) != null) saveCache();
    }

    private static int atoi(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    private static long atol(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return 0; }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
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
                .setNeutralButton(R.string.rc_recount, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        title.setText(getString(R.string.rc_loading));
                        new Thread(new Runnable() {
                            public void run() {
                                measure(true);
                                runOnUiThread(new Runnable() {
                                    public void run() { refresh(); }
                                });
                            }
                        }).start();
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
        filter.setTextColor(accent);
        sortTv.setText(sortShort() + " ▾");
        sortTv.setTextColor(accent);
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
                    if (a.bytes != b.bytes) return a.bytes > b.bytes ? -1 : 1;
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
        if (sel > 0) Theme.primary(btnRemove, this);
        else Theme.disabledPrimary(btnRemove, this);
        title.setText(loading ? getString(R.string.rc_loading)
                : getString(R.string.rc_title_count, rows.size(), sel));
        empty.setVisibility(shown.isEmpty() && !loading ? View.VISIBLE : View.GONE);
        if (empty.getText().length() == 0) empty.setText(R.string.rc_empty);
        renderFilter();
        adapter.notifyDataSetChanged();
    }

    private String sizeText(Row r) {
        if (r.bytes < 0) return "…";        // 还没量到，别显示成「无数据」
        if (r.bytes == 0) return getString(R.string.rc_no_data);
        return sizeOf(r.bytes);
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
                Theme.check(cb, RemoveClonesActivity.this);
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
        long total = 0;
        for (Row r : rows) {
            if (r.on) { sel.add(r); if (r.bytes > 0) total += r.bytes; }
        }
        if (sel.isEmpty()) return;
        boolean withData = false;
        for (Row r : sel) if (r.bytes > 0) withData = true;
        new AlertDialog.Builder(this)
                .setTitle(withData ? R.string.rc_confirm_data_title : R.string.rc_confirm_title)
                .setMessage(withData
                        ? getString(R.string.rc_confirm_data, sel.size(), sizeOf(total))
                        : getString(R.string.rc_confirm, sel.size(), sizeOf(total)))
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
                        dropCache(r.uid, r.pkg);
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
