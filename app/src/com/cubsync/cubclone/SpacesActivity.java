package com.cubsync.cubclone;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分身空间管理：独立页。
 *
 * 设置页里那块列表只保留「已用 N / M」和入口，真正的浏览、改名、删除、
 * 批量操作都在这里 —— 空间多了以后挤在设置页里既滚不动也点不准。
 */
public class SpacesActivity extends Activity {

    private View root, header, bar;
    private ImageButton btnBack;
    private TextView title, summary, btnSelect, btnAll, btnBackup, btnDel;
    private LinearLayout list;

    private CloneStore.SpaceScan scan;
    private boolean loading;
    private boolean selectMode;
    private final Set<Integer> selected = new HashSet<Integer>();
    private int fgColor, secColor;

    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Locales.wrap(base));
    }

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_spaces);
        root = findViewById(R.id.root);
        header = findViewById(R.id.header);
        bar = findViewById(R.id.sp_bar);
        title = (TextView) findViewById(R.id.sp_title);
        summary = (TextView) findViewById(R.id.sp_summary);
        list = (LinearLayout) findViewById(R.id.sp_list);
        btnSelect = (TextView) findViewById(R.id.sp_select);
        btnAll = (TextView) findViewById(R.id.sp_all);
        btnBackup = (TextView) findViewById(R.id.sp_backup);
        btnDel = (TextView) findViewById(R.id.sp_del);
        btnBack = (ImageButton) findViewById(R.id.sp_back);

        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        btnSelect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSelectMode(); }
        });
        btnAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSelectAll(); }
        });
        btnDel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { removeSelected(); }
        });
        btnBackup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { backupSelected(); }
        });

        applyChrome();
        load();
    }

    protected void onResume() {
        super.onResume();
        applyChrome();
        // 从备份/别的页回来要重扫：删了或改了名的空间不能还显示着
        load();
    }

    // ── 配色 ────────────────────────────────────────────────────────

    private void applyChrome() {
        fgColor = Theme.fg(this);
        secColor = Theme.sec(this);
        root.setBackgroundColor(Theme.bg(this));
        header.setBackgroundColor(Theme.bg(this));
        title.setTextColor(fgColor);
        btnBack.setColorFilter(fgColor);
        summary.setTextColor(secColor);
        btnSelect.setTextColor(Theme.accent(this));
        int chipBg = Theme.chipBg(this);
        int chipFg = Theme.chipFg(this);
        for (TextView t : new TextView[]{btnAll, btnBackup}) {
            if (t.getBackground() != null) t.getBackground().setTint(chipBg);
            t.setTextColor(chipFg);
        }
        if (btnDel.getBackground() != null) btnDel.getBackground().setTint(chipBg);
        btnDel.setTextColor(Theme.red(this));
        bar.setBackgroundColor(Theme.card(this));
        render();
    }

    // ── 数据 ────────────────────────────────────────────────────────

    private void load() {
        if (loading) return;
        loading = true;
        summary.setText(R.string.loading);
        list.removeAllViews();
        // 进程刚起来（重装/被回收）时 rootOk 还是 false，这时扫出来是空列表，
        // 界面就成了「已用 0 个」—— 先探一次提权，拿到再扫。
        if (!Shell.ready()) {
            new Thread(new Runnable() {
                public void run() {
                    Shell.probe();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            loading = false;
                            load();
                        }
                    });
                }
            }).start();
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final CloneStore.SpaceScan sc = CloneStore.scanSpaces(SpacesActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        scan = sc;
                        loading = false;
                        render();
                    }
                });
            }
        }).start();
    }

    private void render() {
        if (scan == null) return;
        renderSummary();
        list.removeAllViews();
        if (scan.uids.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.space_empty);
            tv.setTextSize(13);
            tv.setTextColor(secColor);
            tv.setPadding(0, dp(8), 0, 0);
            list.addView(tv);
            return;
        }
        for (int i = 0; i < scan.uids.size(); i++) {
            list.addView(row(scan.uids.get(i).intValue(),
                    scan.counts.get(i).size(),
                    scan.foreign.get(i).booleanValue()));
        }
    }

    private void renderSummary() {
        int cap = scan.cap();
        StringBuilder sb = new StringBuilder();
        sb.append(cap > 0
                ? getString(R.string.space_used_of, scan.uids.size(), cap)
                : getString(R.string.space_used, scan.uids.size()));
        if (cap > 0 && scan.oursCount() >= cap) {
            sb.append("\n").append(getString(R.string.space_limit_one));
        }
        summary.setText(sb.toString());
    }

    /** 一行：多选模式画勾选框，普通模式画「⋯」菜单 */
    private View row(final int uid, int apps, final boolean foreign) {
        final boolean on = selected.contains(Integer.valueOf(uid));
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(10), dp(8), dp(10));
        if (on) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setColor(Theme.row(this));
            r.setBackground(g);
        }

        if (selectMode) {
            CheckBox cb = new CheckBox(this);
            cb.setChecked(on);
            cb.setClickable(false);
            Theme.check(cb, this);
            r.addView(cb);
        }

        TextView tv = new TextView(this);
        tv.setText(getString(R.string.sp_row, CloneStore.nameOf(this, uid), uid, apps));
        tv.setTextSize(14);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setTextColor(foreign ? secColor : fgColor);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(tv);

        // 分身模式：克隆型（profile.CLONE，和主用户同组、天然同屏可见）/
        // 独立型（full.SECONDARY，Flyme 不支持 clone profile 时的降级结果，可见性由插件补）。
        // 两者在系统里行为不同，标出来才看得懂为什么有的空间要插件才打得开。
        if (!foreign) {
            TextView mode = new TextView(this);
            mode.setText(CloneStore.isCloneSpace(uid)
                    ? R.string.space_mode_clone : R.string.space_mode_full);
            mode.setTextSize(11);
            mode.setTextColor(secColor);
            mode.setPadding(dp(6), 0, dp(2), 0);
            r.addView(mode);
        }

        if (foreign) {
            TextView tag = new TextView(this);
            tag.setText(R.string.space_foreign);
            tag.setTextSize(12);
            tag.setTextColor(secColor);
            tag.setPadding(dp(8), 0, dp(4), 0);
            r.addView(tag);
        }

        if (!selectMode) {
            TextView more = new TextView(this);
            more.setText(R.string.sp_more);
            more.setTextSize(16);
            more.setTextColor(secColor);
            more.setPadding(dp(12), dp(4), dp(8), dp(4));
            more.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { rowMenu(uid, foreign); }
            });
            r.addView(more);
        }

        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!selectMode) {
                    rowMenu(uid, foreign);
                    return;
                }
                Integer k = Integer.valueOf(uid);
                if (!selected.remove(k)) selected.add(k);
                render();
            }
        });
        return r;
    }

    // ── 单行操作 ────────────────────────────────────────────────────

    private void rowMenu(final int uid, boolean foreign) {
        final List<String> items = new ArrayList<String>();
        final List<Integer> acts = new ArrayList<Integer>();
        if (!foreign) {
            items.add(getString(R.string.rename_space));
            acts.add(Integer.valueOf(0));
        }
        // 系统自带空间只在开了「管理非自建空间」时给删除入口，和设置页口径一致
        if (!foreign || CloneStore.manageForeign(this)) {
            items.add(getString(R.string.delete_space));
            acts.add(Integer.valueOf(1));
        }
        if (items.isEmpty()) return;
        Theme.dialog(this)
                .setTitle(getString(R.string.sp_row_title, CloneStore.nameOf(this, uid), uid))
                .setItems(items.toArray(new String[items.size()]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                int a = acts.get(which).intValue();
                                if (a == 0) askRename(uid); else confirmRemove(uid);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askRename(final int uid) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setHint(R.string.new_space_name);
        Theme.dialog(this)
                .setTitle(R.string.rename_space)
                .setView(et)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String name = et.getText().toString().trim();
                        if (name.length() == 0) return;
                        CloneStore.dirty = true;
                        new Thread(new Runnable() {
                            public void run() {
                                final String r = Shell.execWithRoot(CloneStore.CLI + " rename " + uid
                                        + " '" + name.replace("'", "") + "'");
                                final boolean synced = r.contains("renamed");
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        toast(getString(synced
                                                ? R.string.space_renamed_synced
                                                : R.string.space_renamed_label_only));
                                        load();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemove(final int uid) {
        if (!Launcher.safe(uid)) {
            toast(getString(R.string.err_not_clone, uid));
            return;
        }
        Theme.dialog(this)
                .setTitle(R.string.delete_space)
                .setMessage(getString(CloneStore.isOurs(null, uid)
                        ? R.string.delete_space_msg : R.string.delete_space_msg_foreign, uid))
                .setPositiveButton(R.string.delete_space, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        removeOne(uid);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void removeOne(final int uid) {
        new Thread(new Runnable() {
            public void run() {
                final String r = Shell.exec(CloneStore.CLI + " remove " + uid);
                final boolean gone = !CloneStore.listSpaceUids()
                        .contains(Integer.valueOf(uid));
                if (gone) {
                    getSharedPreferences("clone", MODE_PRIVATE).edit()
                            .remove("space_pkgs_" + uid).apply();
                    CloneStore.unmarkOurs(uid);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        CloneStore.dirty = true;
                        MainActivity.unpinShortcut(SpacesActivity.this, uid, null);
                        toast(r.contains("REFUSED") ? r.trim()
                                : getString(gone ? R.string.space_deleted
                                        : R.string.space_deleted_reboot, uid));
                        load();
                    }
                });
            }
        }).start();
    }

    // ── 批量 ────────────────────────────────────────────────────────

    private void toggleSelectMode() {
        selectMode = !selectMode;
        if (!selectMode) selected.clear();
        btnSelect.setText(selectMode ? R.string.sp_cancel : R.string.sp_select);
        bar.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        render();
    }

    private void toggleSelectAll() {
        if (scan == null) return;
        boolean all = selected.size() >= scan.uids.size();
        selected.clear();
        if (!all) {
            // 只选我们自己建的：系统自带空间的批量删除风险太高，单个操作时才放行
            for (int i = 0; i < scan.uids.size(); i++) {
                if (!scan.foreign.get(i).booleanValue()) selected.add(scan.uids.get(i));
            }
        }
        render();
    }

    private void removeSelected() {
        if (selected.isEmpty()) {
            toast(getString(R.string.sp_none_selected));
            return;
        }
        final List<Integer> targets = new ArrayList<Integer>(selected);
        Theme.dialog(this)
                .setTitle(R.string.sp_del_title)
                .setMessage(getString(R.string.sp_del_msg, targets.size()))
                .setPositiveButton(R.string.delete_space, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        batchRemove(targets);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 串行删：并行跑 remove-user 会互相锁 UserManager，反而更慢 */
    private void batchRemove(final List<Integer> targets) {
        bar.setVisibility(View.GONE);
        summary.setText(getString(R.string.sp_deleting, 0, targets.size()));
        new Thread(new Runnable() {
            public void run() {
                int done = 0, ok = 0;
                for (Integer u : targets) {
                    final int uid = u.intValue();
                    String r = Shell.exec(CloneStore.CLI + " remove " + uid);
                    if (!r.contains("REFUSED")
                            && !CloneStore.listSpaceUids().contains(u)) {
                        getSharedPreferences("clone", MODE_PRIVATE).edit()
                                .remove("space_pkgs_" + uid).apply();
                        CloneStore.unmarkOurs(uid);
                        MainActivity.unpinShortcut(SpacesActivity.this, uid, null);
                        ok++;
                    }
                    done++;
                    final int fd = done;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            summary.setText(getString(R.string.sp_deleting, fd, targets.size()));
                        }
                    });
                }
                final int fok = ok, fn = targets.size();
                CloneStore.dirty = true;
                runOnUiThread(new Runnable() {
                    public void run() {
                        selected.clear();
                        selectMode = false;
                        btnSelect.setText(R.string.sp_select);
                        toast(getString(R.string.sp_deleted_n, fok, fn));
                        load();
                    }
                });
            }
        }).start();
    }

    /**
     * 批量备份：把选中空间里的全部三方应用交给批量备份页跑同一套流程。
     * 包枚举要在后台做（每个空间一次 query-activities），别卡 UI。
     */
    private void backupSelected() {
        if (selected.isEmpty()) {
            toast(getString(R.string.sp_none_selected));
            return;
        }
        final List<Integer> targets = new ArrayList<Integer>(selected);
        summary.setText(R.string.loading);
        new Thread(new Runnable() {
            public void run() {
                final List<String> items = new ArrayList<String>();
                for (Integer u : targets) {
                    int uid = u.intValue();
                    for (String p : CloneStore.launchablePkgs(SpacesActivity.this, uid)) {
                        if (p.equals(getPackageName())) continue;
                        items.add(uid + "|" + p);
                    }
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (items.isEmpty()) {
                            toast(getString(R.string.backup_batch_none));
                            renderSummary();
                            return;
                        }
                        Intent it = new Intent(SpacesActivity.this, BatchRunActivity.class);
                        it.putExtra(BatchRunActivity.EXTRA_MODE, BatchRunActivity.MODE_BACKUP);
                        it.putExtra(BatchRunActivity.EXTRA_ITEMS,
                                items.toArray(new String[items.size()]));
                        startActivity(it);
                        renderSummary();
                    }
                });
            }
        }).start();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
