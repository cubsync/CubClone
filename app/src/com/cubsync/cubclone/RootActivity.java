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

    private LinearLayout root;
    private LinearLayout header;
    private TextView title;
    private TextView status;
    private TextView secTitle;
    private LinearLayout card;
    private TextView masterLabel, masterHint;
    private TextView cloneLabel, cloneHint;
    private TextView backupLabel, backupHint;
    private Switch swMaster, swClone, swBackup;
    private ImageButton btnBack;

    private int fg = 0xFF1B1B1F;
    private int sec = 0xFF5F6368;

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
        swMaster = findViewById(R.id.rt_master);
        swClone = findViewById(R.id.rt_clone);
        swBackup = findViewById(R.id.rt_backup);
        btnBack = findViewById(R.id.rt_back);

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

        applyChrome();
        applyGate();
        probe();
    }

    protected void onResume() {
        super.onResume();
        applyChrome();
    }

    /** 总开关关掉时，下面两个单项一起置灰但保留原值（重新打开总开关就恢复） */
    private void applyGate() {
        boolean on = swMaster.isChecked();
        card.setAlpha(on ? 1f : 0.45f);
        swClone.setEnabled(on);
        swBackup.setEnabled(on);
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

        root.setBackgroundColor(bg);
        header.setBackgroundColor(bg);
        title.setTextColor(fg);
        btnBack.setColorFilter(fg);
        status.setTextColor(sec);
        secTitle.setTextColor(sec);
        masterLabel.setTextColor(fg);
        cloneLabel.setTextColor(fg);
        backupLabel.setTextColor(fg);
        masterHint.setTextColor(sec);
        cloneHint.setTextColor(sec);
        backupHint.setTextColor(sec);
    }
}
