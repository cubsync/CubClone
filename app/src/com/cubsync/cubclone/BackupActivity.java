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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据备份页（克隆时自动留的那份和长按「备份数据」建的都在这里）。交互按常见备份工具的做法：
 *   列表是页面主体；点一行或右侧 ⋮ 出操作；长按进多选；顶部搜索 + 空间筛选；
 *   没有 root 时页面顶部给出明确提示（备份在 /data/user 下，Shizuku 读不到）。
 */
public class BackupActivity extends Activity {

    private ListView list;
    private TextView empty;
    private TextView title;
    private TextView hint;
    private ImageButton btnBack;
    private LinearLayout root;
    private LinearLayout header;
    private LinearLayout algoRow;
    private View divider;
    private TextView algoLabel;
    private TextView algoValue;
    private TextView algoArrow;
    private EditText search;
    private TextView filter;
    private TextView selCount;
    private TextView selAll;
    private TextView selMore;

    private final List<AppData.Backup> all = new ArrayList<AppData.Backup>();
    private final List<AppData.Backup> shown = new ArrayList<AppData.Backup>();
    private final Set<String> selected = new HashSet<String>();
    private final java.util.HashMap<String, String> labelCache = new java.util.HashMap<String, String>();

    private String query = "";
    private final Set<Integer> filterUsers = new HashSet<Integer>();
    private boolean multi = false;
    private boolean rootOk = false;

