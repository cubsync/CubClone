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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量删除分身。
 *
 * 为什么单独一页：主界面多选只能「建」，删要靠长按一个个点；分身多起来（几十上百个）
 * 就没法用了。这里把选中应用在所有空间里的分身摊平成一列，带每个分身的数据体积，
 * **默认只勾没有数据的空壳**——有数据的要自己动手勾，删了不可恢复。
 */
public class RemoveClonesActivity extends Activity {

    static final String EXTRA_PKGS = "pkgs";

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

    private ListView list;
    private TextView empty;
    private TextView title;
    private Button btnRemove;
    private int fg = 0xFF1B1B1F;
    private int sec = 0xFF5F6368;
    private boolean loading = true;

    private Adapter adapter;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CloneStore.attach(this);
        buildUi();
        final String[] pkgs = getIntent().getStringArrayExtra(EXTRA_PKGS);
        load(pkgs == null ? new String[0] : pkgs);
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
        hint.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(hint);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(dp(12), 0, dp(12), dp(4));
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
    private void load(final String[] pkgs) {
        loading = true;
        refresh();
        new Thread(new Runnable() {
            public void run() {
                List<Integer> spaces = CloneStore.listSpaceUids();
                Set<Integer> usable = new HashSet<Integer>();
                for (Integer u : spaces) {
                    if (CloneStore.isOurs(null, u.intValue())
                            || sp().getBoolean("manage_foreign", false)) {
                        usable.add(u);
                    }
                }
                for (Integer u : usable) {
                    Set<String> have = installedOf(u.intValue());
                    for (String p : pkgs) {
                        if (!have.contains(p)) continue;
                        rows.add(new Row(p, u.intValue(), labelOf(p),
                                RemoveClonesActivity.spaceName(RemoveClonesActivity.this, u.intValue())));
                    }
                }
                measure();
                runOnUiThread(new Runnable() {
                    public void run() {
                        loading = false;
                        for (Row r : rows) r.on = r.kb >= 0 && r.kb <= LIGHT_KB;
                        refresh();
                    }
                });
            }
        }).start();
    }

    private android.content.SharedPreferences sp() {
        return getSharedPreferences("clone", MODE_PRIVATE);
    }

    /** 某空间里装着的包（pm list 一次拿全，别逐包问） */
    private Set<String> installedOf(int uid) {
        Set<String> s = new HashSet<String>();
        String out = Shell.exec("pm list packages --user " + uid);
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) s.add(t.substring(8));
        }
        return s;
    }

    /** 一次脚本把所有分身的数据体积量完：du 逐目录，242 个包也就十几秒 */
    private void measure() {
        if (rows.isEmpty()) return;
        StringBuilder s = new StringBuilder();
        for (Row r : rows) {
            s.append("S=$(du -sk '/data/user/").append(r.uid).append('/').append(r.pkg)
                    .append("' 2>/dev/null | cut -f1); echo ").append(r.uid).append('|')
                    .append(r.pkg).append("|${S:-0}\n");
        }
        String out = Shell.execScript(s.toString());
        for (String line : out.split("\n")) {
            String t = line.trim();
            int a = t.indexOf('|'), b2 = t.lastIndexOf('|');
            if (a <= 0 || b2 <= a) continue;
            String u = t.substring(0, a), p = t.substring(a + 1, b2), k = t.substring(b2 + 1);
            for (Row r : rows) {
                if (r.uid == atoi(u) && r.pkg.equals(p)) {
                    r.kb = atol(k);
                    break;
                }
            }
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

    // ── 列表 ────────────────────────────────────────────────────────
    private void refresh() {
        shown.clear();
        shown.addAll(rows);
        int sel = 0;
        long kb = 0;
        for (Row r : rows) {
            if (r.on) { sel++; if (r.kb > 0) kb += r.kb; }
        }
        btnRemove.setText(getString(R.string.rc_btn, sel));
        btnRemove.setEnabled(sel > 0);
        title.setText(loading ? getString(R.string.rc_loading)
                : getString(R.string.rc_title) + "  " + rows.size());
        empty.setVisibility(rows.isEmpty() && !loading ? View.VISIBLE : View.GONE);
        empty.setText(R.string.rc_empty);
        adapter.notifyDataSetChanged();
    }

    private String sizeText(Row r) {
        if (loading && r.kb < 0) return "…";
        if (r.kb <= 0) return getString(R.string.rc_no_data);
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.rc_confirm_title)
                .setMessage(getString(R.string.rc_confirm, sel.size(), sizeOf(kb * 1024)))
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
