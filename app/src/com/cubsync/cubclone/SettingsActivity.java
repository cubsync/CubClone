package com.cubsync.cubclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * 设置页：外观（主题 / 语言）、提权状态、分身空间管理。
 * 颜色全部由代码控制，跟主界面同一套规则（不依赖系统 night 资源）。
 */
public class SettingsActivity extends Activity {

    private static final int THEME_AUTO = 0, THEME_DARK = 1, THEME_LIGHT = 2, THEME_BLACK = 3;
    private static final int REQ_SHIZUKU = 1002;
    private static final String WB =
            "CLASSPATH=/data/local/tmp/cubclone.jar app_process /system/bin CubClone";

    private static final Pattern SPACE_ROW =
            Pattern.compile("CLONE\\s+UserInfo\\{(\\d+):([^:]*):");

    private boolean dark;
    private TextView statusBar;
    private boolean spacesLoaded;
    private int fgColor, secColor;

    private TextView title, secLook, secPerm, secClone, secAbout;
    private TextView themeLabel, themeValue, langLabel, langValue;
    private TextView gridColsLabel, gridColsValue, gridRowsLabel, gridRowsValue, gridPrimaryLabel;
    private TextView recentRowsLabel, recentRowsValue, recentColsLabel, recentColsValue;
    private Switch swGridPrimary, swRecent;
    private TextView permRoot, permShizuku, allLabel, allHint, iconLabel, iconHint;
    private TextView spaceTitle, spaceLimit, aboutVer, aboutNote;
    private TextView btnRecheck, btnShizuku, btnNewSpace;
    private LinearLayout spaceList;
    private View divider, lineLook;
    private Switch swAll, swIcon, swAmoled, swKeepSys, swManageForeign;
    /** 程序里刷开关状态时别再触发监听，否则会把用户的选择写没 */
    private boolean updatingAmoled;
    private TextView amoledLabel;
    private android.widget.ImageButton btnBack;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Locales.wrap(base));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从主界面建完/删完空间回来要重读：原来只在 onCreate 拉一次，
        // Activity 还在栈里就一直显示旧数据（川：创建了新空间设置里不刷新）
        if (Shell.ready()) loadSpaces();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);

        title = findViewById(R.id.title);
        secLook = findViewById(R.id.sec_look);
        secPerm = findViewById(R.id.sec_perm);
        secClone = findViewById(R.id.sec_clone);
        secAbout = findViewById(R.id.sec_about);
        themeLabel = findViewById(R.id.theme_label);
        themeValue = findViewById(R.id.theme_value);
        langLabel = findViewById(R.id.lang_label);
        langValue = findViewById(R.id.lang_value);
        permRoot = findViewById(R.id.perm_root);
        permShizuku = findViewById(R.id.perm_shizuku);
        allLabel = findViewById(R.id.all_label);
        allHint = findViewById(R.id.all_hint);
        iconLabel = findViewById(R.id.icon_label);
        iconHint = findViewById(R.id.icon_hint);
        spaceTitle = findViewById(R.id.space_title);
        spaceLimit = findViewById(R.id.space_limit);
        aboutVer = findViewById(R.id.about_ver);
        aboutNote = findViewById(R.id.about_note);
        btnRecheck = findViewById(R.id.btn_recheck);
        btnShizuku = findViewById(R.id.btn_shizuku);
        btnNewSpace = findViewById(R.id.btn_new_space);
        spaceList = findViewById(R.id.space_list);
        divider = findViewById(R.id.divider);
        lineLook = findViewById(R.id.line_look);
        swAll = findViewById(R.id.sw_all);
        swIcon = findViewById(R.id.sw_icon);
        swKeepSys = findViewById(R.id.sw_keep_sys);
        swManageForeign = findViewById(R.id.sw_manage_foreign);
        swAmoled = findViewById(R.id.sw_amoled);
        amoledLabel = findViewById(R.id.amoled_label);
        btnBack = findViewById(R.id.btn_back);
        statusBar = findViewById(R.id.status);
        gridColsLabel = findViewById(R.id.grid_cols_label);
        gridColsValue = findViewById(R.id.grid_cols_value);
        gridRowsLabel = findViewById(R.id.grid_rows_label);
        gridRowsValue = findViewById(R.id.grid_rows_value);
        gridPrimaryLabel = findViewById(R.id.grid_primary_label);
        swGridPrimary = findViewById(R.id.sw_grid_primary);
        recentRowsLabel = findViewById(R.id.recent_rows_label);
        recentRowsValue = findViewById(R.id.recent_rows_value);
        recentColsLabel = findViewById(R.id.recent_cols_label);
        recentColsValue = findViewById(R.id.recent_cols_value);
        swRecent = findViewById(R.id.sw_recent);
        swRecent.setChecked(sp().getBoolean("recent_show", true));
        swRecent.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("recent_show", checked).apply();
            }
        });
        findViewById(R.id.row_grid_cols).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickGridCols(); }
        });
        findViewById(R.id.row_grid_rows).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickGridRows(); }
        });
        findViewById(R.id.row_recent_rows).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickRecentRows(); }
        });
        findViewById(R.id.row_recent_cols).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickRecentCols(); }
        });
        swGridPrimary.setChecked(sp().getBoolean("grid_show_primary", false));
        swGridPrimary.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("grid_show_primary", checked).apply();
            }
        });

        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.row_theme).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickTheme(); }
        });
        findViewById(R.id.row_lang).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickLanguage(); }
        });
        btnRecheck.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { checkPerm(); }
        });
        btnShizuku.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askShizuku(); }
        });
        btnNewSpace.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askNewSpace(); }
        });

        swAll.setChecked(sp().getBoolean("show_all", false));
        swAll.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("show_all", checked).apply();
            }
        });

        swAmoled.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                if (updatingAmoled) return;
                sp().edit().putBoolean("amoled", checked).apply();
                applyChrome();
            }
        });

        swIcon.setChecked(sp().getBoolean("auto_icon", false));
        swIcon.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("auto_icon", checked).apply();
            }
        });

        swKeepSys.setChecked(sp().getBoolean("keep_sys", false));
        swKeepSys.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("keep_sys", checked).apply();
                MainActivity.dirty = true;
            }
        });

        swManageForeign.setChecked(sp().getBoolean("manage_foreign", false));
        swManageForeign.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("manage_foreign", checked).apply();
                MainActivity.dirty = true;
            }
        });

        Shizuku.addRequestPermissionResultListener(onShizukuResult);

        aboutVer.setText(getString(R.string.app_name) + " " + versionName());
        aboutNote.setText(R.string.about_note);
        applyChrome();
        checkPerm();       // 探完权限再读空间，否则直接进设置页会显示"需要授权"
    }

    @Override
    protected void onPause() {
        super.onPause();
        MainActivity.dirty = true;   // 设置里可能改了显示策略、建删了空间
        MainActivity.metaDirty = true;
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(onShizukuResult);
        super.onDestroy();
    }

    // ---------------- 颜色 ----------------

    private android.content.SharedPreferences sp() {
        return getSharedPreferences("clone", MODE_PRIVATE);
    }

    /** 纯黑（AMOLED）：只在深色模式下生效 */
    private boolean isBlack() {
        return isNight() && sp().getBoolean("amoled", false);
    }

    private boolean isNight() {
        int t = sp().getInt("theme", THEME_AUTO);
        if (t == THEME_DARK) return true;
        if (t == THEME_LIGHT) return false;
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyChrome() {
        dark = isNight();
        boolean black = isBlack();
        int bg = black ? 0xFF000000 : (dark ? 0xFF121317 : 0xFFFDFDFE);
        fgColor = dark ? 0xFFEDEDED : 0xFF1B1B1F;
        secColor = black ? 0xFF8A8F96 : (dark ? 0xFF9AA0A6 : 0xFF5F6368);
        int card = black ? 0xFF0C0C0C : (dark ? 0xFF1E1F22 : 0xFFF3F3F6);
        int line = black ? 0xFF1C1C1C : (dark ? 0xFF2C2D31 : 0xFFE3E3E8);

        findViewById(R.id.root).setBackgroundColor(bg);
        findViewById(R.id.header).setBackgroundColor(bg);
        title.setTextColor(fgColor);
        btnBack.setColorFilter(fgColor);

        TextView[] secs = {secLook, secPerm, secClone, secAbout,
                allHint, spaceLimit, aboutNote, themeValue, langValue, iconHint,
                gridColsValue, gridRowsValue, recentRowsValue, recentColsValue};
        for (TextView t : secs) if (t != null) t.setTextColor(secColor);
        TextView[] mains = {themeLabel, langLabel, permRoot, permShizuku,
                allLabel, spaceTitle, aboutVer, iconLabel, amoledLabel,
                gridColsLabel, gridRowsLabel, gridPrimaryLabel,
                recentRowsLabel, recentColsLabel};
        for (TextView t : mains) if (t != null) t.setTextColor(fgColor);

        divider.setBackgroundColor(line);
        lineLook.setBackgroundColor(line);
        for (int id : new int[]{R.id.card_look, R.id.card_perm, R.id.card_clone, R.id.card_about}) {
            View c = findViewById(id);
            if (c.getBackground() != null) c.getBackground().setTint(card);
        }
        renderButtons();
        renderValues();
        try {
            getWindow().setStatusBarColor(bg);
        } catch (Throwable ignored) {
        }
    }

    private void renderButtons() {
        int bg = isBlack() ? 0xFF16283D : (dark ? 0xFF1F3A5F : 0xFFD3E3FD);
        int fg = dark ? 0xFFA8C7FA : 0xFF0B57D0;
        for (TextView t : new TextView[]{btnRecheck, btnShizuku, btnNewSpace}) {
            if (t.getBackground() != null) t.getBackground().setTint(bg);
            t.setTextColor(fg);
        }
    }

    private void renderValues() {
        int t = sp().getInt("theme", THEME_AUTO);
        themeValue.setText(t == THEME_DARK ? R.string.theme_dark
                : t == THEME_LIGHT ? R.string.theme_light : R.string.theme_auto);
        // 纯黑只在深色下可用
        boolean canBlack = isNight();
        swAmoled.setEnabled(canBlack);
        swAmoled.setAlpha(canBlack ? 1f : 0.45f);
        amoledLabel.setAlpha(canBlack ? 1f : 0.45f);
        // 只改显示，不回写偏好：切到浅色时开关变灰并显示为关，
        // 切回深色还能拿回用户原来的选择（之前就是这里把 amoled=false 写死了）
        updatingAmoled = true;
        swAmoled.setChecked(canBlack && sp().getBoolean("amoled", false));
        updatingAmoled = false;
        String tag = currentLanguageTag();
        if (tag == null) langValue.setText(R.string.lang_auto);
        else if (tag.startsWith("en")) langValue.setText(R.string.lang_en);
        else langValue.setText(R.string.lang_zh);
        gridColsValue.setText(String.valueOf(sp().getInt("grid_cols", 5)));
        gridRowsValue.setText(String.valueOf(sp().getInt("grid_rows", 7)));
        recentRowsValue.setText(String.valueOf(sp().getInt("recent_rows", 1)));
        int rc = sp().getInt("recent_cols", 0);
        recentColsValue.setText(rc <= 0 ? getString(R.string.recent_cols_follow)
                : String.valueOf(rc));
    }

    // ---------------- 主题 ----------------

    private void pickTheme() {
        // 显示顺序与 Shizuku 一致：跟随系统 / 浅色 / 深色 / 黑色
        final int[] values = {THEME_AUTO, THEME_LIGHT, THEME_DARK};
        final String[] names = {
                getString(R.string.theme_auto),
                getString(R.string.theme_light),
                getString(R.string.theme_dark)
        };
        int cur = sp().getInt("theme", THEME_AUTO);
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) sel = i;
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_theme)
                .setSingleChoiceItems(names, sel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                sp().edit().putInt("theme", values[which]).apply();
                                d.dismiss();
                                applyChrome();
                                loadSpaces();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------- 语言 ----------------

    /** null = 跟随系统 */
    private String currentLanguageTag() {
        return Locales.tag(this);
    }

    private void pickLanguage() {
        final String[] tags = {null, "zh-CN", "en"};
        String[] names = {
                getString(R.string.lang_auto),
                getString(R.string.lang_zh),
                getString(R.string.lang_en)
        };
        String cur = currentLanguageTag();
        int sel = cur == null ? 0 : (cur.startsWith("en") ? 2 : 1);
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_language)
                .setSingleChoiceItems(names, sel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        applyLocale(tags[which]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyLocale(String tag) {
        if (tag == null) sp().edit().remove("lang").apply();
        else sp().edit().putString("lang", tag).apply();
        recreate();
    }

    // ---------------- 网格规格 ----------------

    private void pickGridCols() {
        final int[] values = {3, 4, 5, 6, 7, 8};
        int cur = sp().getInt("grid_cols", 5);
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) sel = i;
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) names[i] = String.valueOf(values[i]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_grid_cols)
                .setSingleChoiceItems(names, sel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        sp().edit().putInt("grid_cols", values[which]).apply();
                        d.dismiss();
                        renderValues();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickGridRows() {
        // 行数放宽到 16：平板/大屏一屏能摆更多行
        final int[] values = {4, 5, 6, 7, 8, 9, 10, 12, 14, 16};
        int cur = sp().getInt("grid_rows", 7);
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) sel = i;
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) names[i] = String.valueOf(values[i]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_grid_rows)
                .setSingleChoiceItems(names, sel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        sp().edit().putInt("grid_rows", values[which]).apply();
                        d.dismiss();
                        renderValues();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 「最近打开」最多几行：1~4，默认 1 */
    private void pickRecentRows() {
        final int[] values = {1, 2, 3, 4};
        int cur = sp().getInt("recent_rows", 1);
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) sel = i;
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) names[i] = String.valueOf(values[i]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_recent_rows)
                .setSingleChoiceItems(names, sel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        sp().edit().putInt("recent_rows", values[which]).apply();
                        d.dismiss();
                        renderValues();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 「最近打开」每行几个：0 = 跟随网格列数 */
    private void pickRecentCols() {
        final int[] values = {0, 3, 4, 5, 6, 7, 8};
        final String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = i == 0 ? getString(R.string.recent_cols_follow)
                    : String.valueOf(values[i]);
        }
        int cur = sp().getInt("recent_cols", 0);
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) sel = i;
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_recent_cols)
                .setSingleChoiceItems(names, sel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        sp().edit().putInt("recent_cols", values[which]).apply();
                        d.dismiss();
                        renderValues();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------- 权限 ----------------

    private final Shizuku.OnRequestPermissionResultListener onShizukuResult =
            new Shizuku.OnRequestPermissionResultListener() {
                public void onRequestPermissionResult(int code, int grantResult) {
                    if (code != REQ_SHIZUKU) return;
                    Shell.shizukuOk = grantResult == PackageManager.PERMISSION_GRANTED;
                    runOnUiThread(new Runnable() {
                        public void run() { showPermState(); }
                    });
                }
            };

    private void checkPerm() {
        permRoot.setText(R.string.perm_checking);
        permShizuku.setText(R.string.perm_checking);
        new Thread(new Runnable() {
            public void run() {
                Shell.checkRoot();
                try {
                    if (Shizuku.pingBinder()
                            && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        Shell.shizukuOk = true;
                    }
                } catch (Throwable ignored) {
                }
                runOnUiThread(new Runnable() {
                    public void run() { showPermState(); }
                });
            }
        }).start();
    }

    private void showPermState() {
        permRoot.setText(getString(R.string.perm_root,
                getString(Shell.rootOk ? R.string.perm_granted : R.string.perm_unavailable)));
        boolean alive = false;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable ignored) {
        }
        String sz = !alive ? getString(R.string.perm_not_running)
                : getString(Shell.shizukuOk ? R.string.perm_granted : R.string.perm_not_granted);
        permShizuku.setText(getString(R.string.perm_shizuku, sz));
        if (!spacesLoaded && Shell.ready()) {
            spacesLoaded = true;
            loadSpaces();
        }
    }

    private void askShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast(getString(R.string.perm_not_running));
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shell.shizukuOk = true;
                showPermState();
                toast(getString(R.string.perm_already));
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast(getString(R.string.perm_denied_before));
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            toast(String.valueOf(t));
        }
    }

    // ---------------- 分身空间 ----------------

    /**
     * 一次 shell 把所有东西拿全：分身列表 + 每个空间的三方应用数 + 类型上限。
     * 原写法是一个空间起一次 pm list、每个包再查一次 getApplicationInfo、两次 dumpsys user。
     */
    /** 空间列表命令：MainActivity 的 SCAN（带 $OURS 归属）+ 该用户类型的上限配置 */
    private static String batch(Context c) {
        return MainActivity.listBatch(c)
                + "dumpsys user | sed -n '/android.os.usertype.profile.CLONE:/,/^$/p'";
    }

    private int perParentCached = Integer.MIN_VALUE;
    private int maxAllCached = Integer.MIN_VALUE;

    /** 同一时刻只允许一条 shell 在跑：onCreate/onResume 并发两条时，其中一条会拿到空结果 */
    private boolean loading;
    private boolean pendingReload;

    private void loadSpaces() {
        if (loading) { pendingReload = true; return; }
        loading = true;
        spaceList.removeAllViews();
        spaceLimit.setText(R.string.loading);
        if (!Shell.ready()) {
            spaceLimit.setText(R.string.need_perm);
            loading = false;
            return;
        }

        new Thread(new Runnable() {
            public void run() {
                // 归属标记（WB_ 前缀 / 空间内 wb_clone）必须先读：listBatch 要用它分 SPACE/FSPACE
                MainActivity.ensureMarks();
                String out = Shell.exec(batch(SettingsActivity.this));
                android.util.Log.i("CubClone", "BATCH len=" + out.length() + " head="
                        + (out.length() > 400 ? out.substring(0, 400) : out));
                final List<Integer> uids = new ArrayList<Integer>();
                // 计数一律用「包名集合」而不是累加：同一个包会从 PKG/OFF/EXT 好几行冒出来，
                // 累加就翻倍（一加 999 的 4 变 8 就是这么来的）。按名字去重，跟空间归属无关。
                final List<java.util.Set<String>> counts = new ArrayList<java.util.Set<String>>();
                final List<Boolean> foreign = new ArrayList<Boolean>();
                final List<java.util.Set<String>> live = new ArrayList<java.util.Set<String>>();
                int perParent = -1, maxAll = -1;
                for (String line : out.split("\\n")) {
                    String ln = line.trim();
                    if (ln.isEmpty()) continue;
                    if (ln.startsWith("SPACE ") || ln.startsWith("FSPACE ")) {
                        try {
                            uids.add(Integer.parseInt(ln.substring(6).trim()));
                            counts.add(new java.util.HashSet<String>());
                            live.add(new java.util.HashSet<String>());
                            foreign.add(Boolean.valueOf(ln.startsWith("FSPACE ")));
                        } catch (Throwable ignored) {
                        }
                    } else if (ln.startsWith("PKG ") || ln.startsWith("OFF ")
                            || ln.startsWith("EXT ")) {
                        String[] pp = ln.split("\\s+");
                        if (pp.length < 3) continue;
                        if (pp[2].equals(getPackageName())) continue;
                        int idx = uids.indexOf(Integer.valueOf(pp[1]));
                        if (idx < 0) continue;
                        // 空间里真在的包（登记表要和它求交：残留登记不能算成分身）
                        live.get(idx).add(pp[2]);
                        // 只数三方：新建空间时系统会灌进 180 多个包，全算就是「180 个应用」那种离谱数字
                        if (!MainActivity.THIRD.contains(pp[2])) continue;
                        counts.get(idx).add(pp[2]);
                    } else if (ln.startsWith("mMaxAllowedPerParent:")) {
                        perParent = num(ln);
                    } else if (ln.startsWith("mMaxAllowed:")) {
                        maxAll = num(ln);
                    }
                }
                perParentCached = perParent;
                maxAllCached = maxAll;
                final int fp = perParent, fm = maxAll;
                runOnUiThread(new Runnable() {
                    public void run() {
                        renderSpaces(uids, counts, foreign, live, fp, fm);
                        loading = false;
                        if (pendingReload) {
                            pendingReload = false;
                            loadSpaces();
                        }
                    }
                });
            }
        }).start();
    }

    private static int num(String line) {
        try {
            return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 类型上限：优先用批量拿回来的，没拿到再单独查一次 */
    private int readTypeInt(String key) {
        if ("mMaxAllowedPerParent".equals(key) && perParentCached != Integer.MIN_VALUE) {
            return perParentCached;
        }
        if ("mMaxAllowed".equals(key) && maxAllCached != Integer.MIN_VALUE) return maxAllCached;
        try {
            String out = Shell.exec("dumpsys user");
            int i = out.indexOf("android.os.usertype.profile.CLONE:");
            if (i < 0) return -1;
            String sub = out.substring(i, Math.min(out.length(), i + 600));
            for (String line : sub.split("\n")) {
                String ln = line.trim();
                if (ln.startsWith(key + ":")) return num(ln);
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private void renderSpaces(List<Integer> uids, List<java.util.Set<String>> counts,
                              List<Boolean> foreign, List<java.util.Set<String>> live,
                              int perParent, int maxAll) {
        spaceList.removeAllViews();
        StringBuilder sb = new StringBuilder();
        int ours = 0;
        for (int i = 0; i < foreign.size(); i++) if (!foreign.get(i).booleanValue()) ours++;
        // 「已用」要算上系统只读空间（一加 999）：主界面能看到它的分身，
        // 这里写「已用 0 个」就是自相矛盾；上限判断仍只看我们自己建的
        sb.append(getString(R.string.space_used, uids.size()));
        if (perParent > 0) sb.append(" / ").append(perParent);
        if (perParent > 0 && ours >= perParent) {
            sb.append("\n").append(getString(R.string.space_limit_one));
        }
        // 「N 个空间已删除但没清干净」这条不再显示：<removing> 是系统自己的收尾过程，
        // 我们既不报也不用用户管，列表里已经把它过滤掉了。
        spaceLimit.setText(sb.toString());

        if (uids.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.space_empty);
            tv.setTextSize(13);
            tv.setTextColor(secColor);
            tv.setPadding(0, dp(6), 0, 0);
            spaceList.addView(tv);
            return;
        }

        for (int i = 0; i < uids.size(); i++) {
            final int uid = uids.get(i);
            int n = counts.get(i).size();

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView tv = new TextView(this);
            // 有登记表就用它（精确＝我们给这个空间装了几个分身）；没有（老空间）退回三方包数。
            // ⚠登记表要和空间里真在的包求交：删分身没清登记的残留会让数字虚高。
            java.util.Set<String> kept = MainActivity.keptPkgs(this, uid);
            if (kept != null && !kept.isEmpty()) {
                int c = 0;
                java.util.Set<String> lv = live.get(i);
                for (String p : kept) if (lv.contains(p)) c++;
                n = c;
            }
            tv.setText(getString(R.string.space_row2,
                    MainActivity.nameOf(this, uid), uid, n));
            tv.setTextSize(14);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            tv.setMarqueeRepeatLimit(-1);
            tv.setHorizontallyScrolling(true);
            tv.setFocusable(false);    // 拿到焦点会走 focus 分支、跑马灯反而不走 selected 那条
            tv.setSelected(true);      // 跑马灯只对 selected 的 TextView 生效
            tv.setTextColor(fgColor);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);

            if (foreign.get(i).booleanValue()) {
                // 一加自带 MultiApp 之类的空间：只看，不给改名/删除
                TextView ro = new TextView(this);
                ro.setText(R.string.space_foreign);
                ro.setTextSize(13);
                ro.setPadding(dp(12), dp(6), dp(4), dp(6));
                ro.setTextColor(secColor);
                row.addView(ro);
                spaceList.addView(row);
                continue;
            }

            TextView rn = new TextView(this);
            rn.setText(R.string.rename_space);
            rn.setTextSize(14);
            rn.setPadding(dp(12), dp(6), dp(4), dp(6));
            rn.setTextColor(dark ? 0xFFA8C7FA : 0xFF0B57D0);
            rn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { askRenameSpace(uid); }
            });
            row.addView(rn);

            TextView del = new TextView(this);
            del.setText(R.string.delete_space);
            del.setTextSize(14);
            del.setPadding(dp(12), dp(6), dp(4), dp(6));
            del.setTextColor(dark ? 0xFFF28B82 : 0xFFC5221F);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmRemoveSpace(uid); }
            });
            row.addView(del);
            spaceList.addView(row);
        }
    }

    private void askNewSpace() {
        if (!Shell.ready()) {
            toast(getString(R.string.need_perm));
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        final EditText nameEt = new EditText(this);
        nameEt.setHint(R.string.new_space_name);
        nameEt.setSingleLine(true);
        box.addView(nameEt);

        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_new_space)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        createSpace(nameEt.getText().toString().trim());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createSpace(final String name) {
        toast(getString(R.string.creating));
        new Thread(new Runnable() {
            public void run() {
                String out = Shell.exec(WB + " create '" + MainActivity.wbName(name) + "'");
                Matcher m = Pattern.compile("\\{(\\d+):").matcher(out);
                final int uid = m.find() ? Integer.parseInt(m.group(1)) : -1;
                if (uid > 0) {
                    MainActivity.markOurs(uid);
                    // 不主动 start：档案由系统在建号时就拉起了，这里再 start 只会让桌面
                    // 把「刚被灌进来还没删的那批应用」bind 一遍，用户就看见一堆图标闪一下
                    MainActivity.setLabel(SettingsActivity.this, uid,
                            name.isEmpty() ? "" : name);
                    if (!sp().getBoolean("keep_sys", false)) {
                        MainActivity.pruneSpace(SettingsActivity.this, uid, true);
                    }
                }

                final String msg;
                if (uid <= 0) {
                    msg = out.contains("Cannot add more") || out.contains("returned null")
                            ? getString(R.string.space_limit_reached,
                            String.valueOf(readTypeInt("mMaxAllowedPerParent")))
                            : getString(R.string.create_failed, out.trim());
                } else {
                    msg = getString(R.string.space_created, uid);
                    MainActivity.dirty = true;
                    MainActivity.metaDirty = true;
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(msg);
                        loadSpaces();
                    }
                });
            }
        }).start();
    }

    /** 给分身空间改名（AOSP 允许随时改，名字就是分身里设置里看到的名字） */
    private void askRenameSpace(final int uid) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint(R.string.new_space_name);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_space)
                .setView(et)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String name = et.getText().toString().trim();
                        if (name.isEmpty()) return;
                        MainActivity.setLabel(SettingsActivity.this, uid, name);
                        MainActivity.dirty = true;
                        loadSpaces();
                        // 系统名（LSP 管理器里看到的那个名字）：setUserName 要 MANAGE_USERS，
                        // root 下能改、Shizuku（shell）改不了。等结果，如实反馈，别让用户以为改了。
                        new Thread(new Runnable() {
                            public void run() {
                                final String r = Shell.exec(WB + " rename " + uid + " '"
                                        + name.replace("'", "") + "'");
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        boolean synced = r.contains("renamed");
                                        toast(getString(synced
                                                ? R.string.space_renamed_synced
                                                : R.string.space_renamed_label_only));
                                        loadSpaces();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemoveSpace(final int uid) {
        if (!Launcher.safe(uid)) {
            toast("uid " + uid + " 不是分身，拒绝操作");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_space)
                .setMessage(getString(R.string.delete_space_msg, uid))
                .setPositiveButton(R.string.delete_space, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            public void run() {
                                final String r = Shell.exec(WB + " remove " + uid);
                                final boolean gone = !MainActivity.listSpaceUids()
                                        .contains(Integer.valueOf(uid));
                                if (gone) {
                                    sp().edit().remove("space_pkgs_" + uid).apply();
                                    MainActivity.unmarkOurs(uid);
                                }
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        MainActivity.dirty = true;
                                        MainActivity.unpinShortcut(SettingsActivity.this, uid, null);
                                        // 这类 ROM 上 remove-user 会把档案停在 <removing> STOPPING，
                                        // 只有重启才彻底消失 —— 别骗用户说已经删干净了
                                        toast(r.contains("REFUSED") ? r.trim()
                                                : getString(gone ? R.string.space_deleted
                                                        : R.string.space_deleted_reboot, uid));
                                        loadSpaces();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------- 工具 ----------------

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 页面上已经有状态行，不再弹 toast */
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idle = new Runnable() {
        public void run() {
            if (statusBar != null) statusBar.setText("");
        }
    };

    private void toast(String s) {
        if (statusBar != null) {
            statusBar.setText(s);
            ui.removeCallbacks(idle);
            ui.postDelayed(idle, 4000);
            statusBar.setTextColor(secColor);
        }
    }

    /** 设置页显示的版本直接读 APK 自己的 versionName，别再手工写死 */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }
}