    private Adapter adapter;
    private int fg = 0xFF1B1B1F;
    private int sec = 0xFF5F6368;
    private int accent = 0xFF0B57D0;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_backup);

        root = findViewById(R.id.bk_root);
        header = findViewById(R.id.bk_header);
        title = findViewById(R.id.bk_title);
        hint = findViewById(R.id.bk_hint);
        btnBack = findViewById(R.id.bk_back);
        algoRow = findViewById(R.id.bk_algo_row);
        algoLabel = findViewById(R.id.bk_algo_label);
        algoValue = findViewById(R.id.bk_algo_value);
        algoArrow = findViewById(R.id.bk_algo_arrow);
        divider = findViewById(R.id.bk_divider);
        list = findViewById(R.id.bk_list);
        empty = findViewById(R.id.bk_empty);
        search = findViewById(R.id.bk_search);
        filter = findViewById(R.id.bk_filter);
        selCount = findViewById(R.id.bk_sel_count);
        selAll = findViewById(R.id.bk_sel_all);
        selMore = findViewById(R.id.bk_sel_more);

        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (multi) exitMulti();
                else finish();
            }
        });
        algoRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!rootOk) {
                    toast(getString(R.string.backup_need_root));
                    return;
                }
                pickAlgo();
            }
        });
        filter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFilter(); }
        });
        selAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSelectAll(); }
        });
        selMore.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { multiActions(); }
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }

            public void onTextChanged(CharSequence s, int a, int b2, int c) {
                query = s.toString().trim().toLowerCase();
                refilter();
            }

            public void afterTextChanged(Editable s) { }
        });

        adapter = new Adapter();
        list.setAdapter(adapter);
        applyChrome();
        renderAlgo();
        boot();
    }

    protected void onResume() {
        super.onResume();
        applyChrome();
        renderAlgo();
    }

    public void onBackPressed() {
        if (multi) exitMulti();
        else super.onBackPressed();
    }

    /** 先探 root 再扫备份：没 root 时列表永远是空的，不给提示会让人以为是没备份 */
    private void boot() {
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = Shell.checkRoot();
                // 每次打开都实测一遍可用的算法：换机器、系统升级、刷了别的 ROM 都会变，
                // 存下来的选择可能已经不能用，这时按 zstd → gzip → bzip2 降级并告知
                String fell = null;
                if (ok) {
                    java.util.List<String> usable = AppData.usableAlgos();
                    String cur = currentAlgo();
                    if (!usable.contains(cur)) {
                        String fb = usable.isEmpty() ? "none" : usable.get(0);
                        sp().edit().putString("backup_algo", fb).apply();
                        fell = getString(R.string.backup_algo_auto, algoName(cur), algoName(fb));
                    }
                }
                final List<AppData.Backup> r = ok ? AppData.listBackups()
                        : new ArrayList<AppData.Backup>();
                final String fbMsg = fell;
                runOnUiThread(new Runnable() {
                    public void run() {
                        rootOk = ok;
                        renderAlgo();
                        hint.setVisibility(ok ? View.GONE : View.VISIBLE);
                        if (!ok) {
                            hint.setText(R.string.backup_need_root_hint);
                            hint.setTextColor(sec);
                        }
                        algoRow.setAlpha(ok ? 1f : 0.45f);
                        all.clear();
                        all.addAll(r);
                        refilter();
                        if (fbMsg != null) toast(fbMsg);
                    }
                });
            }
        }).start();
    }

    // ── 配色（与设置页同一套规则） ────────────────────────────────────
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
        accent = night ? 0xFF9EC1FF : 0xFF0B57D0;
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
        search.setTextColor(fg);
        search.setHintTextColor(sec);
        filter.setTextColor(accent);
        selCount.setTextColor(sec);
        selAll.setTextColor(accent);
        selMore.setTextColor(fg);
        if (hint.getVisibility() == View.VISIBLE) hint.setTextColor(sec);
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

    /** 主界面只报实际用的算法和级别，不标「默认档」——那会被当成推荐值 */
    private String algoSummary() {
        String a = currentAlgo();
        if ("none".equals(a)) return algoName(a);
        return algoName(a) + " · " + getString(R.string.backup_level_item, currentLevel(a));
    }

    private void renderAlgo() {
        algoValue.setText(algoSummary());
    }

    private void pickAlgo() {
        final String[] algos = {"zst", "gz", "bz2", "none"};
        String[] items = new String[algos.length];
        int cur = 0;
        for (int i = 0; i < algos.length; i++) {
            items[i] = algoName(algos[i]);
            if (algos[i].equals(currentAlgo())) cur = i;
        }
        // 单选列表：当前用的算法直接打勾，点哪项就切哪项并接着选级别
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_algo_pick)
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        pickLevel(algos[which]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 级别用 SeekBar + 实时数值，覆盖算法完整范围（zstd 1–22 / gzip 1–9 / bzip2 1–9） */
    private void pickLevel(final String algo) {
        final int max = AppData.maxLevel(algo);
        if (max <= 0) {
            saveAlgo(algo, 0);
            return;
        }
        final int min = 1;
        int cur = currentLevel(algo);
        if (cur < min || cur > max) cur = AppData.defaultLevel(algo);

        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (12 * d), (int) (20 * d), 0);

        final TextView value = new TextView(this);
        value.setTextSize(16);
        value.setTextColor(fg);
        value.setText(getString(R.string.backup_level_item, cur));
        box.addView(value);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(cur - min);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * d);
        box.addView(bar, lp);

        TextView note = new TextView(this);
        note.setTextSize(12);
        note.setTextColor(sec);
        note.setText(R.string.backup_level_note);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = (int) (6 * d);
        box.addView(note, lp2);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                value.setText(getString(R.string.backup_level_item, progress + 1));
            }

            public void onStartTrackingTouch(SeekBar s) { }

            public void onStopTrackingTouch(SeekBar s) { }
        });

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.backup_level_title, algoName(algo)))
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int w) {
                        saveAlgo(algo, bar.getProgress() + 1);
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

    // ── 搜索 / 空间筛选 ───────────────────────────────────────────────
    private void refilter() {
        shown.clear();
        for (AppData.Backup b : all) {
            if (!filterUsers.isEmpty() && !filterUsers.contains(Integer.valueOf(b.user))) continue;
            if (query.length() > 0) {
                String label = labelOf(b.pkg).toLowerCase();
                if (!label.contains(query) && !b.pkg.toLowerCase().contains(query)) continue;
            }
            shown.add(b);
        }
        // 多选状态下把过滤掉的项取消选中，避免删到看不见的东西
        Set<String> keep = new HashSet<String>();
        for (AppData.Backup b : shown) {
            if (selected.contains(b.path)) keep.add(b.path);
        }
        selected.retainAll(keep);

        adapter.notifyDataSetChanged();
        boolean none = shown.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        if (none) {
            empty.setText(all.isEmpty() ? getString(R.string.backup_empty_hint)
                    : getString(R.string.backup_no_match));
        }
        renderFilter();
        renderMultiBar();
    }

    private void renderFilter() {
        StringBuilder sb = new StringBuilder();
        if (filterUsers.isEmpty()) {
            sb.append(getString(R.string.backup_filter_all));
        } else if (filterUsers.size() == 1) {
            for (Integer u : filterUsers) sb.append(CloneStore.nameOf(this, u.intValue()));
        } else {
            sb.append(getString(R.string.backup_filter_count, filterUsers.size()));
        }
        if (query.length() > 0) sb.append(" • ").append(query);
        sb.append(" ▾");
        filter.setText(sb.toString());
    }

    /** 空间筛选支持多选：勾几个就看几个空间，全不勾等于全部 */
    private void pickFilter() {
        final List<Integer> users = new ArrayList<Integer>();
        for (AppData.Backup b : all) {
            if (!users.contains(Integer.valueOf(b.user))) users.add(Integer.valueOf(b.user));
        }
        if (users.isEmpty()) return;
        String[] items = new String[users.size()];
        final boolean[] checked = new boolean[users.size()];
        for (int i = 0; i < users.size(); i++) {
            int u = users.get(i).intValue();
            items[i] = CloneStore.nameOf(this, u);
            checked[i] = filterUsers.contains(Integer.valueOf(u));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_filter_title)
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
                        refilter();
                    }
                })
                .setNeutralButton(R.string.backup_filter_reset, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        filterUsers.clear();
                        search.setText("");
                        query = "";
                        refilter();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── 多选 ─────────────────────────────────────────────────────────
    private void enterMulti(AppData.Backup first) {
        multi = true;
        selected.clear();
        if (first != null) selected.add(first.path);
        adapter.notifyDataSetChanged();
        renderMultiBar();
    }

    private void exitMulti() {
        multi = false;
        selected.clear();
        adapter.notifyDataSetChanged();
        renderMultiBar();
    }

    private void toggleSelect(AppData.Backup b) {
        if (selected.contains(b.path)) selected.remove(b.path);
        else selected.add(b.path);
        adapter.notifyDataSetChanged();
        renderMultiBar();
    }

    private void toggleSelectAll() {
        boolean allSel = shown.size() > 0 && selected.size() == shown.size();
        selected.clear();
        if (!allSel) {
            for (AppData.Backup b : shown) selected.add(b.path);
        }
        adapter.notifyDataSetChanged();
        renderMultiBar();
    }

    private void renderMultiBar() {
        int n = selected.size();
        selCount.setVisibility(multi ? View.VISIBLE : View.GONE);
        selAll.setVisibility(multi ? View.VISIBLE : View.GONE);
        selMore.setVisibility(multi ? View.VISIBLE : View.GONE);
        title.setText(multi ? getString(R.string.backup_sel_title) : getString(R.string.sec_backup));
        if (multi) {
            selCount.setText(getString(R.string.backup_sel_count, n));
            boolean allSel = shown.size() > 0 && n == shown.size();
            selAll.setText(allSel ? R.string.backup_sel_none : R.string.backup_sel_all);
        }
    }

    private List<AppData.Backup> selectedItems() {
        List<AppData.Backup> r = new ArrayList<AppData.Backup>();
        for (AppData.Backup b : shown) {
            if (selected.contains(b.path)) r.add(b);
        }
        return r;
    }

    private void multiActions() {
        final List<AppData.Backup> sel = selectedItems();
        if (sel.isEmpty()) {
            toast(getString(R.string.backup_sel_empty));
            return;
        }
        boolean hasDir = false;
        for (AppData.Backup b : sel) if (!b.packed()) hasDir = true;
        final boolean canZip = hasDir;
        String[] acts = canZip
                ? new String[]{getString(R.string.backup_sel_delete),
                        getString(R.string.backup_sel_compress)}
                : new String[]{getString(R.string.backup_sel_delete)};

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.backup_sel_count, sel.size()))
                .setItems(acts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            new AlertDialog.Builder(BackupActivity.this)
                                    .setTitle(R.string.backup_delete)
                                    .setMessage(getString(R.string.backup_delete_many_msg, sel.size()))
                                    .setPositiveButton(android.R.string.ok,
                                            new DialogInterface.OnClickListener() {
                                                public void onClick(DialogInterface dd, int w) {
                                                    jobManyDelete(sel);
                                                }
                                            })
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show();
                        } else {
                            jobManyCompress(sel);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void jobManyDelete(final List<AppData.Backup> sel) {
        toast(getString(R.string.backup_working));
        new Thread(new Runnable() {
            public void run() {
                final String r = AppData.deleteMany(sel);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (r.startsWith("OK ")) {
                            toast(getString(R.string.backup_delete_many_ok, r.substring(3).trim()));
                            exitMulti();
                            boot();
                        } else {
                            toast(getString(R.string.backup_fail, r));
                        }
                    }
                });
            }
        }).start();
    }

    private void jobManyCompress(final List<AppData.Backup> sel) {
        toast(getString(R.string.backup_compressing));
        new Thread(new Runnable() {
            public void run() {
                final String algo = currentAlgo();
                final int lv = currentLevel(algo);
                int ok = 0, bad = 0;
                for (AppData.Backup b : sel) {
                    if (b.packed()) continue;
                    String r = AppData.compress(b, algo, lv);
                    if (r.startsWith("OK")) ok++;
                    else bad++;
                }
                final int fok = ok, fbad = bad;
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(getString(R.string.backup_done_many, fok, fbad));
                        exitMulti();
                        boot();
                    }
                });
            }
        }).start();
    }

    // ── 列表 ─────────────────────────────────────────────────────────
    private void reloadList() {
        new Thread(new Runnable() {
            public void run() {
                final List<AppData.Backup> r = AppData.listBackups();
                runOnUiThread(new Runnable() {
                    public void run() {
                        all.clear();
                        all.addAll(r);
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

    private int appIdOf(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0).uid % 100000;
        } catch (Throwable t) {
            return -1;
        }
    }

    private class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }

        public Object getItem(int i) { return shown.get(i); }

        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(BackupActivity.this)
                        .inflate(R.layout.item_backup, parent, false);
            }
            final AppData.Backup b = shown.get(i);
            TextView t1 = v.findViewById(R.id.item_title);
            TextView t2 = v.findViewById(R.id.item_sub);
            TextView more = v.findViewById(R.id.item_more);
            CheckBox check = v.findViewById(R.id.item_check);

            t1.setText(labelOf(b.pkg));
            t1.setTextColor(fg);
            StringBuilder sub = new StringBuilder();
            sub.append(CloneStore.nameOf(BackupActivity.this, b.user))
                    .append(" · ").append(b.ts)
                    .append(" · ").append(b.sizeText());
            if (b.packed()) {
                sub.append(" · ").append(getString(R.string.backup_packed));
                String an = b.algo;
                if ("zst".equals(an)) an = "zstd";
                else if ("gz".equals(an)) an = "gzip";
                else if ("bz2".equals(an)) an = "bzip2";
                if (an.length() > 0) sub.append(" ").append(an);
            }
            t2.setText(sub.toString());
            t2.setTextColor(sec);
            more.setTextColor(sec);

            check.setVisibility(multi ? View.VISIBLE : View.GONE);
            check.setChecked(selected.contains(b.path));
            more.setVisibility(multi ? View.GONE : View.VISIBLE);

            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    if (multi) toggleSelect(b);
                    else actions(b);
                }
            });
            v.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View x) {
                    if (!multi) enterMulti(b);
                    return true;
                }
            });
            more.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { actions(b); }
            });
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
                            reloadList();
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
