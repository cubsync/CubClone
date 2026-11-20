package com.cubsync.cubclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 数据备份页。交互参考常见备份工具（列表 + 每项操作 + 顶部设置行），
 * 不再用层层 AlertDialog：列表是页面的主体，点一行或右侧 ⋮ 才弹操作。
 */
public class BackupActivity extends Activity {

    private ListView list;
    private TextView empty;
    private TextView algoValue;
    private TextView title;
    private ImageButton btnBack;
    private LinearLayout root;
    private LinearLayout header;
    private LinearLayout algoRow;
    private View divider;
    private TextView algoLabel;
    private TextView algoArrow;

    private final java.util.List<AppData.Backup> items = new java.util.ArrayList<AppData.Backup>();
    private Adapter adapter;
    private int fg = 0xFF1B1B1F;
    private int sec = 0xFF5F6368;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_backup);

        root = findViewById(R.id.bk_root);
        header = findViewById(R.id.bk_header);
        title = findViewById(R.id.bk_title);
        btnBack = findViewById(R.id.bk_back);
        algoRow = findViewById(R.id.bk_algo_row);
        algoLabel = findViewById(R.id.bk_algo_label);
        algoValue = findViewById(R.id.bk_algo_value);
        algoArrow = findViewById(R.id.bk_algo_arrow);
        divider = findViewById(R.id.bk_divider);
        list = findViewById(R.id.bk_list);
        empty = findViewById(R.id.bk_empty);

        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        algoRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickAlgo(); }
        });

        adapter = new Adapter();
        list.setAdapter(adapter);
        applyChrome();
        renderAlgo();
        load();
    }

    protected void onResume() {
        super.onResume();
        renderAlgo();
        load();
    }

    // ── 配色：与设置页保持同一套规则（浅色 / 深色 / 纯黑） ─────────────
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
        fg = night ? 0xFFEDEDED : 0xFF1B1B1F;
        sec = black ? 0xFF8A8F96 : (night ? 0xFF9AA0A6 : 0xFF5F6368);
        int line = black ? 0xFF1C1C1C : (night ? 0xFF2C2D31 : 0xFFE3E3E8);

        root.setBackgroundColor(bg);
        header.setBackgroundColor(bg);
        title.setTextColor(fg);
        btnBack.setColorFilter(fg);
        algoLabel.setTextColor(fg);
        algoValue.setTextColor(sec);
        algoArrow.setTextColor(sec);
        divider.setBackgroundColor(line);
        empty.setTextColor(sec);
        list.setBackgroundColor(bg);
    }

    // ── 压缩设置 ─────────────────────────────────────────────────────
    private String currentAlgo() {
        return sp().getString("backup_algo", "zst");
    }

    private int currentLevel(String a) {
        return sp().getInt("backup_level_" + a, AppData.defaultLevel(a));
    }

    private String algoName(String a) {
        if ("zst".equals(a)) return getString(R.string.backup_algo_zst);
        if ("gz".equals(a)) return getString(R.string.backup_algo_gz);
        if ("bz2".equals(a)) return getString(R.string.backup_algo_bz2);
        return getString(R.string.backup_algo_none);
    }

    private String algoSummary() {
        String a = currentAlgo();
        if ("none".equals(a)) return algoName(a);
        int lv = currentLevel(a);
        return algoName(a) + " · " + (lv == AppData.defaultLevel(a)
                ? getString(R.string.backup_level_def, lv)
                : getString(R.string.backup_level_item, lv));
    }

    private void renderAlgo() {
        algoValue.setText(algoSummary());
    }

    private void pickAlgo() {
        final String[] algos = {"zst", "gz", "bz2", "none"};
        String[] items = new String[algos.length];
        for (int i = 0; i < algos.length; i++) {
            items[i] = algoName(algos[i]);
            if (!"none".equals(algos[i])) {
                items[i] += " · " + getString(R.string.backup_level_def,
                        AppData.defaultLevel(algos[i]));
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_algo_pick)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        pickLevel(algos[which]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 级别选择。⚠ 不能同时用 setMessage 和 setItems：两者共用同一个 content 区域，
     * 后设置的会把先设置的顶掉（上一版就是这么把级别列表弄没的）。说明放进标题。
     */
    private void pickLevel(final String algo) {
        final int[] lv = AppData.levelsOf(algo);
        if (lv.length == 0) {
            saveAlgo(algo, 0);
            return;
        }
        final int def = AppData.defaultLevel(algo);
        String[] items = new String[lv.length];
        for (int i = 0; i < lv.length; i++) {
            items[i] = lv[i] == def ? getString(R.string.backup_level_def, lv[i])
                    : getString(R.string.backup_level_item, lv[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.backup_level_title, algoName(algo)))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        saveAlgo(algo, lv[which]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveAlgo(String algo, int level) {
        sp().edit().putString("backup_algo", algo)
                .putInt("backup_level_" + algo, level).apply();
        renderAlgo();
        toast(getString(R.string.backup_algo_saved, algoSummary()));
    }

    // ── 列表 ─────────────────────────────────────────────────────────
    private void load() {
        new Thread(new Runnable() {
            public void run() {
                final java.util.List<AppData.Backup> r = AppData.listBackups();
                runOnUiThread(new Runnable() {
                    public void run() {
                        items.clear();
                        items.addAll(r);
                        adapter.notifyDataSetChanged();
                        boolean none = items.isEmpty();
                        empty.setVisibility(none ? View.VISIBLE : View.GONE);
                        list.setVisibility(none ? View.GONE : View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    private String labelOf(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private int appIdOf(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0).uid % 100000;
        } catch (Throwable t) {
            return -1;
        }
    }

    private class Adapter extends BaseAdapter {
        public int getCount() { return items.size(); }

        public Object getItem(int i) { return items.get(i); }

        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(BackupActivity.this)
                        .inflate(R.layout.item_backup, parent, false);
            }
            final AppData.Backup b = items.get(i);
            TextView t1 = v.findViewById(R.id.item_title);
            TextView t2 = v.findViewById(R.id.item_sub);
            TextView more = v.findViewById(R.id.item_more);

            t1.setText(labelOf(b.pkg));
            t1.setTextColor(fg);
            StringBuilder sub = new StringBuilder();
            sub.append(CloneStore.nameOf(BackupActivity.this, b.user))
                    .append(" · ").append(b.ts)
                    .append(" · ").append(b.sizeText());
            if (b.packed()) sub.append(" · ").append(getString(R.string.backup_packed));
            t2.setText(sub.toString());
            t2.setTextColor(sec);
            more.setTextColor(sec);

            View.OnClickListener open = new View.OnClickListener() {
                public void onClick(View x) { actions(b); }
            };
            v.setOnClickListener(open);
            more.setOnClickListener(open);
            return v;
        }
    }

    // ── 每项操作 ─────────────────────────────────────────────────────
    private void actions(final AppData.Backup b) {
        final boolean canZip = !b.packed();
        String[] acts = canZip
                ? new String[]{getString(R.string.backup_restore),
                        getString(R.string.backup_export),
                        getString(R.string.backup_compress),
                        getString(R.string.backup_delete)}
                : new String[]{getString(R.string.backup_restore),
                        getString(R.string.backup_export),
                        getString(R.string.backup_delete)};

        new AlertDialog.Builder(this)
                .setTitle(labelOf(b.pkg) + " · " + b.ts)
                .setItems(acts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            confirm(R.string.backup_restore, R.string.backup_restore_msg, b, 0);
                        } else if (which == 1) {
                            job(b, 1);
                        } else if (canZip && which == 2) {
                            confirm(R.string.backup_compress,
                                    R.string.backup_compress_msg, b, 3);
                        } else {
                            confirm(R.string.backup_delete, R.string.backup_delete_msg, b, 2);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirm(int titleRes, int msgRes, final AppData.Backup b, final int kind) {
        String msg = getString(msgRes);
        if (kind == 3) msg = msg + "\n\n" + algoSummary();
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { job(b, kind); }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** kind: 0 恢复、1 导出、2 删除、3 压缩 */
    private void job(final AppData.Backup b, final int kind) {
        toast(getString(kind == 1 ? R.string.backup_exporting
                : kind == 3 ? R.string.backup_compressing : R.string.backup_working));
        new Thread(new Runnable() {
            public void run() {
                String r;
                if (kind == 0) r = AppData.restore(b, appIdOf(b.pkg));
                else if (kind == 1) r = AppData.export(b);
                else if (kind == 3) r = AppData.compress(b, currentAlgo(), currentLevel(currentAlgo()));
                else r = AppData.delete(b);
                final String fr = r;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fr.startsWith("OK")) {
                            if (kind == 0) toast(getString(R.string.backup_restore_ok));
                            else if (kind == 1) toast(getString(R.string.backup_export_ok,
                                    fr.length() > 3 ? fr.substring(3).trim() : ""));
                            else if (kind == 3) {
                                String rest = fr.length() > 3 ? fr.substring(3).trim() : "";
                                int bar = rest.lastIndexOf('|');
                                String path = bar > 0 ? rest.substring(0, bar) : rest;
                                String actual = bar > 0 ? rest.substring(bar + 1) : "";
                                toast(getString(R.string.backup_compress_ok,
                                        path.substring(path.lastIndexOf('/') + 1)));
                                if (actual.length() > 0 && !actual.equals(currentAlgo())) {
                                    toast(getString(R.string.backup_compress_fallback, algoName(actual)));
                                }
                            } else toast(getString(R.string.backup_delete_ok));
                            load();
                        } else {
                            toast(getString(R.string.backup_fail, fr));
                        }
                    }
                });
            }
        }).start();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
