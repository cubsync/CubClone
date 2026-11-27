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
    private TextView rtPluginState;
    private LinearLayout rtLimitCard;
    private TextView rtInstallLabel, rtInstallState, rtInstallHint;
    private LinearLayout rtInstallCard;
    private Switch swInstall;
    private TextView rtGuardLabel, rtGuardState, rtGuardHint;
    private Switch swGuard;
    private LinearLayout rtGuardCard;
    private boolean guardBinding;
    private TextView rtHideLabel, rtHideState, rtHideHint;
    private Switch swHide;
    private boolean hideBinding;
    /** 程序化 setChecked 会回调监听器，用这个挡住，免得自己把属性写一圈 */
    private boolean installBinding;

    private int fg = Theme.FG_LIGHT;
    private int sec = Theme.SEC_LIGHT;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        // 直接进本页（桌面快捷方式/通知）时没经过 MainActivity，先把全局 Context 挂上，
        // 否则读「已装插件版本」拿不到 PackageManager
        CloneStore.attach(this);
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
        rtPluginState = findViewById(R.id.rt_plugin_state);
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

        rtInstallCard = findViewById(R.id.rt_install_card);
        rtInstallLabel = findViewById(R.id.rt_install_label);
        rtInstallState = findViewById(R.id.rt_install_state);
        rtInstallHint = findViewById(R.id.rt_install_hint);
        swInstall = findViewById(R.id.rt_install);
        swInstall.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                if (installBinding) return;
                saveInstall(checked);
            }
        });

        rtGuardLabel = findViewById(R.id.rt_guard_label);
        rtGuardState = findViewById(R.id.rt_guard_state);
        rtGuardHint = findViewById(R.id.rt_guard_hint);
        rtGuardCard = findViewById(R.id.rt_guard_card);
        swGuard = findViewById(R.id.rt_guard);
        swGuard.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                if (guardBinding) return;
                saveGuard(checked);
            }
        });

        rtHideLabel = findViewById(R.id.rt_hide_label);
        rtHideState = findViewById(R.id.rt_hide_state);
        rtHideHint = findViewById(R.id.rt_hide_hint);
        swHide = findViewById(R.id.rt_hide);
        swHide.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                if (hideBinding) return;
                saveHide(checked);
            }
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
        renderInstall();
        renderGuard();
        renderHide();
        renderPluginState();
        // 直接进本页（不经设置页）时 Shell 还没探测过，ready() 是 false，
        // 两个插件区块会误报「需要先授权 root」。这里自己探一次再重画。
        new Thread(new Runnable() {
            public void run() {
                Shell.probe();
                runOnUiThread(new Runnable() {
                    public void run() {
                        renderLimit();
                        renderInstall();
                        renderGuard();
                        renderHide();
                        renderPluginState();
                    }
                });
            }
        }).start();
    }

    protected void onResume() {
        super.onResume();
        // 从 LSPosed 启用/装完模块回来，这一块的状态必须重读
        applyChrome();
        renderLimit();
        renderInstall();
        renderGuard();
        renderHide();
        renderPluginState();
    }

    /**
     * 「插件功能」栏的版本提示，三个版本一起看：
     * <ul>
     *   <li><b>运行</b>：{@code sys.cubclone.hook.ver}，进程里真正在跑的那一版；</li>
     *   <li><b>已装</b>：模块 APK 的 versionName，装完立刻变；</li>
     *   <li><b>内嵌</b>：主应用 assets 里带的那一份，就是「可用的最新版」，不用联网。</li>
     * </ul>
     * 四态：正常运行（绿）／有新版本（橙）／已装新版待重启（橙）／未装未启用（灰）。
     * 只写文字不给按钮：装插件用下面卡片里的「重装增强插件」，多一个入口是重复。
     */
    private void renderPluginState() {
        if (rtPluginState == null) return;
        if (!Shell.ready()) {
            rtPluginState.setText("");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final boolean active = CloneStore.hookActive();
                final String run = active ? CloneStore.hookRunningVersion() : null;
                final String ins = CloneStore.hookInstalledVersion();
                final String bundled = CloneStore.hookBundledVersion();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || rtPluginState == null) return;
                        String text;
                        int color;
                        if (ins != null && bundled != null
                                && CloneStore.verCmp(bundled, ins) > 0) {
                            // 内嵌的比已装的新：先让用户能拿到新版。
                            // 这条必须排在最前 —— 否则「已装 ≠ 运行」会先命中「重启生效」，
                            // 用户永远看不到还有新版可装（魅族实测 reg=dev.5 / ins=dev.6 / 内嵌 dev.7）。
                            text = run == null
                                    ? getString(R.string.plugin_state_new_idle, ins, bundled)
                                    : getString(R.string.plugin_state_new, run, ins, bundled);
                            color = Theme.warn(RootActivity.this);
                        } else if (active && run != null && ins != null && !ins.equals(run)) {
                            // 已装就是最新，但进程里还在跑旧的
                            text = getString(R.string.plugin_state_pending, run, ins);
                            color = Theme.warn(RootActivity.this);
                        } else if (active && run != null && ins != null) {
                            text = getString(R.string.plugin_state_running, run, ins);
                            color = Theme.green(RootActivity.this);
                        } else if (active && run != null) {
                            text = getString(R.string.plugin_state_running_only, run);
                            color = Theme.green(RootActivity.this);
                        } else if (active && ins != null) {
                            text = getString(R.string.plugin_state_running_old, ins);
                            color = Theme.green(RootActivity.this);
                        } else if (active) {
                            text = getString(R.string.plugin_state_active);
                            color = Theme.green(RootActivity.this);
                        } else if (ins != null) {
                            text = getString(R.string.plugin_state_installed, ins);
                            color = sec;
                        } else {
                            text = getString(R.string.plugin_state_none);
                            color = sec;
                        }
                        rtPluginState.setText(text);
                        rtPluginState.setTextColor(color);
                    }
                });
            }
        }).start();
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
        doInstallModule();
    }

    private void doInstallModule() {
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
                        renderPluginState();
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

    // ── 安装拦截（未指定用户 → 主空间） ──────────────────────────

    /**
     * 三态：没 root / 模块没生效 / 已生效（或已关闭）。
     * 默认开：属性没写过就按开显示——hook 自己缺省也是开，两边不能不一致。
     */
    private void renderInstall() {
        if (rtInstallState == null) return;
        if (!Shell.ready()) {
            rtInstallState.setText(R.string.install_need_perm);
            rtInstallState.setTextColor(sec);
            installBinding = true;
            swInstall.setChecked(false);
            installBinding = false;
            swInstall.setEnabled(false);
            rtInstallCard.setAlpha(0.45f);
            return;
        }
        rtInstallState.setText(R.string.loading);
        rtInstallState.setTextColor(sec);
        new Thread(new Runnable() {
            public void run() {
                final Boolean on = CloneStore.installConverge();
                // 属性没写过就落一次显式值：以后 hook 改缺省也不会和我们显示的对不上
                if (Shell.raw("getprop", CloneStore.HOOK_INSTALL_PERSIST).trim().length() == 0) {
                    CloneStore.writeInstallConverge(on == null || on.booleanValue());
                }
                final boolean val = on == null || on.booleanValue();
                final boolean mod = CloneStore.hookLimit() != null;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || rtInstallState == null) return;
                        installBinding = true;
                        swInstall.setChecked(val);
                        installBinding = false;
                        swInstall.setEnabled(true);
                        rtInstallCard.setAlpha(1f);
                        if (!mod) {
                            rtInstallState.setText(R.string.install_no_module);
                            rtInstallState.setTextColor(sec);
                        } else {
                            rtInstallState.setText(val ? R.string.install_state_on
                                    : R.string.install_state_off);
                            rtInstallState.setTextColor(val
                                    ? Theme.green(RootActivity.this) : sec);
                        }
                        applyChrome();
                    }
                });
            }
        }).start();
    }

    private void saveInstall(final boolean on) {
        if (!Shell.ready()) {
            installBinding = true;
            swInstall.setChecked(!on);
            installBinding = false;
            toast(getString(R.string.install_need_perm));
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final String err = CloneStore.writeInstallConverge(on);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err != null) {
                            installBinding = true;
                            swInstall.setChecked(!on);
                            installBinding = false;
                            toast(getString(R.string.install_save_fail, err));
                        } else {
                            toast(on ? getString(R.string.install_on_toast)
                                    : getString(R.string.install_off_toast));
                        }
                        renderInstall();
                    }
                });
            }
        }).start();
    }

    /**
     * 桌面防护：开关 + 是否真的挂上了。
     *
     * <p>状态不猜：插件在桌面进程里把每个挂载点的结果写成 {@code sys.cubclone.hook.lw.pts}，
     * 形如 {@code pc1,add1,task2,prov1,on1}。总入口 {@code pc} 拿不到就说明系统桌面改版了，
     * 这里必须显式报「未生效」，不能装作正常。
     */
    private void renderGuard() {
        if (rtGuardState == null) return;
        if (!Shell.ready()) {
            rtGuardState.setText(R.string.install_need_perm);
            rtGuardState.setTextColor(sec);
            guardBinding = true;
            swGuard.setChecked(false);
            guardBinding = false;
            swGuard.setEnabled(false);
            if (rtGuardCard != null) rtGuardCard.setAlpha(0.45f);
            return;
        }
        rtGuardState.setText(R.string.loading);
        rtGuardState.setTextColor(sec);
        new Thread(new Runnable() {
            public void run() {
                final Boolean on = CloneStore.guardOn();
                // 没写过就落一次显式值，界面显示和插件缺省永远一致
                if (Shell.raw("getprop", CloneStore.HOOK_GUARD_PERSIST).trim().length() == 0) {
                    CloneStore.writeGuard(on == null || on.booleanValue());
                }
                final boolean val = on == null || on.booleanValue();
                final String pts = CloneStore.guardPoints();
                final String hits = CloneStore.guardHits();
                final String alive = CloneStore.guardAlive();
                // 这台桌面自己有没有「建号后往主空间补图标」这条链路：看它 APK 里的类，
                // 不看品牌。出厂就没这段代码的机器（魅族、AOSP）本来就不需要拦。
                // 开关关着就不用扫，省一次一秒的 dex 读取。
                final Boolean need = val ? CloneStore.guardNeeded() : null;
                final boolean mod = CloneStore.hookLimit() != null;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || rtGuardState == null) return;
                        guardBinding = true;
                        swGuard.setChecked(val);
                        guardBinding = false;
                        swGuard.setEnabled(true);
                        if (rtGuardCard != null) rtGuardCard.setAlpha(1f);
                        if (!mod) {
                            // 插件没装/没生效
                            rtGuardState.setText(R.string.install_no_module);
                            rtGuardState.setTextColor(sec);
                        } else if (!val) {
                            rtGuardState.setText(R.string.guard_state_off);
                            rtGuardState.setTextColor(sec);
                        } else if (anyPoint(pts)) {
                            // 桌面进程里确实挂上了，这是最强的证据，优先于静态探测
                            String s = getString(R.string.guard_state_on);
                            if (hits.length() > 0 && !"0".equals(hits)) {
                                s = getString(R.string.guard_state_hits, hits);
                            }
                            rtGuardState.setText(s);
                            rtGuardState.setTextColor(Theme.green(RootActivity.this));
                        } else if (Boolean.FALSE.equals(need)) {
                            // 本机桌面 APK 里根本没有那套实现 —— 这台不需要防护，不是坏了
                            rtGuardState.setText(R.string.guard_state_not_needed);
                            rtGuardState.setTextColor(sec);
                        } else if (Boolean.TRUE.equals(need)) {
                            // 有这行为却一个入口都没挂上：要么模块没进桌面进程，要么类改名了
                            rtGuardState.setText(alive.length() > 0
                                    ? R.string.guard_state_stale : R.string.guard_state_unloaded);
                            rtGuardState.setTextColor(Theme.red(RootActivity.this));
                        } else {
                            rtGuardState.setText(R.string.guard_state_unknown);
                            rtGuardState.setTextColor(sec);
                        }
                        applyChrome();
                    }
                });
            }
        }).start();
    }

    /**
     * 挂载点串 {@code pc1,add2,task2,prov1,on1} 里是否至少有一个真正挂上。
     * 不同 ROM 走的入口不一样：ColorOS 有 pc/task，AOSP 只有 add —— 只要有一个在，
     * 防护就成立；全 0 才是真的没挂上。
     */
    private static boolean anyPoint(String pts) {
        if (pts == null || pts.length() == 0) return false;
        for (String kv : pts.split(",")) {
            if (kv.startsWith("on")) continue;
            StringBuilder num = new StringBuilder();
            for (int i = 0; i < kv.length(); i++) {
                char ch = kv.charAt(i);
                if (ch >= '0' && ch <= '9') num.append(ch);
            }
            if (num.length() == 0) continue;
            try {
                if (Integer.parseInt(num.toString()) > 0) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 主桌面是否隐藏分身应用（框架层 `LauncherAppsService.isEnabledProfileOf` +
     * `UserManagerService.getProfileIds` 两条）。
     *
     * <p>和上面「建空间不补图标」是两件事：这一条在 system_server 里，任何 ROM 都走，
     * 关掉的效果是分身应用照常出现在主桌面的应用列表 / 搜索 / 文件夹里。
     */
    private void renderHide() {
        if (rtHideState == null) return;
        if (!Shell.ready()) {
            rtHideState.setText(R.string.install_need_perm);
            rtHideState.setTextColor(sec);
            hideBinding = true;
            swHide.setChecked(false);
            hideBinding = false;
            swHide.setEnabled(false);
            return;
        }
        rtHideState.setText(R.string.loading);
        rtHideState.setTextColor(sec);
        new Thread(new Runnable() {
            public void run() {
                final Boolean on = CloneStore.hideOn();
                if (Shell.raw("getprop", CloneStore.HOOK_HIDE_PERSIST).trim().length() == 0) {
                    CloneStore.writeHide(on == null || on.booleanValue());
                }
                final boolean val = on == null || on.booleanValue();
                final boolean mod = CloneStore.hookLimit() != null;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || rtHideState == null) return;
                        hideBinding = true;
                        swHide.setChecked(val);
                        hideBinding = false;
                        swHide.setEnabled(true);
                        if (!mod) {
                            rtHideState.setText(R.string.install_no_module);
                            rtHideState.setTextColor(sec);
                        } else if (val) {
                            rtHideState.setText(R.string.hide_state_on);
                            rtHideState.setTextColor(Theme.green(RootActivity.this));
                        } else {
                            rtHideState.setText(R.string.hide_state_off);
                            rtHideState.setTextColor(sec);
                        }
                    }
                });
            }
        }).start();
    }

    private void saveHide(final boolean on) {
        if (!Shell.ready()) {
            hideBinding = true;
            swHide.setChecked(!on);
            hideBinding = false;
            toast(getString(R.string.install_need_perm));
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final String err = CloneStore.writeHide(on);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err != null) {
                            hideBinding = true;
                            swHide.setChecked(!on);
                            hideBinding = false;
                            toast(getString(R.string.install_save_fail, err));
                        } else {
                            toast(on ? getString(R.string.hide_on_toast)
                                    : getString(R.string.hide_off_toast));
                        }
                        renderHide();
                    }
                });
            }
        }).start();
    }

    private void saveGuard(final boolean on) {
        if (!Shell.ready()) {
            guardBinding = true;
            swGuard.setChecked(!on);
            guardBinding = false;
            toast(getString(R.string.install_need_perm));
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final String err = CloneStore.writeGuard(on);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err != null) {
                            guardBinding = true;
                            swGuard.setChecked(!on);
                            guardBinding = false;
                            toast(getString(R.string.install_save_fail, err));
                        } else {
                            toast(on ? getString(R.string.guard_on_toast)
                                    : getString(R.string.guard_off_toast));
                        }
                        renderGuard();
                        renderHide();
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
        if (rtInstallLabel != null) rtInstallLabel.setTextColor(fg);
        if (rtInstallHint != null) rtInstallHint.setTextColor(sec);
        if (rtGuardLabel != null) rtGuardLabel.setTextColor(fg);
        if (rtGuardHint != null) rtGuardHint.setTextColor(sec);
        if (rtHideLabel != null) rtHideLabel.setTextColor(fg);
        if (rtHideHint != null) rtHideHint.setTextColor(sec);
        int btnBg = Theme.chipBg(this);
        int btnFg = Theme.chipFg(this);
        for (TextView t : new TextView[]{rtLimitInstall, rtLimitTune}) {
            if (t == null) continue;
            if (t.getBackground() != null) t.getBackground().setTint(btnBg);
            t.setTextColor(btnFg);
        }
    }
}
