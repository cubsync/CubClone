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


    /** 界面语言跟随应用设置（Locales），不能只靠系统语言 */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Locales.wrap(base));
    }

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
    private TextView sortTv;
    private TextView addBtn;
    /** 上一次批量任务的日志 */
    private TextView logBtn;
    /** 备份目录那一行：点开选目录，换目录会把旧备份一起搬过去 */
    private LinearLayout dirRow;
    private TextView dirLabel;
    private TextView dirValue;
    private TextView dirArrow;
    /** 备份内容那一行（数据 / 设备数据 / APK / 权限） */
    private LinearLayout partsRow;
    private TextView partsLabel, partsValue, partsArrow;
    private TextView scopeTv;
    /** 多选时显示：和全选放一起，不再塞进 ⋮ 菜单 */
    private TextView selInv;
    private TextView selCount;
    /** 批量任务的进度：bk_progress 整行藏在 algo_row 下面，任务跑起来才显示 */
    private android.view.View progWrap;
    private android.widget.ProgressBar progBar;
    private TextView progText;
    private TextView selAll;
    private TextView selMore;

    private static final int REQ_PICK_DIR = 701;

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
    private int fg = Theme.FG_LIGHT;
    private int sec = Theme.SEC_LIGHT;
    private int accent = Theme.ACCENT_LIGHT;

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
        sortTv = findViewById(R.id.bk_sort);
        addBtn = findViewById(R.id.bk_add);
        logBtn = findViewById(R.id.bk_log);
        dirRow = findViewById(R.id.bk_dir_row);
        dirLabel = findViewById(R.id.bk_dir_label);
        dirValue = findViewById(R.id.bk_dir_value);
        dirArrow = findViewById(R.id.bk_dir_arrow);
        partsRow = findViewById(R.id.bk_parts_row);
        partsLabel = findViewById(R.id.bk_parts_label);
        partsValue = findViewById(R.id.bk_parts_value);
        partsArrow = findViewById(R.id.bk_parts_arrow);
        scopeTv = findViewById(R.id.bk_scope);
        selInv = findViewById(R.id.bk_sel_inv);
        dirValue.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickBackupDir(); }
        });
        partsRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickParts(); }
        });
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

        logBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLog(); }
        });
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
        sortTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickSort(); }
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
                    } else if (usable.contains("zst") && !sp().getBoolean("algo_user_set", false)
                            && !"zst".equals(cur)) {
                        // 以前没有内置 zstd，只能落在 gzip 上；那是降级不是用户的选择。
                        // 用户自己挑过算法（algo_user_set）就不再动他的设置。
                        sp().edit().putString("backup_algo", "zst").apply();
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
        return Theme.isNight(this);
    }

    private void applyChrome() {
        int bg = Theme.bg(this);
        fg = Theme.fg(this);
        sec = Theme.sec(this);
        accent = Theme.accent(this);
        int line = Theme.line(this);

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
        sortTv.setTextColor(accent);
        renderSort();
        selCount.setTextColor(sec);
        selAll.setTextColor(accent);
        selInv.setTextColor(accent);
        addBtn.setTextColor(accent);
        logBtn.setTextColor(accent);
        dirLabel.setTextColor(fg);
        dirValue.setTextColor(sec);
        dirArrow.setTextColor(sec);
        partsLabel.setTextColor(fg);
        partsValue.setTextColor(sec);
        partsArrow.setTextColor(sec);
        scopeTv.setTextColor(sec);
        selMore.setTextColor(fg);
        renderDir();
        renderParts();
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

    /**
     * 备份内容摘要。行里放不下长句，只报勾了哪几项；APK 那份数在对话框里说。
     */
    private void renderParts() {
        int p = AppData.parts(this);
        java.util.List<String> on = new java.util.ArrayList<String>();
        if ((p & AppData.PART_CE) != 0) on.add(getString(R.string.backup_parts_ce_short));
        if ((p & AppData.PART_DE) != 0) on.add(getString(R.string.backup_parts_de_short));
        if ((p & AppData.PART_APK) != 0) on.add(getString(R.string.backup_parts_apk_short));
        if ((p & AppData.PART_PERM) != 0) on.add(getString(R.string.backup_parts_perm_short));
        if ((p & AppData.PART_EXT) != 0) on.add(getString(R.string.backup_parts_ext_short));
        if ((p & AppData.PART_OBB) != 0) on.add(getString(R.string.backup_parts_obb_short));
        if ((p & AppData.PART_MEDIA) != 0) on.add(getString(R.string.backup_parts_media_short));
        StringBuilder sb = new StringBuilder();
        for (String x : on) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(x);
        }
        partsValue.setText(sb.length() == 0 ? getString(R.string.backup_parts_none) : sb.toString());
        scopeTv.setText(getString(R.string.backup_parts_note));
    }

    /** 勾备份内容：数据 / 设备数据 / APK / 权限 + 外部三块（默认关） */
    private void pickParts() {
        final int cur = AppData.parts(this);
        final boolean all = AppData.apkAll(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final CheckBox ce = pickBox(R.string.backup_parts_ce, (cur & AppData.PART_CE) != 0);
        final CheckBox de = pickBox(R.string.backup_parts_de, (cur & AppData.PART_DE) != 0);
        final CheckBox apk = pickBox(R.string.backup_parts_apk, (cur & AppData.PART_APK) != 0);
        final CheckBox apkAll = pickBox(R.string.backup_apk_all, all);
        final CheckBox perm = pickBox(R.string.backup_parts_perm, (cur & AppData.PART_PERM) != 0);
        final CheckBox ext = pickBox(R.string.backup_parts_ext, (cur & AppData.PART_EXT) != 0);
        final CheckBox obb = pickBox(R.string.backup_parts_obb, (cur & AppData.PART_OBB) != 0);
        final CheckBox media = pickBox(R.string.backup_parts_media, (cur & AppData.PART_MEDIA) != 0);
        // 子选项只缩进一点，别缩到和同级选项错开
        int kid = (int) (24 * getResources().getDisplayMetrics().density);
        apkAll.setPadding(kid, 0, 0, 0);
        apkAll.setEnabled((cur & AppData.PART_APK) != 0);
        apk.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                apkAll.setEnabled(on);
                apkAll.setAlpha(on ? 1f : 0.45f);
            }
        });
        apkAll.setAlpha((cur & AppData.PART_APK) != 0 ? 1f : 0.45f);

        box.addView(ce);
        box.addView(de);
        box.addView(apk);
        box.addView(apkAll);
        box.addView(perm);
        box.addView(ext);
        box.addView(obb);
        box.addView(media);

        Theme.dialog(this)
                .setTitle(R.string.backup_parts_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int p = 0;
                        if (ce.isChecked()) p |= AppData.PART_CE;
                        if (de.isChecked()) p |= AppData.PART_DE;
                        if (apk.isChecked()) p |= AppData.PART_APK;
                        if (perm.isChecked()) p |= AppData.PART_PERM;
                        if (ext.isChecked()) p |= AppData.PART_EXT;
                        if (obb.isChecked()) p |= AppData.PART_OBB;
                        if (media.isChecked()) p |= AppData.PART_MEDIA;
                        if (p == 0) p = AppData.PART_CE;     // 全不勾就没东西可备了
                        sp().edit().putInt("backup_parts", p)
                                .putBoolean("backup_apk_all", apk.isChecked() && apkAll.isChecked())
                                .apply();
                        renderParts();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private CheckBox pickBox(int text, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextSize(15);
        c.setChecked(on);
        Theme.check(c, this);
        return c;
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
        Theme.dialog(this)
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

    /**
     * 级别用 SeekBar + 实时数值。每个算法各给两句：当前级别什么效果（跟着滑块变）、
     * 这个算法该怎么调（固定一句话，直接给数字，不列范围）。
     */
    private void pickLevel(final String algo) {
        final int max = AppData.maxLevel(algo);
        if (max <= 0) {
            saveAlgo(algo, 0);
            return;
        }
        final int min = 1;
        final int def = AppData.defaultLevel(algo);
        int cur = currentLevel(algo);
        if (cur < min || cur > max) cur = def;

        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (12 * d), (int) (20 * d), 0);

        final TextView value = new TextView(this);
        value.setTextSize(16);
        value.setTextColor(fg);
        value.setText(levelLabel(cur, def));
        box.addView(value);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(cur - min);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * d);
        box.addView(bar, lp);

        final TextView hintLv = new TextView(this);
        hintLv.setTextSize(12);
        hintLv.setTextColor(sec);
        hintLv.setText(levelHint(algo, cur));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = (int) (2 * d);
        box.addView(hintLv, lp2);

        final TextView advice = new TextView(this);
        advice.setTextSize(12);
        advice.setTextColor(sec);
        advice.setText(android.text.Html.fromHtml(getString(adviceRes(algo))));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = (int) (12 * d);
        box.addView(advice, lp3);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int lv = progress + min;
                value.setText(levelLabel(lv, def));
                hintLv.setText(levelHint(algo, lv));
            }

            public void onStartTrackingTouch(SeekBar s) { }

            public void onStopTrackingTouch(SeekBar s) { }
        });

        Theme.dialog(this)
                .setTitle(getString(R.string.backup_level_title, algoName(algo)))
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int w) {
                        saveAlgo(algo, bar.getProgress() + min);
                    }
                })
                .setNeutralButton(getString(R.string.backup_level_reset, def),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dlg, int w) {
                                // 只把滑块拨回默认并刷新说明，不关窗：想再微调还能继续拖
                                bar.setProgress(def - min);
                                value.setText(levelLabel(def, def));
                                hintLv.setText(levelHint(algo, def));
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 「级别 N」；正好在上游默认值时补「（默认）」 */
    private String levelLabel(int lv, int def) {
        return lv == def ? getString(R.string.backup_level_item_def, lv)
                : getString(R.string.backup_level_item, lv);
    }

    /** 当前级别的效果；数字取自各算法上游默认和实测（见 README「压缩级别」一节） */
    private String levelHint(String algo, int lv) {
        int r;
        if ("zst".equals(algo)) {
            if (lv <= 2) r = R.string.backup_lv_zst_1;
            else if (lv == 3) r = R.string.backup_lv_zst_3;
            else if (lv <= 6) r = R.string.backup_lv_zst_5;
            else if (lv <= 9) r = R.string.backup_lv_zst_9;
            else if (lv <= 12) r = R.string.backup_lv_zst_12;
            else if (lv <= 18) r = R.string.backup_lv_zst_18;
            else r = R.string.backup_lv_zst_19;
        } else if ("gz".equals(algo)) {
            if (lv <= 2) r = R.string.backup_lv_gz_1;
            else if (lv <= 5) r = R.string.backup_lv_gz_5;
            else if (lv == 6) r = R.string.backup_lv_gz_6;
            else if (lv <= 8) r = R.string.backup_lv_gz_8;
            else r = R.string.backup_lv_gz_9;
        } else {
            if (lv <= 5) r = R.string.backup_lv_bz2_5;
            else if (lv <= 8) r = R.string.backup_lv_bz2_8;
            else r = R.string.backup_lv_bz2_9;
        }
        return getString(r);
    }

    private int adviceRes(String algo) {
        if ("zst".equals(algo)) return R.string.backup_lv_advice_zst;
        if ("gz".equals(algo)) return R.string.backup_lv_advice_gz;
        return R.string.backup_lv_advice_bz2;
    }

    private void saveAlgo(String algo, int level) {
        sp().edit().putString("backup_algo", algo)
                .putInt("backup_level_" + algo, level)
                .putBoolean("algo_user_set", true).apply();
        renderAlgo();
        toast(getString(R.string.backup_algo_saved, algoSummary()));
    }

    // ── 搜索 / 空间筛选 ───────────────────────────────────────────────
    private void refilter() {
        sortAll();
        shown.clear();
        for (AppData.Backup b : all) {
            if (!filterUsers.isEmpty() && !filterUsers.contains(Integer.valueOf(b.user))) continue;
            if (query.length() > 0) {
                String label = labelOf(b.pkg).toLowerCase();
                if (!label.contains(query) && !b.pkg.toLowerCase().contains(query)) continue;
            }
            shown.add(b);
        }
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
        Theme.dialog(this)
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

    // ── 排序 ─────────────────────────────────────────────────────────
    /** 维度：time / name / size / space。旧版把方向也塞进这个键（time_desc/time_asc），读到时迁过来。 */
    private String sortKey() {
        String k = sp().getString("backup_sort", "time");
        if ("time_desc".equals(k) || "time_asc".equals(k)) {
            sp().edit().putString("backup_sort", "time")
                    .putBoolean("backup_sort_desc", "time_desc".equals(k)).apply();
            return "time";
        }
        return k;
    }

    /** 方向按维度各记一份：时间/大小默认倒序，名称/空间默认正序 */
    private boolean sortDesc() {
        String k = sortKey();
        String saved = sp().getString("backup_sort_dir_" + k, null);
        if (saved != null) return "desc".equals(saved);
        if ("time".equals(k)) return sp().getBoolean("backup_sort_desc", true);
        return "size".equals(k);
    }

    /** 工具栏只显示当前维度；方向在对话框里选，不在外面挂箭头 */
    private String sortShort() {
        String k = sortKey();
        if ("name".equals(k)) return getString(R.string.backup_sort_name);
        if ("size".equals(k)) return getString(R.string.backup_sort_size);
        if ("space".equals(k)) return getString(R.string.backup_sort_space);
        return getString(R.string.backup_sort_time);
    }

    private void renderSort() {
        sortTv.setText(sortShort() + " ▾");
    }

    private void sortAll() {
        final String key = sortKey();
        final boolean desc = sortDesc();
        java.util.Collections.sort(all, new java.util.Comparator<AppData.Backup>() {
            public int compare(AppData.Backup a, AppData.Backup b) {
                int c;
                if ("name".equals(key)) {
                    c = labelOf(a.pkg).compareToIgnoreCase(labelOf(b.pkg));
                } else if ("size".equals(key)) {
                    c = Long.valueOf(a.sizeKb).compareTo(Long.valueOf(b.sizeKb));
                } else if ("space".equals(key)) {
                    c = Integer.valueOf(a.user).compareTo(Integer.valueOf(b.user));
                } else {
                    c = a.ts.compareTo(b.ts);
                }
                if (desc) c = -c;
                return c != 0 ? c : b.ts.compareTo(a.ts);   // 并列时新的在前
            }
        });
    }

    private void pickSort() {
        final String[] keys = {"time", "name", "size", "space"};
        String[] labels = {
                getString(R.string.backup_sort_time),
                getString(R.string.backup_sort_name),
                getString(R.string.backup_sort_size),
                getString(R.string.backup_sort_space),
        };
        String k = sortKey();
        int cur = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(k)) cur = i;
        SortDialog.show(this, R.string.backup_sort, labels, cur, sortDesc(), new SortDialog.OnPick() {
            public void onPick(int index, boolean desc) {
                sp().edit()
                        .putString("backup_sort", keys[index])
                        .putString("backup_sort_dir_" + keys[index], desc ? "desc" : "asc")
                        .apply();
                renderSort();
                refilter();
            }
        });
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
        logBtn.setVisibility((rootOk && !multi) ? View.VISIBLE : View.GONE);
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

        Theme.dialog(this)
                .setTitle(getString(R.string.backup_sel_count, sel.size()))
                .setItems(acts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            jobManyRestore(sel);
                            return;
                        }
                        if (which == iDelete) {
                            Theme.dialog(BackupActivity.this)
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
        /** 批量恢复走独立过程页：那里有进度、逐项状态、失败筛选和日志 */
    private void jobManyRestore(final List<AppData.Backup> sel) {
        if (BatchJob.get() != null && BatchJob.get().running) {
            toast(getString(R.string.batch_busy));
            return;
        }
        RestoreDialog.show(this, sel.get(0).user, sel.size(), new RestoreDialog.OnOk() {
            public void onOk(int targetUser, int parts) {
                String[] paths = new String[sel.size()];
                for (int i = 0; i < sel.size(); i++) paths[i] = sel.get(i).path;
                android.content.Intent it = new android.content.Intent(
                        BackupActivity.this, BatchRunActivity.class);
                it.putExtra(BatchRunActivity.EXTRA_MODE, BatchRunActivity.MODE_RESTORE);
                it.putExtra(BatchRunActivity.EXTRA_PATHS, paths);
                it.putExtra(BatchRunActivity.EXTRA_TARGET_USER, targetUser);
                it.putExtra(BatchRunActivity.EXTRA_PARTS, parts);
                startActivity(it);
                exitMulti();
            }
        });
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
            // 体积放最前：后面还有空间、时间、算法，单行放不下时先被截掉的就是尾巴
            sub.append(b.sizeText())
                    .append(" · ").append(spaceLabel(b.user))
                    .append(" · ").append(b.ts);
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

        Theme.dialog(this)
                .setTitle(labelOf(b.pkg) + " · " + b.ts)
                .setItems(acts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            RestoreDialog.show(BackupActivity.this, b.user, 1,
                                    new RestoreDialog.OnOk() {
                                        public void onOk(int targetUser, int parts) {
                                            jobRestore(b, targetUser, parts);
                                        }
                                    });
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
        Theme.dialog(this)
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
                if (kind == 0) r = AppData.restore(b, appIdOf(b.pkg), b.user, AppData.PARTS_DEFAULT);
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

    /** 单条恢复也走过程页：进度、速度、日志和批量一样，失败原因能在日志里翻 */
    private void jobRestore(final AppData.Backup b, final int targetUser, final int parts) {
        android.content.Intent it = new android.content.Intent(this, BatchRunActivity.class);
        it.putExtra(BatchRunActivity.EXTRA_MODE, BatchRunActivity.MODE_RESTORE);
        it.putExtra(BatchRunActivity.EXTRA_PATHS, new String[]{b.path});
        it.putExtra(BatchRunActivity.EXTRA_TARGET_USER, targetUser);
        it.putExtra(BatchRunActivity.EXTRA_PARTS, parts);
        startActivity(it);
    }

    /** 「日志」= 回到批量任务那一页：还在跑就直接看进度，跑完了看结果，进程重启过就看上次落盘的 */
    private void showLog() {
        startActivity(new android.content.Intent(this, BatchRunActivity.class));
    }

    // ── 备份目录 ─────────────────────────────────────────────
    private void renderDir() {
        if (dirValue != null) dirValue.setText(AppData.root());
    }

    /** 系统文件选择器：能选已有目录，也能当场新建一个 */
    private void pickBackupDir() {
        if (!rootOk) {
            toast(getString(R.string.backup_need_root));
            return;
        }
        android.content.Intent it = new android.content.Intent(
                android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
        it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(it, REQ_PICK_DIR);
        } catch (Throwable t) {
            toast(getString(R.string.backup_fail, "NO_PICKER"));
        }
    }

    /**
     * SAF 给的 tree uri 转成文件系统路径：我们的备份/恢复都走 root 的 shell，
     * 它只认路径。`primary:Download/x` → /storage/emulated/0/Download/x；
     * `1234-ABCD:x` → /storage/1234-ABCD/x。
     */
    private String pathOfTree(android.net.Uri uri) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(uri);
            int colon = docId.indexOf(':');
            if (colon <= 0) return null;
            String vol = docId.substring(0, colon);
            String rel = docId.substring(colon + 1);
            String base;
            if ("primary".equalsIgnoreCase(vol)) {
                base = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            } else if ("home".equalsIgnoreCase(vol)) {
                base = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            } else {
                base = "/storage/" + vol;
            }
            return rel.length() == 0 ? base : base + "/" + rel;
        } catch (Throwable t) {
            return null;
        }
    }

    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_DIR || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        String path = pathOfTree(uri);
        if (path == null || path.length() == 0) {
            toast(getString(R.string.backup_dir_bad));
            return;
        }
        // 记住授权，免得下次系统再问一次
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        useDir(path);
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
