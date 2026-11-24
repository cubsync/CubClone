package com.cubsync.cubclone;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Root 功能开关页。
 *
 * 需要 root 的功能（跨空间克隆数据、数据备份）在这里统一管：
 * 总开关一关，所有入口消失；单项开关可以只留自己要用的那个。
 * 拿不到 root 时（只用 Shizuku）入口本来就不显示，这里只如实报状态。
 */
public class RootActivity extends Activity {


    /** 界面语言跟随应用设置（Locales），不能只靠系统语言 */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Locales.wrap(base));
    }

    private LinearLayout root;
    private LinearLayout header;
    private TextView title;
    private TextView status;
    private TextView secTitle;
    private LinearLayout card;
    private TextView masterLabel, masterHint;
    private TextView cloneLabel, cloneHint;
    private TextView backupLabel, backupHint;
    private TextView relLabel, relHint;
    private Switch swMaster, swClone, swBackup, swRel;
    private ImageButton btnBack;
    private TextView rtLimitSec, rtLimitState, rtLimitHint, rtLimitInstall, rtLimitTune;
    private LinearLayout rtLimitCard;

    private int fg = Theme.FG_LIGHT;
    private int sec = Theme.SEC_LIGHT;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_root);

        root = findViewById(R.id.rt_root);
        header = findViewById(R.id.rt_header);
        title = findViewById(R.id.rt_title);
        status = findViewById(R.id.rt_status);
        secTitle = findViewById(R.id.rt_sec);
        card = findViewById(R.id.rt_card);
        masterLabel = findViewById(R.id.rt_master_label);
        masterHint = findViewById(R.id.rt_master_hint);
        cloneLabel = findViewById(R.id.rt_clone_label);
        cloneHint = findViewById(R.id.rt_clone_hint);
        backupLabel = findViewById(R.id.rt_backup_label);
        backupHint = findViewById(R.id.rt_backup_hint);
        relLabel = findViewById(R.id.rt_relaunch_label);
        relHint = findViewById(R.id.rt_relaunch_hint);
        swMaster = findViewById(R.id.rt_master);
        swClone = findViewById(R.id.rt_clone);
        swBackup = findViewById(R.id.rt_backup);
        swRel = findViewById(R.id.rt_relaunch);
        btnBack = findViewById(R.id.rt_back);

        rtLimitSec = findViewById(R.id.rt_limit_sec);
        rtLimitCard = findViewById(R.id.rt_limit_card);
        rtLimitState = findViewById(R.id.rt_limit_state);
        rtLimitHint = findViewById(R.id.rt_limit_hint);
        rtLimitInstall = findViewById(R.id.rt_limit_install);
        rtLimitTune = findViewById(R.id.rt_limit_tune);
        rtLimitInstall.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { installModule(); }
        });
        rtLimitTune.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askLimit(); }
        });

        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        swMaster.setChecked(Root.master(this));
        swClone.setChecked(Root.feature(this, Root.CLONE));
        swBackup.setChecked(Root.feature(this, Root.BACKUP));
        swMaster.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                sp().edit().putBoolean("root_master", checked).apply();
                applyGate();
            }
        });
        swClone.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                sp().edit().putBoolean("root_feat_" + Root.CLONE, checked).apply();
            }
        });
        swBackup.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                sp().edit().putBoolean("root_feat_" + Root.BACKUP, checked).apply();
            }
        });
        // 备份/还原完把跑之前在用的应用放回去（只认有界面的，推送常驻的不算）
        swRel.setChecked(sp().getBoolean("backup_relaunch", true));
        swRel.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                sp().edit().putBoolean("backup_relaunch", checked).apply();
            }
        });

        applyChrome();
        applyGate();
        probe();
        renderLimit();
    }

    protected void onResume() {
        super.onResume();
        // 从 LSPosed 启用/装完模块回来，这一块的状态必须重读
        applyChrome();
        renderLimit();
    }

    /** 总开关关掉时，下面两个单项一起置灰但保留原值（重新打开总开关就恢复） */
    private void applyGate() {
        boolean on = swMaster.isChecked();
        card.setAlpha(on ? 1f : 0.45f);
        swClone.setEnabled(on);
        swBackup.setEnabled(on);
        swRel.setEnabled(on);
    }

    private void probe() {
        status.setText(R.string.root_status_checking);
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = Shell.checkRoot();
                runOnUiThread(new Runnable() {
                    public void run() {
                        status.setText(ok ? R.string.root_status_ok : R.string.root_status_bad);
                        status.setTextColor(ok ? sec : sec);
                    }
                });
            }
        }).start();
    }

    // ── 分身数量上限（LSPosed 解锁模块） ───────────────────────────

    /**
     * 三种状态：没提权 / 模块没生效（显示系统原值，调整按钮灰掉）/ 已生效（绿色 + 真实上限）。
     * 读属性和 dumpsys 都要跑 shell，不能放主线程。
     */
    private void renderLimit() {
        if (rtLimitState == null) return;
        if (!Shell.ready()) {
            rtLimitState.setText(R.string.limit_need_perm);
            rtLimitState.setTextColor(sec);
            rtLimitTune.setEnabled(false);
            rtLimitTune.setAlpha(0.45f);
            return;
        }
        rtLimitState.setText(R.string.loading);
        new Thread(new Runnable() {
            public void run() {
                final Integer hk = CloneStore.hookLimit();
                final int cap = CloneStore.readCap();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || rtLimitState == null) return;
                        if (hk != null) {
                            rtLimitState.setText(getString(R.string.limit_on,
                                    cap > 0 ? cap : hk.intValue()));
                            rtLimitState.setTextColor(Theme.green(RootActivity.this));
                            rtLimitHint.setText(R.string.limit_active_tip);
                            rtLimitTune.setEnabled(true);
                            rtLimitTune.setAlpha(1f);
                            rtLimitInstall.setText(R.string.limit_reinstall);
                        } else {
                            rtLimitState.setText(R.string.limit_no_module);
                            rtLimitState.setTextColor(sec);
                            rtLimitHint.setText(R.string.limit_install_tip);
                            rtLimitTune.setEnabled(false);
                            rtLimitTune.setAlpha(0.45f);
                            rtLimitInstall.setText(R.string.limit_install);
                        }
                        applyChrome();
                    }
                });
            }
        }).start();
    }

    /** 只负责装；LSPosed 里的启用和重启必须用户自己来，我们不改它的配置库 */
    private void installModule() {
        if (!Shell.ready()) {
            toast(getString(R.string.limit_need_perm));
            return;
        }
        rtLimitInstall.setEnabled(false);
        rtLimitInstall.setText(R.string.limit_installing);
        new Thread(new Runnable() {
            public void run() {
                final String err = CloneStore.installUnlockModule(RootActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        rtLimitInstall.setEnabled(true);
                        toast(err == null ? getString(R.string.limit_install_ok)
                                : getString(R.string.limit_install_fail, err));
                        renderLimit();
                    }
                });
            }
        }).start();
    }

    private void askLimit() {
        final Integer hk = CloneStore.hookLimit();
        final int cur = hk != null ? hk.intValue() : CloneStore.HOOK_DEFAULT;
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(cur));
        et.setSelection(et.getText().length());
        box.addView(et);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.limit_dialog_msg, cur, CloneStore.HOOK_MAX));
        hint.setTextSize(12);
        hint.setTextColor(sec);
        hint.setPadding(0, pad / 2, 0, 0);
        box.addView(hint);

        Theme.dialog(this)
                .setTitle(R.string.limit_dialog_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        int n;
                        try {
                            n = Integer.parseInt(et.getText().toString().trim());
                        } catch (Throwable t) {
                            n = -1;
                        }
                        if (n < 1 || n > CloneStore.HOOK_MAX) {
                            toast(getString(R.string.limit_bad_value, CloneStore.HOOK_MAX));
                            return;
                        }
                        applyLimit(n);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyLimit(final int n) {
        new Thread(new Runnable() {
            public void run() {
                final String err = CloneStore.writeHookLimit(n);
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(err == null ? getString(R.string.limit_saved, n)
                                : getString(R.string.create_failed, err));
                        renderLimit();
                    }
                });
            }
        }).start();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private SharedPreferences sp() {
        return getSharedPreferences("clone", MODE_PRIVATE);
    }

    /** 主题判断只走 Theme，本页自己再判一次就是 11-24 颜色配错的根源（1=深、2=浅，别写反） */
    private boolean isNight() {
        return Theme.isNight(this);
    }

    private void applyChrome() {
        boolean night = isNight();
        boolean black = night && sp().getBoolean("amoled", false);
        int bg = Theme.bg(this);
        fg = Theme.fg(this);
        sec = Theme.sec(this);

        root.setBackgroundColor(bg);
        header.setBackgroundColor(bg);
        title.setTextColor(fg);
        btnBack.setColorFilter(fg);
        status.setTextColor(sec);
        secTitle.setTextColor(sec);
        masterLabel.setTextColor(fg);
        cloneLabel.setTextColor(fg);
        backupLabel.setTextColor(fg);
        relLabel.setTextColor(fg);
        masterHint.setTextColor(sec);
        cloneHint.setTextColor(sec);
        backupHint.setTextColor(sec);
        relHint.setTextColor(sec);

        if (rtLimitSec != null) rtLimitSec.setTextColor(sec);
        if (rtLimitHint != null) rtLimitHint.setTextColor(sec);
        int btnBg = Theme.chipBg(this);
        int btnFg = Theme.chipFg(this);
        for (TextView t : new TextView[]{rtLimitInstall, rtLimitTune}) {
            if (t == null) continue;
            if (t.getBackground() != null) t.getBackground().setTint(btnBg);
            t.setTextColor(btnFg);
        }
    }
}
