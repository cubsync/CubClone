package com.cubsync.cubclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String CLI =
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
    private TextView btnRecheck, btnShizuku, btnNewSpace, btnRefreshSpace, btnManageSpaces;
    private LinearLayout spaceList;
    private TextView hiddenCount;
    private TextView imePickCount;
    /** 需要 root 的两个入口：备份列表按 Root 开关决定出不出现 */
    private View backupEntry, rootEntry;
    private TextView rootEntryState;
    private TextView spaceWarn;
    /** 设备上已装模块的 versionCode；-1 = 读不到（没装，或 2.3 及更早） */
    private int moduleCode = -1;
    private String moduleVer;
    /** 分身存储是否已知不可用（只测一次，结果缓存） */
    private Integer storageState;
    private View divider, dividerKeep, lineLook;
    private Switch swAll, swIcon, swAmoled, swKeepSys, swManageForeign, swSpaceBadge, swShowDisabled;
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
        applyRootGates();
        if (Shell.ready()) {
            loadSpaces();
            return;
        }
        // 进程被回收后从「最近任务」直接回到本页：rootOk/shizukuOk 都还是 false，
        // 所有 shell 调用返回 ERR_NO_PERM —— 空间列表空白、点「新建空间」连输入框都不弹。
        // 补探一次（su 要几百毫秒，不能放主线程）。BatchBackupActivity 同一个坑。
        new Thread(new Runnable() {
            public void run() {
                Shell.probe();
                runOnUiThread(new Runnable() {
                    public void run() {
                        applyRootGates();
                        loadSpaces();
                    }
                });
            }
        }).start();
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
        spaceWarn = findViewById(R.id.space_storage_warn);
        spaceWarn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { makeFixModule(); }
        });
        renderStorageWarn();
        aboutVer = findViewById(R.id.about_ver);
        aboutNote = findViewById(R.id.about_note);
        btnRecheck = findViewById(R.id.btn_recheck);
        btnShizuku = findViewById(R.id.btn_shizuku);
        btnNewSpace = findViewById(R.id.btn_new_space);
        btnRefreshSpace = findViewById(R.id.btn_refresh_space);
        btnManageSpaces = findViewById(R.id.btn_manage_spaces);
        btnManageSpaces.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, SpacesActivity.class));
            }
        });
        spaceList = findViewById(R.id.space_list);
        hiddenCount = findViewById(R.id.hidden_entry_count);
        imePickCount = findViewById(R.id.ime_pick_count);
        findViewById(R.id.ime_pick_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showImePicker(); }
        });
        renderImePick();
        findViewById(R.id.hidden_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showHiddenManager(); }
        });
        backupEntry = findViewById(R.id.backup_entry);
        rootEntry = findViewById(R.id.root_entry);
        rootEntryState = findViewById(R.id.root_entry_state);
        backupEntry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!Root.on(SettingsActivity.this, Root.BACKUP)) {
                    toast(getString(R.string.root_need));
                    return;
                }
                startActivity(new android.content.Intent(SettingsActivity.this, BackupActivity.class));
            }
        });
        rootEntry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, RootActivity.class));
            }
        });
        renderHiddenCard();
        divider = findViewById(R.id.divider);
        dividerKeep = findViewById(R.id.divider_keep);
        lineLook = findViewById(R.id.line_look);
        swAll = findViewById(R.id.sw_all);
        swIcon = findViewById(R.id.sw_icon);
        swKeepSys = findViewById(R.id.sw_keep_sys);
        swManageForeign = findViewById(R.id.sw_manage_foreign);
        findViewById(R.id.keep_extra_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showDisableExtra(); }
        });
        findViewById(R.id.uninstall_extra_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showUninstallExtra(); }
        });
        findViewById(R.id.hidden_sys_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showHiddenSys(); }
        });
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
        btnRefreshSpace.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { loadSpaces(); }
        });

        swAll.setChecked(sp().getBoolean("show_all", false));
        swAll.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("show_all", checked).apply();
            }
        });

        swShowDisabled = findViewById(R.id.sw_show_disabled);
        swShowDisabled.setChecked(sp().getBoolean("show_disabled", false));
        swShowDisabled.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("show_disabled", checked).apply();
                CloneStore.dirty = true;   // 影响主界面列表，回来重刷
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

        swSpaceBadge = findViewById(R.id.sw_space_badge);
        swSpaceBadge.setChecked(sp().getBoolean("space_badge", true));
        swSpaceBadge.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("space_badge", checked).apply();
                CloneStore.dirty = true;   // 角标开关影响主界面渲染，回来重刷
            }
        });

        swKeepSys.setChecked(CloneStore.pruneSys(this));
        swKeepSys.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("prune_sys", checked).apply();
                CloneStore.dirty = true;
            }
        });

        swManageForeign.setChecked(sp().getBoolean("manage_foreign", false));
        swManageForeign.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                sp().edit().putBoolean("manage_foreign", checked).apply();
                CloneStore.dirty = true;
                loadSpaces();   // 开关影响「非自建空间是否出删除按钮」，立即刷新列表
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
        CloneStore.dirty = true;   // 设置里可能改了显示策略、建删了空间 → 主界面重扫空间状态
        // 主身份应用清单不因进出设置页而变，不置 metaDirty（置了每次返回都全量重载几百个图标）
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

    /** 主题判断只走 Theme（1=深、2=浅），本页不再自己判一次 */
    private boolean isBlack() {
        return Theme.isBlack(this);
    }

    private boolean isNight() {
        return Theme.isNight(this);
    }

    private void applyChrome() {
        dark = isNight();
        boolean black = isBlack();
        int bg = Theme.bg(this);
        fgColor = Theme.fg(this);
        secColor = Theme.sec(this);
        int card = Theme.card(this);
        int line = Theme.line(this);

        findViewById(R.id.root).setBackgroundColor(bg);
        findViewById(R.id.header).setBackgroundColor(bg);
        title.setTextColor(fgColor);
        btnBack.setColorFilter(fgColor);

        TextView[] secs = {secLook, secPerm, secClone, secAbout,
                allHint, spaceLimit, aboutNote, themeValue, langValue, iconHint,
                gridColsValue, gridRowsValue, recentRowsValue, recentColsValue};
        for (TextView t : secs) if (t != null) t.setTextColor(secColor);
        // 权限框：左边标签走正文色，右边的状态值由 showPermState 上色（绿/红），
        // 所以状态 TextView 不进这个数组，否则换主题时会被刷回普通文字色。
        TextView[] mains = {themeLabel, langLabel,
                (TextView) findViewById(R.id.perm_root_label),
                (TextView) findViewById(R.id.perm_shizuku_label),
                allLabel, spaceTitle, aboutVer, iconLabel, amoledLabel,
                gridColsLabel, gridRowsLabel, gridPrimaryLabel,
                recentRowsLabel, recentColsLabel};
        for (TextView t : mains) if (t != null) t.setTextColor(fgColor);
        // 开关旁边的文字（recent/keep_sys/manage_foreign/space_badge/show_disabled）也要跟主题色，否则默认灰色
        for (int id : new int[]{R.id.recent_show_label, R.id.sys_keep_label,
                R.id.manage_foreign_label,
                R.id.space_badge_label, R.id.show_disabled_label,
                R.id.keep_extra_label, R.id.uninstall_extra_label, R.id.hidden_sys_label,
                R.id.hidden_entry_label, R.id.backup_entry_label, R.id.root_entry_label}) {
            TextView t = findViewById(id);
            if (t != null) t.setTextColor(fgColor);
        }
        if (rootEntryState != null) rootEntryState.setTextColor(secColor);
        TextView mfh = findViewById(R.id.manage_foreign_hint);
        if (mfh != null) mfh.setTextColor(secColor);
        TextView sbh = findViewById(R.id.space_badge_hint);
        if (sbh != null) sbh.setTextColor(secColor);
        TextView sdh = findViewById(R.id.show_disabled_hint);
        if (sdh != null) sdh.setTextColor(secColor);
        TextView ksh = findViewById(R.id.keep_sys_hint);
        if (ksh != null) ksh.setTextColor(secColor);
        TextView keh = findViewById(R.id.keep_extra_hint);
        if (keh != null) keh.setTextColor(secColor);
        TextView ueh = findViewById(R.id.uninstall_extra_hint);
        if (ueh != null) ueh.setTextColor(secColor);
        TextView hsh = findViewById(R.id.hidden_sys_hint);
        if (hsh != null) hsh.setTextColor(secColor);
        TextView imh = findViewById(R.id.ime_hint);
        if (imh != null) imh.setTextColor(secColor);
        TextView ipl = findViewById(R.id.ime_pick_label);
        if (ipl != null) ipl.setTextColor(fgColor);
        renderImePick();
        renderStorageWarn();

        divider.setBackgroundColor(line);
        dividerKeep.setBackgroundColor(line);
        lineLook.setBackgroundColor(line);
        for (int id : new int[]{R.id.card_look, R.id.card_perm, R.id.card_clone, R.id.card_about}) {
            View c = findViewById(id);
            if (c.getBackground() != null) c.getBackground().setTint(card);
        }
        renderButtons();
        renderValues();
        showPermState();
        try {
            getWindow().setStatusBarColor(bg);
        } catch (Throwable ignored) {
        }
    }

    private void renderButtons() {
        int bg = Theme.chipBg(this);
        int fg = Theme.chipFg(this);
        for (TextView t : new TextView[]{btnRecheck, btnShizuku, btnNewSpace, btnRefreshSpace, btnManageSpaces}) {
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
        Theme.dialog(this)
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
        Theme.dialog(this)
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
        Theme.dialog(this)
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
        Theme.dialog(this)
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
        Theme.dialog(this)
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
        Theme.dialog(this)
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

    /**
     * Root 功能的入口门控：只用 Shizuku 时「数据备份」直接不出现；
     * 「Root 功能」这一行始终在，否则没 root 时连开关页都进不去。
     */
    private void applyRootGates() {
        if (backupEntry != null) {
            backupEntry.setVisibility(Root.on(this, Root.BACKUP) ? View.VISIBLE : View.GONE);
        }
        if (rootEntryState != null) {
            rootEntryState.setText(Root.available()
                    ? R.string.root_short_ok : R.string.root_short_bad);
            rootEntryState.setTextColor(secColor);
        }
    }

    private void checkPerm() {
        permRoot.setText(R.string.perm_checking);
        permShizuku.setText(R.string.perm_checking);
        permRoot.setTextColor(secColor);
        permShizuku.setTextColor(secColor);
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
        // 左侧已经有「Root 权限」「Shizuku」标签，这里只写状态，别再拼一遍前缀。
        // 状态用颜色区分：可用的绿，不可用/未授权的红。
        final boolean rootOk = Shell.rootOk;
        permRoot.setText(rootOk ? R.string.perm_granted : R.string.perm_unavailable);
        permRoot.setTextColor(rootOk ? Theme.green(this) : Theme.red(this));
        boolean alive = false;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable ignored) {
        }
        final String sz = !alive ? getString(R.string.perm_not_running)
                : getString(Shell.shizukuOk ? R.string.perm_granted : R.string.perm_not_granted);
        permShizuku.setText(sz);
        permShizuku.setTextColor(Shell.shizukuOk ? Theme.green(this) : Theme.red(this));
        if (!spacesLoaded && Shell.ready()) {
            spacesLoaded = true;
            loadSpaces();
        }
        // 提权刚就绪时补算一次存储检测（首次进页面时可能还没拿到 Shizuku）
        if (Shell.ready() && storageState == null) renderStorageWarn();
        applyRootGates();
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
    private int perParentCached = Integer.MIN_VALUE;
    private int maxAllCached = Integer.MIN_VALUE;

    /** 同一时刻只允许一条 shell 在跑：onCreate/onResume 并发两条时，其中一条会拿到空结果 */
    private boolean loading;
    private boolean pendingReload;

    private void loadSpaces() {
        if (loading) { pendingReload = true; return; }
        loading = true;
        spaceLimit.setText(R.string.loading);
        if (!Shell.ready()) {
            spaceLimit.setText(R.string.need_perm);
            loading = false;
            return;
        }

        new Thread(new Runnable() {
            public void run() {
                // 扫描逻辑在 CloneStore.scanSpaces（和空间管理页共用一份）
                final CloneStore.SpaceScan sc = CloneStore.scanSpaces(SettingsActivity.this);
                perParentCached = sc.perParent;
                maxAllCached = sc.maxAll;
                runOnUiThread(new Runnable() {
                    public void run() {
                        renderSpaces(sc.uids, sc.counts, sc.foreign, sc.live,
                                sc.perParent, sc.maxAll);
                        renderHiddenCard();
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
        // 建号链上有两道闸，谁小谁说了算：按类型总数（mMaxAllowed）× 每父用户（mMaxAllowedPerParent）。
        // 只显示 perParent 会误导 —— 8A 实测 perParent 放到 100、mMaxAllowed 还是 3，界面写「已用 3 / 100」
        // 但第 4 个必然被拒，用户只会觉得「点了没反应」。
        final int cap = CloneStore.effectiveCap(perParent, maxAll);
        // 「已用」要算上系统只读空间（一加 999）：主界面能看到它的分身，
        // 这里写「已用 0 个」就是自相矛盾；上限判断仍只看我们自己建的
        // 有上限就一次说清「已用 N / M 个」；以前是「已用 N 个 … / M」，读起来别扭
        sb.append(cap > 0
                ? getString(R.string.space_used_of, uids.size(), cap)
                : getString(R.string.space_used, uids.size()));
        if (cap > 0 && ours >= cap) {
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

        // 设置页只预览前 3 个：空间多了以后这一块会顶掉整个页面，
        // 完整的浏览和批量操作在 SpacesActivity
        final int shown = Math.min(uids.size(), 3);
        for (int i = 0; i < shown; i++) {
            final int uid = uids.get(i);
            int n = counts.get(i).size();

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView tv = new TextView(this);
            // 计数＝空间里 enabled 的三方数，和主页显示一致，不再靠登记表求交。
            tv.setText(getString(R.string.space_row2,
                    CloneStore.nameOf(this, uid), uid, n));
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
                // 「管理非自建空间」开启后：非自建空间也给出删除入口（带确认）
                if (sp().getBoolean("manage_foreign", false)) {
                    TextView del = new TextView(this);
                    del.setText(R.string.delete_space);
                    del.setTextSize(14);
                    del.setPadding(dp(12), dp(6), dp(4), dp(6));
                    del.setTextColor(Theme.red(this));
                    del.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { confirmRemoveSpace(uid); }
                    });
                    row.addView(del);
                }
                spaceList.addView(row);
                continue;
            }

            TextView rn = new TextView(this);
            rn.setText(R.string.rename_space);
            rn.setTextSize(14);
            rn.setPadding(dp(12), dp(6), dp(4), dp(6));
            rn.setTextColor(Theme.accent(this));
            rn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { askRenameSpace(uid); }
            });
            row.addView(rn);

            // 输入法：老空间可能被早期 prune 清掉过输入法，或主空间换了输入法没跟着换。
            // 点一下就地重装＋重写设置，幂等，随便点。
            TextView ime = new TextView(this);
            ime.setText(R.string.ime_action);
            ime.setTextSize(14);
            ime.setPadding(dp(12), dp(6), dp(4), dp(6));
            ime.setTextColor(Theme.accent(this));
            ime.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { repairIme(uid); }
            });
            row.addView(ime);

            TextView del = new TextView(this);
            del.setText(R.string.delete_space);
            del.setTextSize(14);
            del.setPadding(dp(12), dp(6), dp(4), dp(6));
            del.setTextColor(Theme.red(this));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmRemoveSpace(uid); }
            });
            row.addView(del);
            spaceList.addView(row);
        }

        if (uids.size() > shown) {
            TextView all = new TextView(this);
            all.setText(getString(R.string.sp_view_all, uids.size()));
            all.setTextSize(13);
            all.setPadding(0, dp(10), 0, 0);
            all.setTextColor(Theme.accent(this));
            all.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startActivity(new android.content.Intent(SettingsActivity.this, SpacesActivity.class));
                }
            });
            spaceList.addView(all);
        }
    }

    /** 弹窗里统一的搜索框：固定高度 + 系统编辑框背景 + 底部间距，避免和下方内容文字重叠 */
    private EditText makeSearchBox(int hintRes) {
        EditText et = new EditText(this);
        et.setHint(hintRes);
        et.setSingleLine(true);
        et.setTextSize(15);
        et.setTextColor(fgColor);
        et.setHintTextColor(secColor);
        // 圆角搜索框（Material search bar）：有清晰边界，hint 不会和下方文字挤在一起
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setCornerRadius(dp(10));
        gd.setColor(Theme.row(this));
        et.setBackground(gd);
        et.setGravity(android.view.Gravity.CENTER_VERTICAL);
        et.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp.bottomMargin = dp(12);
        et.setLayoutParams(lp);
        return et;
    }

    /** 禁用清单弹窗：必需项锁定，可选项勾选禁用 */
    private void showDisableExtra() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        if (ris == null) ris = new ArrayList<ResolveInfo>();

        final Set<String> mustK = CloneStore.mustKeep(this);
        final Set<String> resolver = CloneStore.resolverPkgs(this);
        final Set<String> essential = new HashSet<String>();
        final List<String> sysPkgs = new ArrayList<String>();
        final Map<String, String> labels = new HashMap<String, String>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) continue;
                labels.put(pkg, String.valueOf(ai.loadLabel(pm)));
            } catch (Throwable ignored) { continue; }
            sysPkgs.add(pkg);
            if (CloneStore.isEssentialPkg(pkg) || mustK.contains(pkg) || resolver.contains(pkg)) {
                essential.add(pkg);
            }
        }
        Collections.sort(sysPkgs);
        final List<String> optional = new ArrayList<String>();
        for (String p : sysPkgs) if (!essential.contains(p)) optional.add(p);

        final Set<String> checked = CloneStore.extraDisable(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), 0);

        final EditText search = makeSearchBox(R.string.search_sys_app);
        root.addView(search);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        TextView essTitle = new TextView(this);
        essTitle.setText(getString(R.string.keep_extra_essential, essential.size()));
        essTitle.setTextSize(12);
        essTitle.setTextColor(secColor);
        essTitle.setPadding(0, dp(8), 0, dp(2));
        list.addView(essTitle);
        TextView essText = new TextView(this);
        StringBuilder sb = new StringBuilder();
        for (String p : essential) {
            if (sb.length() > 0) sb.append(" · ");
            String lb = labels.get(p);
            sb.append(lb == null ? p : lb);
        }
        essText.setText(sb.toString());
        essText.setTextSize(13);
        essText.setTextColor(secColor);
        essText.setAlpha(0.55f);
        essText.setPadding(dp(4), dp(2), dp(4), dp(2));
        list.addView(essText);

        TextView optTitle = new TextView(this);
        optTitle.setText(R.string.keep_extra_optional);
        optTitle.setTextSize(12);
        optTitle.setTextColor(secColor);
        optTitle.setPadding(0, dp(8), 0, dp(2));
        list.addView(optTitle);

        final LinearLayout optBox = new LinearLayout(this);
        optBox.setOrientation(LinearLayout.VERTICAL);
        final Map<String, CheckBox> optChecks = new HashMap<String, CheckBox>();
        final Set<String> inUninstall = CloneStore.extraUninstall(this);
        for (String p : optional) {
            if (inUninstall.contains(p)) continue;   // 卸载优先：已在卸载清单的项排到末尾置灰
            CheckBox cb = new CheckBox(this);
            String lb = labels.get(p);
            cb.setText(lb == null ? p : lb);
            cb.setTextSize(14);
            cb.setTextColor(fgColor);
            cb.setChecked(checked.contains(p));
            optChecks.put(p, cb);
            optBox.addView(cb);
        }
        for (String p : optional) {
            if (!inUninstall.contains(p)) continue;
            CheckBox cb = new CheckBox(this);
            String lb = labels.get(p);
            cb.setText((lb == null ? p : lb) + "（" + getString(R.string.in_uninstall_list) + "）");
            cb.setTextSize(14);
            cb.setTextColor(secColor);
            cb.setEnabled(false);
            cb.setChecked(false);
            optChecks.put(p, cb);
            optBox.addView(cb);
        }
        list.addView(optBox);
        sv.addView(list);
        root.addView(sv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(8), 0, dp(4));
        TextView all = new TextView(this);
        all.setText(R.string.select_all_optional);
        all.setTextSize(14);
        all.setTextColor(fgColor);
        all.setPadding(dp(4), dp(8), dp(16), dp(8));
        all.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (CheckBox cb : optChecks.values()) cb.setChecked(true);
            }
        });
        btns.addView(all);
        root.addView(btns);

        final AlertDialog dlg = Theme.dialog(this)
                .setTitle(R.string.keep_extra_title)
                .setView(root)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Set<String> saved = new HashSet<String>();
                        for (Map.Entry<String, CheckBox> e : optChecks.entrySet()) {
                            if (e.getValue().isChecked()) saved.add(e.getKey());
                        }
                        sp().edit().putStringSet("disable_extra", saved).apply();
                        CloneStore.dirty = true;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.show();

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(android.text.Editable e) {
                String kw = e.toString().trim().toLowerCase();
                for (Map.Entry<String, CheckBox> en : optChecks.entrySet()) {
                    String lb = labels.get(en.getKey());
                    boolean match = kw.isEmpty()
                            || (lb != null && lb.toLowerCase().contains(kw))
                            || en.getKey().toLowerCase().contains(kw);
                    en.getValue().setVisibility(match ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    /** 彻底移除清单：勾选的非必需系统应用，建空间后直接卸载（而非禁用） */
    private void showUninstallExtra() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        if (ris == null) ris = new ArrayList<ResolveInfo>();

        final Set<String> mustK = CloneStore.mustKeep(this);
        final Set<String> resolver = CloneStore.resolverPkgs(this);
        final Set<String> essential = new HashSet<String>();
        final List<String> sysPkgs = new ArrayList<String>();
        final Map<String, String> labels = new HashMap<String, String>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) continue;
                labels.put(pkg, String.valueOf(ai.loadLabel(pm)));
            } catch (Throwable ignored) { continue; }
            sysPkgs.add(pkg);
            if (CloneStore.isEssentialPkg(pkg) || mustK.contains(pkg) || resolver.contains(pkg)) {
                essential.add(pkg);
            }
        }
        Collections.sort(sysPkgs);
        final List<String> optional = new ArrayList<String>();
        for (String p : sysPkgs) if (!essential.contains(p)) optional.add(p);

        final Set<String> checked = CloneStore.extraUninstall(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), 0);

        final EditText search = makeSearchBox(R.string.search_sys_app);
        root.addView(search);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        TextView essTitle = new TextView(this);
        essTitle.setText(getString(R.string.keep_extra_essential, essential.size()));
        essTitle.setTextSize(12);
        essTitle.setTextColor(secColor);
        essTitle.setPadding(0, dp(8), 0, dp(2));
        list.addView(essTitle);
        TextView essText = new TextView(this);
        StringBuilder sb = new StringBuilder();
        for (String p : essential) {
            if (sb.length() > 0) sb.append(" · ");
            String lb = labels.get(p);
            sb.append(lb == null ? p : lb);
        }
        essText.setText(sb.toString());
        essText.setTextSize(13);
        essText.setTextColor(secColor);
        essText.setAlpha(0.55f);
        essText.setPadding(dp(4), dp(2), dp(4), dp(2));
        list.addView(essText);

        TextView optTitle = new TextView(this);
        optTitle.setText(R.string.uninstall_optional);
        optTitle.setTextSize(12);
        optTitle.setTextColor(secColor);
        optTitle.setPadding(0, dp(8), 0, dp(2));
        list.addView(optTitle);

        final LinearLayout optBox = new LinearLayout(this);
        optBox.setOrientation(LinearLayout.VERTICAL);
        final Map<String, CheckBox> optChecks = new HashMap<String, CheckBox>();
        for (String p : optional) {
            CheckBox cb = new CheckBox(this);
            String lb = labels.get(p);
            cb.setText(lb == null ? p : lb);
            cb.setTextSize(14);
            cb.setTextColor(fgColor);
            cb.setChecked(checked.contains(p));
            optChecks.put(p, cb);
            optBox.addView(cb);
        }
        list.addView(optBox);
        sv.addView(list);
        root.addView(sv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(8), 0, dp(4));
        TextView all = new TextView(this);
        all.setText(R.string.select_all_optional);
        all.setTextSize(14);
        all.setTextColor(fgColor);
        all.setPadding(dp(4), dp(8), dp(16), dp(8));
        all.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (CheckBox cb : optChecks.values()) cb.setChecked(true);
            }
        });
        btns.addView(all);
        root.addView(btns);

        final AlertDialog dlg = Theme.dialog(this)
                .setTitle(R.string.uninstall_extra_title)
                .setView(root)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Set<String> saved = new HashSet<String>();
                        for (Map.Entry<String, CheckBox> e : optChecks.entrySet()) {
                            if (e.getValue().isChecked()) saved.add(e.getKey());
                        }
                        sp().edit().putStringSet("uninstall_extra", saved).apply();
                        CloneStore.dirty = true;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.show();

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(android.text.Editable e) {
                String kw = e.toString().trim().toLowerCase();
                for (Map.Entry<String, CheckBox> en : optChecks.entrySet()) {
                    String lb = labels.get(en.getKey());
                    boolean match = kw.isEmpty()
                            || (lb != null && lb.toLowerCase().contains(kw))
                            || en.getKey().toLowerCase().contains(kw);
                    en.getValue().setVisibility(match ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    /** 隐藏名单（配置清单）：勾选要隐藏的主空间系统应用（保持启用、只不显示），取消勾选即恢复 */
    private void showHiddenSys() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        if (ris == null) ris = new ArrayList<ResolveInfo>();

        final List<String> sysPkgs = new ArrayList<String>();
        final Map<String, String> labels = new HashMap<String, String>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) continue;
                labels.put(pkg, String.valueOf(ai.loadLabel(pm)));
            } catch (Throwable ignored) { continue; }
            sysPkgs.add(pkg);
        }
        Collections.sort(sysPkgs);

        final Set<String> checked = CloneStore.hideList();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), 0);

        final EditText search = makeSearchBox(R.string.search_sys_app);
        root.addView(search);

        TextView tip = new TextView(this);
        tip.setText(R.string.hidden_sys_dialog_tip);
        tip.setTextSize(12);
        tip.setTextColor(secColor);
        tip.setPadding(0, 0, 0, dp(6));
        root.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final Map<String, CheckBox> checks = new HashMap<String, CheckBox>();
        final Set<String> inUninstall = CloneStore.extraUninstall(this);
        final Set<String> inDisable = CloneStore.extraDisable(this);
        for (String p : sysPkgs) {
            if (inUninstall.contains(p) || inDisable.contains(p)) continue;   // 已进更高优先级清单 → 末尾置灰
            CheckBox cb = new CheckBox(this);
            String lb = labels.get(p);
            cb.setText(lb == null ? p : lb);
            cb.setTextSize(14);
            cb.setTextColor(fgColor);
            cb.setChecked(checked.contains(p));
            checks.put(p, cb);
            list.addView(cb);
        }
        for (String p : sysPkgs) {
            boolean u = inUninstall.contains(p);
            boolean d = !u && inDisable.contains(p);
            if (!u && !d) continue;
            CheckBox cb = new CheckBox(this);
            String lb = labels.get(p);
            cb.setText((lb == null ? p : lb) + "（"
                    + getString(u ? R.string.in_uninstall_list : R.string.in_disable_list) + "）");
            cb.setTextSize(14);
            cb.setTextColor(secColor);
            cb.setEnabled(false);
            cb.setChecked(false);
            checks.put(p, cb);
            list.addView(cb);
        }
        sv.addView(list);
        root.addView(sv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(8), 0, dp(4));
        TextView all = new TextView(this);
        all.setText(R.string.select_all_optional);
        all.setTextSize(14);
        all.setTextColor(fgColor);
        all.setPadding(dp(4), dp(8), dp(16), dp(8));
        all.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (CheckBox cb : checks.values()) cb.setChecked(true);
            }
        });
        btns.addView(all);
        root.addView(btns);

        final AlertDialog dlg = Theme.dialog(this)
                .setTitle(R.string.hidden_sys_title)
                .setView(root)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Set<String> saved = new HashSet<String>();
                        for (Map.Entry<String, CheckBox> e : checks.entrySet()) {
                            if (e.getValue().isChecked()) saved.add(e.getKey());
                        }
                        sp().edit().putStringSet("hide_list", saved).apply();
                        // 只改配置，不立即隐藏：建空间时才把清单固化进「已隐藏」结果
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.show();

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(android.text.Editable e) {
                String kw = e.toString().trim().toLowerCase();
                for (Map.Entry<String, CheckBox> en : checks.entrySet()) {
                    String lb = labels.get(en.getKey());
                    boolean match = kw.isEmpty()
                            || (lb != null && lb.toLowerCase().contains(kw))
                            || en.getKey().toLowerCase().contains(kw);
                    en.getValue().setVisibility(match ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    /** 「已隐藏的应用」入口行：右侧显示已隐藏数量（用户应用 + 系统应用，无则空），点击进管理 */
    /** 生成修复模块到 Download（模块内容由应用现场拼，不单独维护模块包） */
    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
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

    private void makeFixModule() {
        // 说明里先报一句「已装哪个版本、本版内置哪个」，免得用户不知道要不要重刷
        String state = moduleVer == null
                ? getString(R.string.fix_module_state_none, CloneStore.MODULE_VERSION)
                : getString(R.string.fix_module_state, moduleVer, CloneStore.MODULE_VERSION);
        Theme.dialog(this)
                .setTitle(R.string.fix_module_title)
                .setMessage(state + "\n\n" + getString(R.string.fix_module_msg))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        toast(getString(R.string.fix_module_working));
                        new Thread(new Runnable() {
                            public void run() {
                                final String p = CloneStore.writeFixModule(SettingsActivity.this);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        toast(p == null
                                                ? getString(R.string.fix_module_failed)
                                                : getString(R.string.fix_module_done, p));
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 分身存储状态提示：
     * 内核带 sdcardfs（可能有兼容问题）＝强调色（可点）提示，点开看说明（新建空间会自动用独立存储）；
     * 共享存储可用＝绿字；本机内核没有 sdcardfs 驱动＝不显示。
     */
    private void renderStorageWarn() {
        if (spaceWarn == null) return;
        final boolean needStore = storageState == null;
        // 检测要走提权 shell，不能放主线程；算完再显示。
        // 模块版本每次进页面都重读：刚刷完模块回来要立刻看到「已是最新」
        new Thread(new Runnable() {
            public void run() {
                final Integer st = needStore ? CloneStore.storageState() : storageState;
                final String[] mod = CloneStore.moduleInstalled();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (mod == null) {
                            moduleCode = -1;
                            moduleVer = null;
                        } else {
                            moduleCode = Integer.parseInt(mod[0]);
                            moduleVer = mod[1];
                        }
                        if (st == null) {   // 提权未就绪，等 showPermState 补算
                            if (spaceWarn != null) spaceWarn.setVisibility(android.view.View.GONE);
                            return;
                        }
                        storageState = st;
                        applyStorageState();
                    }
                });
            }
        }).start();
    }

    private void applyStorageState() {
        if (spaceWarn == null || storageState == null) return;
        int st = storageState.intValue();
        if (st == 0) {                       // 本机不需要，不显示
            spaceWarn.setVisibility(View.GONE);
            return;
        }
        if (st != 2) {                       // 1 = 内核对 sdcardfs 有驱动，可能有兼容问题
            // 不是错误、更不是本机故障：很多机型（G7 实测）用着完全正常，
            // 只有相册/文件真出问题时才需要处理，所以按普通提示的次要色显示
            spaceWarn.setText(R.string.space_storage_broken);
            // 品牌青绿：红/橙/绿已经都被状态占用了（坏、可升级、已修好），
            // 这条只是「可能不兼容，可点处理」，用品牌色区分，同时不至于像出错
            spaceWarn.setTextColor(Theme.teal(this));
            spaceWarn.setVisibility(View.VISIBLE);
            return;
        }
        // st == 2：内核对 sdcardfs 有驱动但已经走 FUSE —— 只能是修复模块干的。
        // 再确认这个模块是不是应用内置的这一版；不是就提示重刷。
        if (moduleCode >= 0 && moduleCode < CloneStore.MODULE_CODE) {
            spaceWarn.setText(getString(R.string.space_storage_updatable,
                    moduleVer, CloneStore.MODULE_VERSION));
            spaceWarn.setTextColor(Theme.warn(this));
        } else if (moduleCode < 0) {
            // 读不到版本标记 = 2.3 及更早（那两版还不写这个标记）
            spaceWarn.setText(getString(R.string.space_storage_outdated,
                    CloneStore.MODULE_VERSION));
            spaceWarn.setTextColor(Theme.warn(this));
        } else {
            spaceWarn.setText(R.string.space_storage_fixed);
            spaceWarn.setTextColor(Theme.green(this));
        }
        spaceWarn.setVisibility(View.VISIBLE);
    }

    // ---------------- 新空间输入法 ----------------

    /** 右侧计数：「5 个 · 全部」/「5 个 · 带 3 个」 */
    private void renderImePick() {
        if (imePickCount == null) return;
        List<String[]> all = CloneStore.allImes(this);
        Set<String> ex = CloneStore.imeExclude(this);
        int total = all.size();
        int keep = 0;
        for (String[] a : all) if (!ex.contains(a[0])) keep++;
        if (total == 0) {
            imePickCount.setText("");
            return;
        }
        imePickCount.setText(keep == total
                ? getString(R.string.ime_pick_all, total)
                : getString(R.string.ime_pick_some, keep, total));
        imePickCount.setTextColor(keep == 0 ? Theme.red(this) : secColor);
    }

    /** 新空间输入法清单：列出主空间所有输入法，默认全选；取消勾选的建新空间时不带进去 */
    private void showImePicker() {
        final List<String[]> all = CloneStore.allImes(this);
        if (all.isEmpty()) {
            toast(getString(R.string.ime_pick_empty));
            return;
        }
        final Set<String> ex = CloneStore.imeExclude(this);
        final String[] names = new String[all.size()];
        final boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i)[1];
            checked[i] = !ex.contains(all.get(i)[0]);
        }
        Theme.dialog(this)
                .setTitle(R.string.ime_pick_title)
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Set<String> out = new HashSet<String>();
                        for (int i = 0; i < all.size(); i++) if (!checked[i]) out.add(all.get(i)[0]);
                        CloneStore.setImeExclude(SettingsActivity.this, out);
                        renderImePick();
                        toast(getString(R.string.ime_pick_saved));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renderHiddenCard() {
        if (hiddenCount == null) return;
        int n = CloneStore.hiddenClones().size() + CloneStore.hiddenSys().size();
        hiddenCount.setText(n == 0 ? "" : String.valueOf(n));
        hiddenCount.setTextColor(n == 0 ? secColor : fgColor);
    }

    /** 已隐藏的应用：顶部「用户应用/系统应用」分类开关并列，统一列表，系统应用红字。勾选=恢复。 */
    private void showHiddenManager() {
        final Set<String> hiddenClones = CloneStore.hiddenClones();
        final Set<String> hiddenSys = CloneStore.hiddenSys();
        if (hiddenClones.isEmpty() && hiddenSys.isEmpty()) {
            toast(getString(R.string.hidden_empty));
            return;
        }
        final int total = hiddenClones.size() + hiddenSys.size();
        final PackageManager pm = getPackageManager();
        final int accent = Theme.accent(this);
        final int sysRed = Theme.red(this);
        final AlertDialog[] holder = new AlertDialog[1];

        final List<CheckBox> userChecks = new ArrayList<CheckBox>();
        final List<Integer> userUids = new ArrayList<Integer>();
        final List<String> userPkgs = new ArrayList<String>();
        final List<CheckBox> sysChecks = new ArrayList<CheckBox>();
        final List<String> sysPkgs = new ArrayList<String>();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), 0);

        // ==== 顶部两个分类开关并列：勾=启用恢复，空=保持隐藏 ====
        final CheckBox swUser = new CheckBox(this);
        swUser.setText(R.string.tab_user);
        swUser.setTextSize(14);
        swUser.setTextColor(fgColor);
        swUser.setChecked(true);
        final CheckBox swSys = new CheckBox(this);
        swSys.setText(R.string.tab_sys);
        swSys.setTextSize(14);
        swSys.setTextColor(fgColor);
        swSys.setChecked(false);
        LinearLayout swRow = new LinearLayout(this);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setPadding(0, dp(4), 0, dp(10));
        swUser.setPadding(0, 0, dp(24), 0);
        swRow.addView(swUser);
        swRow.addView(swSys);
        root.addView(swRow);

        final EditText search = makeSearchBox(R.string.search_hidden);
        root.addView(search);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        // ==== 用户应用（已隐藏的分身）：按空间分组，组头▾+计数，组内全选/反选，默认勾选 ====
        final Map<Integer, List<String[]>> byUid = new java.util.LinkedHashMap<Integer, List<String[]>>();
        for (String h : hiddenClones) {
            int sep = h.indexOf(':');
            if (sep < 0) continue;
            final int uid;
            try { uid = Integer.parseInt(h.substring(0, sep)); }
            catch (Throwable t) { continue; }
            String pkg = h.substring(sep + 1);
            String label = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = String.valueOf(ai.loadLabel(pm));
            } catch (Throwable ignored) { }
            List<String[]> g = byUid.get(Integer.valueOf(uid));
            if (g == null) { g = new ArrayList<String[]>(); byUid.put(Integer.valueOf(uid), g); }
            g.add(new String[]{pkg, label});
        }

        for (Map.Entry<Integer, List<String[]>> e : byUid.entrySet()) {
            final int uid = e.getKey().intValue();
            final List<String[]> items = e.getValue();
            final String name = CloneStore.nameOf(this, uid);

            final LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            head.setPadding(dp(4), dp(8), dp(4), dp(8));

            final TextView arrow = new TextView(this);
            arrow.setText("▾");
            arrow.setTextSize(16);
            arrow.setTextColor(fgColor);
            arrow.setPadding(0, 0, dp(6), 0);
            head.addView(arrow);

            TextView headName = new TextView(this);
            headName.setText(name);
            headName.setTextSize(14);
            headName.setTextColor(fgColor);
            headName.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(headName);

            TextView headCount = new TextView(this);
            headCount.setText(String.valueOf(items.size()));
            headCount.setTextSize(13);
            headCount.setTextColor(secColor);
            head.addView(headCount);

            final LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setVisibility(View.VISIBLE);

            head.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean open = body.getVisibility() != View.VISIBLE;
                    body.setVisibility(open ? View.VISIBLE : View.GONE);
                    arrow.setText(open ? "▾" : "▸");
                }
            });

            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setPadding(dp(4), dp(2), dp(4), dp(4));
            final List<CheckBox> spaceChecks = new ArrayList<CheckBox>();
            TextView selAll = new TextView(this);
            selAll.setText(R.string.select_all_space);
            selAll.setTextSize(13);
            selAll.setTextColor(accent);
            selAll.setPadding(0, dp(4), dp(16), dp(4));
            selAll.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (CheckBox cb : spaceChecks) cb.setChecked(true);
                }
            });
            ops.addView(selAll);
            TextView invert = new TextView(this);
            invert.setText(R.string.invert_space);
            invert.setTextSize(13);
            invert.setTextColor(accent);
            invert.setPadding(0, dp(4), 0, dp(4));
            invert.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (CheckBox cb : spaceChecks) cb.setChecked(!cb.isChecked());
                }
            });
            ops.addView(invert);
            body.addView(ops);

            for (String[] item : items) {
                CheckBox cb = new CheckBox(this);
                cb.setText(item[1]);
                cb.setTextSize(14);
                cb.setTextColor(fgColor);
                cb.setChecked(true);   // 用户应用默认勾选=恢复
                spaceChecks.add(cb);
                userChecks.add(cb);
                userUids.add(Integer.valueOf(uid));
                userPkgs.add(item[0]);
                body.addView(cb);
            }
            list.addView(head);
            list.addView(body);
        }

        // ==== 系统应用（已隐藏的主空间系统应用）：平铺，默认不勾选，红字 ====
        List<String> sysOrdered = new ArrayList<String>(hiddenSys);
        Collections.sort(sysOrdered);
        for (String p : sysOrdered) {
            String lb = p;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                lb = String.valueOf(ai.loadLabel(pm));
            } catch (Throwable ignored) { }
            CheckBox cb = new CheckBox(this);
            cb.setText(lb);
            cb.setTextSize(14);
            cb.setTextColor(sysRed);
            cb.setChecked(false);   // 系统应用默认不勾选=保持隐藏
            sysChecks.add(cb);
            sysPkgs.add(p);
            list.addView(cb);
        }
        sv.addView(list);
        root.addView(sv);

        // ==== 分类开关：一键全勾/全空该类 ====
        swUser.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = swUser.isChecked();
                for (CheckBox cb : userChecks) cb.setChecked(on);
            }
        });
        swSys.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = swSys.isChecked();
                for (CheckBox cb : sysChecks) cb.setChecked(on);
            }
        });

        // ==== 底部：恢复所选 / 全部恢复 ====
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(8), 0, dp(4));
        TextView restoreSel = new TextView(this);
        restoreSel.setText(R.string.hidden_restore);
        restoreSel.setTextSize(14);
        restoreSel.setTextColor(accent);
        restoreSel.setPadding(dp(4), dp(8), dp(16), dp(8));
        restoreSel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = 0;
                for (int i = 0; i < userChecks.size(); i++) {
                    if (!userChecks.get(i).isChecked()) continue;
                    CloneStore.unhideClone(SettingsActivity.this,
                            userUids.get(i).intValue(), userPkgs.get(i));
                    n++;
                }
                for (int i = 0; i < sysChecks.size(); i++) {
                    if (!sysChecks.get(i).isChecked()) continue;
                    CloneStore.unhideSys(SettingsActivity.this, sysPkgs.get(i));
                    n++;
                }
                if (n > 0) {
                    CloneStore.dirty = true;
                    toast(getString(R.string.hidden_restored, n));
                }
                if (holder[0] != null) holder[0].dismiss();
                loadSpaces();
            }
        });
        btns.addView(restoreSel);
        TextView restoreAll = new TextView(this);
        restoreAll.setText(R.string.hidden_restore_all);
        restoreAll.setTextSize(14);
        restoreAll.setTextColor(accent);
        restoreAll.setPadding(0, dp(8), 0, dp(8));
        restoreAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Theme.dialog(SettingsActivity.this)
                        .setTitle(R.string.hidden_restore_all)
                        .setMessage(R.string.hidden_restore_all_confirm)
                        .setPositiveButton(R.string.hidden_restore_all,
                                new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                for (int i = 0; i < userPkgs.size(); i++) {
                                    CloneStore.unhideClone(SettingsActivity.this,
                                            userUids.get(i).intValue(), userPkgs.get(i));
                                }
                                for (String p : sysPkgs) {
                                    CloneStore.unhideSys(SettingsActivity.this, p);
                                }
                                if (total > 0) {
                                    CloneStore.dirty = true;
                                    toast(getString(R.string.hidden_restored, total));
                                }
                                if (holder[0] != null) holder[0].dismiss();
                                loadSpaces();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        btns.addView(restoreAll);
        root.addView(btns);

        holder[0] = Theme.dialog(this)
                .setTitle(getString(R.string.hidden_title, total))
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0].show();

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(android.text.Editable e) {
                String kw = e.toString().trim().toLowerCase();
                for (CheckBox cb : userChecks) {
                    CharSequence t = cb.getText();
                    boolean match = kw.isEmpty()
                            || (t != null && t.toString().toLowerCase().contains(kw));
                    cb.setVisibility(match ? View.VISIBLE : View.GONE);
                }
                for (CheckBox cb : sysChecks) {
                    CharSequence t = cb.getText();
                    boolean match = kw.isEmpty()
                            || (t != null && t.toString().toLowerCase().contains(kw));
                    cb.setVisibility(match ? View.VISIBLE : View.GONE);
                }
            }
        });
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

        Theme.dialog(this)
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
        // 持续显示「创建中」：start+prune 可能要好几秒，短 toast 早没了会让用户以为卡住
        spaceLimit.setText(getString(R.string.creating));
        new Thread(new Runnable() {
            public void run() {
                String out = Shell.exec(CLI + " create '" + CloneStore.cloneName(name) + "'");
                Matcher m = Pattern.compile("\\{(\\d+):").matcher(out);
                final int uid = m.find() ? Integer.parseInt(m.group(1)) : -1;
                if (uid > 0) {
                    // 先标记归属：startSpace 等待期间 onResume/手动刷新会跑 batch，
                    // 归属没标记，这个新空间就会被判成「系统空间」显示。
                    CloneStore.markOurs(uid);
                    // 再 start 让存储就绪（/data/user/N、/data/media/N）——createProfileForUser
                    // 只建号不建存储，不 start 的话备份还原会报「目录不存在」。
                    CloneStore.startSpace(uid);
                    CloneStore.ensureIme(uid);   // 装主空间输入法并抄一份设置（词库各自独立）
                    if (CloneStore.pruneSys(SettingsActivity.this)) {
                        CloneStore.pruneSpace(SettingsActivity.this, uid, true);
                    }
                }

                final String msg;
                if (uid <= 0) {
                    if (out.contains("Cannot add more") || out.contains("returned null")) {
                        // 提示里必须给真实生效的那道闸：perParent 被 hook 放到 100、
                        // mMaxAllowed 还是 3 时，写 100 只会让人更懵
                        int cap = CloneStore.readCap();
                        msg = getString(R.string.space_limit_reached,
                                cap > 0 ? String.valueOf(cap) : "?");
                    } else {
                        msg = getString(R.string.create_failed, out.trim());
                    }
                } else {
                    msg = out.contains("MODE=private")
                            ? getString(R.string.space_created_private, uid)
                            : getString(R.string.space_created, uid);
                    CloneStore.dirty = true;   // 空间变了重扫空间状态；主身份清单没变，不重载
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

    /** 修复某个空间的输入法：装主空间在用的输入法 + 抄一份设置（幂等，可反复点） */
    private void repairIme(final int uid) {
        toast(getString(R.string.ime_working));
        new Thread(new Runnable() {
            public void run() {
                final CloneStore.ImeResult r = CloneStore.ensureIme(uid);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (r.ok()) {
                            toast(getString(R.string.ime_done, imeLabel(r.def),
                                    String.valueOf(r.imes)));
                        } else {
                            toast(getString(R.string.ime_failed, r.err));
                        }
                    }
                });
            }
        }).start();
    }

    /** 输入法组件（包/类）→ 应用名；取不到就用包名 */
    private String imeLabel(String comp) {
        String p = comp == null ? "" : comp;
        int i = p.indexOf('/');
        if (i > 0) p = p.substring(0, i);
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString();
        } catch (Throwable t) {
            return p;
        }
    }

    /** 给分身空间改名（AOSP 允许随时改，名字就是分身里设置里看到的名字） */
    private void askRenameSpace(final int uid) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint(R.string.new_space_name);
        Theme.dialog(this)
                .setTitle(R.string.rename_space)
                .setView(et)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String name = et.getText().toString().trim();
                        if (name.isEmpty()) return;
                        CloneStore.dirty = true;
                        loadSpaces();
                        // 系统名（LSP 管理器里看到的那个名字）：setUserName 要 MANAGE_USERS，
                        // 只有 root 能做。执行时实时探一次 root，有就改，没有如实提示「需要 root」。
                        new Thread(new Runnable() {
                            public void run() {
                                final String r = Shell.execWithRoot(CLI + " rename " + uid + " '"
                                        + name.replace("'", "") + "'");
                                final boolean synced = r.contains("renamed");
                                runOnUiThread(new Runnable() {
                                    public void run() {
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
            toast(getString(R.string.err_not_clone, uid));
            return;
        }
        Theme.dialog(this)
                .setTitle(R.string.delete_space)
                .setMessage(getString(
                        CloneStore.isOurs(null, uid)
                                ? R.string.delete_space_msg
                                : R.string.delete_space_msg_foreign, uid))
                .setPositiveButton(R.string.delete_space, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            public void run() {
                                final String r = Shell.exec(CLI + " remove " + uid);
                                final boolean gone = !CloneStore.listSpaceUids()
                                        .contains(Integer.valueOf(uid));
                                if (gone) {
                                    sp().edit().remove("space_pkgs_" + uid).apply();
                                    CloneStore.unmarkOurs(uid);
                                }
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        CloneStore.dirty = true;
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
