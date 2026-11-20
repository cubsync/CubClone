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
    private TextView addBtn;
    /** 备份目录那一行：点开选目录，换目录会把旧备份一起搬过去 */
    private LinearLayout dirRow;
    private TextView dirLabel;
    private TextView dirValue;
    private TextView dirArrow;
    /** 多选时显示：和全选放一起，不再塞进 ⋮ 菜单 */
    private TextView selInv;
    private TextView selCount;
    /** 批量任务的进度：bk_progress 整行藏在 algo_row 下面，任务跑起来才显示 */
    private android.view.View progWrap;
    private android.widget.ProgressBar progBar;
    private TextView progText;
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
    /** 本机实测可用的压缩算法（boot 时刷新）；null = 还没测过，别乱标 */
    private java.util.List<String> usable;

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
        addBtn = findViewById(R.id.bk_add);
        dirRow = findViewById(R.id.bk_dir_row);
        dirLabel = findViewById(R.id.bk_dir_label);
        dirValue = findViewById(R.id.bk_dir_value);
        dirArrow = findViewById(R.id.bk_dir_arrow);
        selInv = findViewById(R.id.bk_sel_inv);
        dirRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickBackupDir(); }
        });
        selInv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { invertSelection(); }
        });
        progWrap = findViewById(R.id.bk_progress);
        progBar = findViewById(R.id.bk_progress_bar);
        progText = findViewById(R.id.bk_progress_text);
        selCount = findViewById(R.id.bk_sel_count);
        selAll = findViewById(R.id.bk_sel_all);
        selMore = findViewById(R.id.bk_sel_more);

        addBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!rootOk) {
                    toast(getString(R.string.backup_need_root));
                    return;
                }
                startActivity(new android.content.Intent(
                        BackupActivity.this, BatchBackupActivity.class));
            }
        });
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

        AppData.loadRoot(sp());
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
        // 从批量备份页回来要看到新出来的备份
        if (rootOk) reloadList();
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
                // 打开页面实测一次可用算法（换机器/刷 ROM 会变）
                if (ok) {
                    usable = AppData.usableAlgos();
                    // 选中的算法本机跑不起来就静默换成本机实际可用的那个并存回：
                    // 主界面只报「实际在用」的，不能再出现「zstd（本机不可用）」这种自相矛盾的显示。
                    // 这里不弹 toast —— 只是打开页面，弹了太吵；用户主动选算法时才提示。
                    String cur = currentAlgo();
                    if (!"none".equals(cur) && !usable.contains(cur)) {
                        sp().edit().putString("backup_algo",
                                AppData.nextUsable(cur, usable)).apply();
                    }
                    // 预热空间名缓存，列表每行就不用再起一次 shell
                    CloneStore.listSpaceUids();
                }
                final List<AppData.Backup> r = ok ? AppData.listBackups()
                        : new ArrayList<AppData.Backup>();
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
        selInv.setTextColor(accent);
        addBtn.setTextColor(accent);
        dirLabel.setTextColor(fg);
        dirValue.setTextColor(sec);
        dirArrow.setTextColor(sec);
        selMore.setTextColor(fg);
        renderDir();
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

    /**
     * 主界面只报实际在用的算法和级别：不标「默认档」（会被当成推荐值），
     * 也不标「本机不可用」（boot 已经把它换成能用的那个了）。
     */
    private String algoSummary() {
        String a = currentAlgo();
        if ("none".equals(a)) return algoName(a);
        return algoName(a) + " · " + getString(R.string.backup_level_item, currentLevel(a));
    }

    private boolean algoUsable(String a) {
        if ("none".equals(a)) return true;
        if (usable == null) return true;     // 还没测过就别乱标
        return usable.contains(a);
    }

    /** 空间名 + uid：批量操作里光看名字分不清是哪个号 */
    private String spaceLabel(int u) {
        String n = u == 0 ? getString(R.string.space_main) : CloneStore.nameOf(this, u);
        return n + " (" + u + ")";
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
            if (!algoUsable(algos[i])) {
                items[i] = items[i] + "（" + getString(R.string.backup_algo_bad) + "）";
            }
            if (algos[i].equals(currentAlgo())) cur = i;
        }
        // 单选列表：当前用的算法直接打勾，点哪项就切哪项并接着选级别。
        // 选到本机不可用的，就地按 zstd → gzip → bzip2 → 纯打包 回落并告知
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_algo_pick)
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        String pick = algos[which];
                        if (!algoUsable(pick)) {
                            String fb = AppData.nextUsable(pick, usable);
                            toast(getString(R.string.backup_algo_auto, algoName(pick), algoName(fb)));
                            pick = fb;
                        }
                        pickLevel(pick);
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
            items[i] = spaceLabel(u);
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
        addBtn.setVisibility((rootOk && !multi) ? View.VISIBLE : View.GONE);
        if (dirRow != null) dirRow.setVisibility(multi ? View.GONE : View.VISIBLE);
        selCount.setVisibility(multi ? View.VISIBLE : View.GONE);
        selAll.setVisibility(multi ? View.VISIBLE : View.GONE);
        if (selInv != null) selInv.setVisibility(multi ? View.VISIBLE : View.GONE);
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
        // 顺序：恢复 / 压缩（只有目录形态才有）/ 删除；反选在顶栏
        String[] acts = canZip
                ? new String[]{getString(R.string.backup_sel_restore),
                        getString(R.string.backup_sel_compress),
                        getString(R.string.backup_sel_delete)}
                : new String[]{getString(R.string.backup_sel_restore),
                        getString(R.string.backup_sel_delete)};
        final int iDelete = canZip ? 2 : 1;

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.backup_sel_count, sel.size()))
                .setItems(acts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            jobManyRestore(sel);
                            return;
                        }
                        if (which == iDelete) {
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

    /** 进度行：done/total 同时喂进度条和右边的「3/12」 */
    private void showProgress(final int done, final int total, final int labelRes) {
        runOnUiThread(new Runnable() {
            public void run() {
                progWrap.setVisibility(android.view.View.VISIBLE);
                progBar.setMax(total);
                progBar.setProgress(done);
                progText.setText(getString(labelRes) + " " + done + "/" + total);
            }
        });
    }

    private void hideProgress() {
        progWrap.setVisibility(android.view.View.GONE);
    }

    /** 从 Download/CubClone-backup 里挑一个导出的包，搬回对应空间并恢复 */
                private int appIdOf(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0).uid % 100000;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 批量导出：串行 cp，每导完一个刷一次进度。
     * 导出位置沿用单条的 Download/CubClone-backup，一次只动一个文件。
     */
        /**
     * 批量恢复。同一空间同一应用有多份时**只恢复最新的那份**：
     * 按 ts 从旧到新依次恢复，结果是旧的覆盖新的，等于白跑还丢了新数据。
     * ts 是 yyyyMMdd-HHmmss，字典序就是时间序。
     */
    private void jobManyRestore(final List<AppData.Backup> sel) {
        final java.util.LinkedHashMap<String, AppData.Backup> newest =
                new java.util.LinkedHashMap<String, AppData.Backup>();
        for (AppData.Backup b : sel) {
            String key = b.user + "/" + b.pkg;
            AppData.Backup old = newest.get(key);
            if (old == null || b.ts.compareTo(old.ts) > 0) newest.put(key, b);
        }
        final int skipped = sel.size() - newest.size();
        final List<AppData.Backup> todo = new ArrayList<AppData.Backup>(newest.values());
        showProgress(0, todo.size(), R.string.backup_restoring);
        new Thread(new Runnable() {
            public void run() {
                int ok = 0, bad = 0;
                for (int i = 0; i < todo.size(); i++) {
                    AppData.Backup b = todo.get(i);
                    int appId = appIdOf(b.pkg);
                    String r = appId < 0 ? "ERR_NO_APP" : AppData.restore(b, appId);
                    if (r.startsWith("OK")) ok++;
                    else bad++;
                    showProgress(i + 1, todo.size(), R.string.backup_restoring);
                }
                final int fok = ok, fbad = bad;
                runOnUiThread(new Runnable() {
                    public void run() {
                        hideProgress();
                        toast(getString(R.string.backup_restore_many_done, fok, fbad));
                        if (skipped > 0) {
                            toast(getString(R.string.backup_restore_many_skip, skipped));
                        }
                        exitMulti();
                        boot();
                    }
                });
            }
        }).start();
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
        showProgress(0, sel.size(), R.string.backup_compressing);
        new Thread(new Runnable() {
            public void run() {
                final String algo = currentAlgo();
                final int lv = currentLevel(algo);
                int ok = 0, bad = 0, done = 0;
                for (AppData.Backup b : sel) {
                    if (b.packed()) continue;
                    String r = AppData.compress(b, algo, lv);
                    if (r.startsWith("OK")) ok++;
                    else bad++;
                    done++;
                    showProgress(done, sel.size(), R.string.backup_compressing);
                }
                final int fok = ok, fbad = bad;
                runOnUiThread(new Runnable() {
                    public void run() {
                        hideProgress();
                        toast(getString(R.string.backup_done_many, fok, fbad));
                        exitMulti();
                        boot();
                    }
                });
            }
        }).start();
    }

    /** 反选：只看当前过滤出来的这一批，避免把看不见的也选上 */
    private void invertSelection() {
        for (AppData.Backup b : shown) {
            if (selected.contains(b.path)) selected.remove(b.path);
            else selected.add(b.path);
        }
        adapter.notifyDataSetChanged();
        renderMultiBar();
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
            sub.append(spaceLabel(b.user))
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
                        getString(R.string.backup_compress),
                        getString(R.string.backup_delete)}
                : new String[]{getString(R.string.backup_restore),
                        getString(R.string.backup_delete)};

        new AlertDialog.Builder(this)
                .setTitle(labelOf(b.pkg) + " · " + b.ts)
                .setItems(acts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            confirm(R.string.backup_restore, R.string.backup_restore_msg, b, 0);
                        } else if (canZip && which == 1) {
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

    /** kind: 0 恢复、2 删除、3 压缩 */
    private void job(final AppData.Backup b, final int kind) {
        toast(getString(kind == 3 ? R.string.backup_compressing : R.string.backup_working));
        new Thread(new Runnable() {
            public void run() {
                String r;
                if (kind == 0) r = AppData.restore(b, appIdOf(b.pkg));
                else if (kind == 3) r = AppData.compress(b, currentAlgo(), currentLevel(currentAlgo()));
                else r = AppData.delete(b);
                final String fr = r;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fr.startsWith("OK")) {
                            if (kind == 0) toast(getString(R.string.backup_restore_ok));
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

    // ── 备份目录 ─────────────────────────────────────────────────────
    private void renderDir() {
        if (dirValue != null) dirValue.setText(AppData.root());
    }

    /** 从当前目录的父级开始浏览 /sdcard：root 直读目录树，不需要 SAF */
    private void pickBackupDir() {
        if (!rootOk) {
            toast(getString(R.string.backup_need_root));
            return;
        }
        String cur = AppData.root();
        int slash = cur.lastIndexOf('/');
        String start = slash > 0 ? cur.substring(0, slash) : "/sdcard";
        browseDir(start);
    }

    private void browseDir(final String dir) {
        new Thread(new Runnable() {
            public void run() {
                final List<String> subs = new ArrayList<String>();
                String out = Shell.execScript("ls -1p '" + dir + "' 2>/dev/null\n");
                for (String line : out.split("\n")) {
                    String n = line.trim();
                    if (n.length() == 0 || !n.endsWith("/")) continue;
                    n = n.substring(0, n.length() - 1);
                    if (n.startsWith(".")) continue;
                    subs.add(n);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        boolean atTop = "/sdcard".equals(dir) || "/storage/emulated/0".equals(dir);
                        final List<String> items = new ArrayList<String>();
                        final List<String> targets = new ArrayList<String>();
                        if (!atTop) {
                            int slash = dir.lastIndexOf('/');
                            if (slash > 0) {
                                items.add("‹ " + getString(R.string.backup_dir_up));
                                targets.add(dir.substring(0, slash));
                            }
                        }
                        for (String n : subs) {
                            items.add(n + "/");
                            targets.add(dir + "/" + n);
                        }
                        items.add(getString(R.string.backup_dir_use));
                        targets.add(dir);
                        new AlertDialog.Builder(BackupActivity.this)
                                .setTitle(dir)
                                .setItems(items.toArray(new String[items.size()]),
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface d, int which) {
                                                String t = targets.get(which);
                                                if (t.equals(dir)) useDir(dir);
                                                else browseDir(t);
                                            }
                                        })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** 换目录：旧目录里的备份一起搬过来，免得列表突然空了、东西还散在两处 */
    private void useDir(final String dir) {
        final String old = AppData.root();
        if (dir.equals(old)) return;
        sp().edit().putString("backup_dir", dir).apply();
        AppData.setRoot(dir);
        renderDir();
        new Thread(new Runnable() {
            public void run() {
                final String r = AppData.moveBackups(old, dir);
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(r.startsWith("OK ")
                                ? getString(R.string.backup_dir_ok, r.substring(3).trim())
                                : getString(R.string.backup_fail, r));
                        boot();
                    }
                });
            }
        }).start();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
