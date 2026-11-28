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


    /** 界面语言跟随应用设置（Locales），不能只靠系统语言 */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Locales.wrap(base));
    }

    private static final class Row {
        final String pkg;
        final int uid;
        final String label;
        final String space;
        /** 用户数据字节数，口径同系统「应用信息 → 用户数据」：文件的字节总和，缓存另算。
         *  -1 = 还没量。 */
        long bytes = -1;
        boolean on;
        /** 主身份里是系统应用 */
        boolean sys;
        /** 该空间里被 pm disable-user 停用了 */
        boolean disabled;

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
    /** 分类筛选：默认只看用户应用；系统分身应用要自己勾出来 */
    private boolean showUser = true;
    private boolean showSys = false;

    private ListView list;
    private TextView empty;
    private TextView title;
    private TextView filter, typeFilter;
    private TextView hintTv;
    private TextView sortTv;
    private Button btnRemove, btnEnable, btnDisable, btnBackup;
    private android.widget.EditText search;
    private String query = "";
    private int fg = Theme.FG_LIGHT;
    private int sec = Theme.SEC_LIGHT;
    private int accent = Theme.ACCENT_LIGHT;

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

        // 空间筛选：点开多选，全不勾 = 全部。和排序同一行（搜索 | 排序 | 筛选），与「数据备份」页一致；
        // 单独占一行会把顶部撑成两行，列表能看见的内容就少一截。
        filter = new TextView(this);
        filter.setTextSize(13);
        filter.setTextColor(sec);
        filter.setPadding(dp(10), dp(8), dp(4), dp(8));
        filter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFilter(); }
        });
        searchRow.addView(filter);

        // 应用类型筛选：和「全部空间」同一套用法，点开多选；默认只看用户应用
        showUser = sp().getBoolean("rc_show_user", true);
        showSys = sp().getBoolean("rc_show_sys", false);
        typeFilter = new TextView(this);
        typeFilter.setTextSize(13);
        typeFilter.setTextColor(sec);
        typeFilter.setPadding(dp(10), dp(8), dp(4), dp(8));
        typeFilter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickType(); }
        });
        searchRow.addView(typeFilter);
        root.addView(searchRow);

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

        // 底部动作条：同一个勾选集合，删除 / 禁用 / 启用 / 备份各走各的
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.setMargins(dp(10), dp(6), dp(10), dp(10));

        btnRemove = actionBtn(R.string.rc_act_delete);
        btnRemove.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(); }
        });
        btnDisable = actionBtn(R.string.rc_act_disable);
        btnDisable.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmToggle(false); }
        });
        btnEnable = actionBtn(R.string.rc_act_enable);
        btnEnable.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmToggle(true); }
        });
        btnBackup = actionBtn(R.string.rc_act_backup);
        btnBackup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startBackup(); }
        });
        actions.addView(btnEnable);
        actions.addView(btnDisable);
        actions.addView(btnBackup);
        actions.addView(btnRemove);
        root.addView(actions, alp);

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

    /** 底部动作按钮：四个等宽，避免在窄屏上挤成两行 */
    private Button actionBtn(int text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        Theme.disabledPrimary(b, this);
        return b;
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
                // 列表永远是全部分身：入口（多选工具条 / 长按菜单）都不再带预选。
                // 系统应用也要列（批量启用/禁用/备份都要用到），默认分类只看用户应用。
                final Set<String> sysSet = systemPkgs();
                for (Integer u : usable) {
                    Set<String> want = installedOf(u.intValue(), false);
                    Set<String> off = disabledOf(u.intValue());
                    for (String p : want) {
                        if ("com.cubsync.cubclone".equals(p)) continue;
                        Row r = new Row(p, u.intValue(), labelOf(p),
                                RemoveClonesActivity.spaceName(RemoveClonesActivity.this, u.intValue()));
                        r.sys = sysSet.contains(p);
                        r.disabled = off.contains(p);
                        rows.add(r);
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

    /** 主身份里的系统包（分类标记用，一次查完） */
    private Set<String> systemPkgs() {
        Set<String> s = new HashSet<String>();
        for (String line : Shell.exec("pm list packages -s").split("\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) s.add(t.substring(8));
        }
        return s;
    }

    /** 某空间里被停用的包：pm list packages -d --user U */
    private Set<String> disabledOf(int uid) {
        Set<String> s = new HashSet<String>();
        for (String line : Shell.exec("pm list packages -d --user " + uid).split("\n")) {
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
                if (r.uid == U && inCategory(r) && !cacheSize.containsKey(U + "|" + r.pkg)) { missing = true; break; }
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
            for (Row r : rows) if (r.uid == U && inCategory(r)) want.add(r.pkg);

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

    /** 工具栏只显示当前维度；方向在对话框里选，不在外面挂箭头 */
    private String sortShort() {
        String k = sortKey();
        if ("size".equals(k)) return getString(R.string.rc_sort_size);
        if ("name".equals(k)) return getString(R.string.rc_sort_name);
        return getString(R.string.rc_sort_space);
    }

    /** 方向按维度各记一份：空间/名称默认正序，体积默认大的在前 */
    private boolean sortDesc() {
        String saved = sp().getString("rc_sort_dir_" + sortKey(), null);
        if (saved == null) return "size".equals(sortKey());
        return "desc".equals(saved);
    }

    private void renderSort() {
        sortTv.setText(sortShort() + " ▾");
        sortTv.setTextColor(accent);
    }

    private void pickSort() {
        final String[] keys = {"space", "size", "name"};
        String[] labels = {
                getString(R.string.rc_sort_space),
                getString(R.string.rc_sort_size),
                getString(R.string.rc_sort_name),
        };
        String k = sortKey();
        int cur = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(k)) cur = i;
        SortDialog.show(this, R.string.rc_sort, labels, cur, sortDesc(),
                R.string.rc_recount, new DialogInterface.OnClickListener() {
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
                },
                new SortDialog.OnPick() {
                    public void onPick(int index, boolean desc) {
                        sp().edit()
                                .putString("rc_sort", keys[index])
                                .putString("rc_sort_dir_" + keys[index], desc ? "desc" : "asc")
                                .apply();
                        renderSort();
                        refresh();
                    }
                });
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
        Theme.dialog(this)
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

    /** 应用类型：和空间筛选一样多选，默认只勾用户应用 */
    private void pickType() {
        final String[] items = {getString(R.string.rc_cat_user), getString(R.string.rc_cat_sys)};
        final boolean[] checked = {showUser, showSys};
        Theme.dialog(this)
                .setTitle(R.string.rc_type_title)
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        showUser = checked[0];
                        showSys = checked[1];
                        sp().edit().putBoolean("rc_show_user", showUser)
                                .putBoolean("rc_show_sys", showSys).apply();
                        refresh();
                    }
                })
                .setNeutralButton(R.string.rc_filter_reset, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        showUser = true;
                        showSys = false;
                        sp().edit().putBoolean("rc_show_user", true)
                                .putBoolean("rc_show_sys", false).apply();
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
        typeFilter.setText((showUser && showSys ? getString(R.string.rc_type_all)
                : showUser ? getString(R.string.rc_cat_user)
                : showSys ? getString(R.string.rc_cat_sys)
                : getString(R.string.rc_type_none)) + " ▾");
        typeFilter.setTextColor(accent);
        renderSort();
    }

    // ── 列表 ────────────────────────────────────────────────────────
    /** 这一行是否在当前勾选的分类里（默认只有用户应用） */
    private boolean inCategory(Row r) {
        return r.sys ? showSys : showUser;
    }

    private void refresh() {
        shown.clear();
        for (Row r : rows) {
            if (!filterUsers.isEmpty() && !filterUsers.contains(Integer.valueOf(r.uid))) continue;
            if (!inCategory(r)) continue;
            if (query.length() > 0
                    && r.label.toLowerCase(java.util.Locale.US).indexOf(query) < 0
                    && r.pkg.toLowerCase(java.util.Locale.US).indexOf(query) < 0) continue;
            shown.add(r);
        }
        // 筛掉的行不留勾选：否则点「删除」会把看不见的分身一起删掉
        Set<Row> visibleSet = new HashSet<Row>(shown);
        for (Row r : rows) if (r.on && !visibleSet.contains(r)) r.on = false;
        final String k = sortKey();
        final boolean desc = sortDesc();
        Collections.sort(shown, new Comparator<Row>() {
            public int compare(Row a, Row b) {
                int c;
                if ("size".equals(k)) {
                    c = Long.valueOf(a.bytes).compareTo(Long.valueOf(b.bytes));
                } else if ("name".equals(k)) {
                    c = a.label.compareToIgnoreCase(b.label);
                } else {
                    c = Integer.valueOf(a.uid).compareTo(Integer.valueOf(b.uid));
                }
                if (desc) c = -c;
                if (c != 0) return c;
                int n = a.label.compareToIgnoreCase(b.label);   // 并列时按名称、包名兜底
                return n != 0 ? n : a.pkg.compareTo(b.pkg);
            }
        });
        int sel = countSelected();
        renderActions(sel);
        title.setText(loading ? getString(R.string.rc_loading)
                : getString(R.string.rc_title_count, shown.size(), sel));
        empty.setVisibility(shown.isEmpty() && !loading ? View.VISIBLE : View.GONE);
        empty.setText(!showUser && !showSys ? R.string.rc_cat_none : R.string.rc_empty);
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
                    renderActions(countSelected());
                }
            });
            sub.setText(r.disabled
                    ? getString(R.string.rc_disabled) + " · " + sizeText(r)
                    : sizeText(r));
            return box;
        }
    }

    // ── 启用 / 禁用 / 备份 ───────────────────────────────────────────
    private int countSelected() {
        int n = 0;
        for (Row r : rows) if (r.on) n++;
        return n;
    }

    private void renderActions(int sel) {
        btnRemove.setText(getString(R.string.rc_btn, sel));
        btnDisable.setText(getString(R.string.rc_act_disable_n, sel));
        btnEnable.setText(getString(R.string.rc_act_enable_n, sel));
        btnBackup.setText(getString(R.string.rc_act_backup_n, sel));
        for (Button b : new Button[]{btnRemove, btnDisable, btnEnable, btnBackup}) {
            b.setEnabled(sel > 0);
            if (sel > 0) Theme.primary(b, this);
            else Theme.disabledPrimary(b, this);
        }
    }

    private List<Row> selectedRows() {
        List<Row> sel = new ArrayList<Row>();
        for (Row r : rows) if (r.on) sel.add(r);
        return sel;
    }

    /** 启用不弹确认；禁用会藏起图标，先问一句（数据都还在，可随时启用） */
    private void confirmToggle(final boolean enable) {
        final List<Row> sel = selectedRows();
        if (sel.isEmpty()) return;
        if (enable) {
            applyToggle(sel, true);
            return;
        }
        Theme.dialog(this)
                .setTitle(R.string.rc_disable_title)
                .setMessage(getString(R.string.rc_disable_msg, sel.size()))
                .setPositiveButton(R.string.rc_act_disable, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { applyToggle(sel, false); }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyToggle(final List<Row> sel, final boolean enable) {
        btnRemove.setEnabled(false);
        btnDisable.setEnabled(false);
        btnEnable.setEnabled(false);
        btnBackup.setEnabled(false);
        title.setText(getString(R.string.rc_loading));
        new Thread(new Runnable() {
            public void run() {
                int ok = 0, bad = 0;
                for (Row r : sel) {
                    String out = enable ? Launcher.enable(r.uid, r.pkg)
                            : Launcher.disable(r.uid, r.pkg);
                    if (out != null && !out.contains("Error") && !out.contains("Exception")) {
                        ok++;
                        r.disabled = !enable;
                        r.on = false;
                    } else {
                        bad++;
                    }
                }
                final int fok = ok, fbad = bad;
                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(RemoveClonesActivity.this,
                                fbad == 0 ? getString(enable ? R.string.rc_enabled : R.string.rc_disabled_n, fok)
                                        : getString(R.string.rc_toggle_bad, fok, fbad),
                                Toast.LENGTH_LONG).show();
                        refresh();
                    }
                });
            }
        }).start();
    }

    /** 备份：勾中的分身直接进批量备份，过程和「数据备份」页同一条链路 */
    private void startBackup() {
        final List<Row> sel = selectedRows();
        if (sel.isEmpty()) return;
        if (BatchJob.get() != null && BatchJob.get().running) {
            toast(getString(R.string.batch_busy));
            return;
        }
        List<String> items = new ArrayList<String>();
        for (Row r : sel) items.add(r.uid + "|" + r.pkg);
        android.content.Intent it = new android.content.Intent(this, BatchRunActivity.class);
        it.putExtra(BatchRunActivity.EXTRA_MODE, BatchRunActivity.MODE_BACKUP);
        it.putExtra(BatchRunActivity.EXTRA_ITEMS, items.toArray(new String[items.size()]));
        startActivity(it);
        finish();
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
        Theme.dialog(this)
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
