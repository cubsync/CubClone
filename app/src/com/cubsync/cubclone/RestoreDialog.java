package com.cubsync.cubclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 恢复选项：恢复到哪个空间 + 恢复哪些内容。单条恢复和批量恢复共用这一个对话框，
 * 免得两处各写一套、以后改一处忘另一处。
 */
public final class RestoreDialog {

    public interface OnOk {
        void onOk(int targetUser, int parts);
    }

    private RestoreDialog() {}

    /**
     * @param backupUser 这份备份原本属于哪个空间（-1 表示「按备份里的空间」，不指定）
     */
    public static void show(final Activity a, final int backupUser, final int count,
                            final OnOk cb) {
        final int[] target = {backupUser};
        final int[] parts = {AppData.PART_CE | AppData.PART_DE | AppData.PART_APK
                | AppData.PART_PERM};

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 20);
        box.setPadding(pad, dp(a, 6), pad, 0);

        TextView hint = new TextView(a);
        hint.setTextSize(13);
        hint.setText(a.getString(R.string.restore_msg_count, count));
        box.addView(hint);

        final TextView space = new TextView(a);
        space.setTextSize(15);
        space.setPadding(0, dp(a, 14), 0, dp(a, 6));
        space.setText(spaceText(a, backupUser, target[0]));
        space.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final List<Integer> us = new ArrayList<Integer>();
                if (backupUser >= 0) us.add(Integer.valueOf(-1));
                us.add(Integer.valueOf(0));
                for (Integer u : CloneStore.listSpaceUids()) {
                    if (u.intValue() != 0 && !us.contains(u)) us.add(u);
                }
                String[] items = new String[us.size()];
                for (int i = 0; i < us.size(); i++) {
                    int u = us.get(i).intValue();
                    items[i] = u < 0 ? a.getString(R.string.restore_space_orig)
                            : spaceName(a, u);
                }
                int cur = us.indexOf(Integer.valueOf(target[0]));
                new AlertDialog.Builder(a)
                        .setTitle(R.string.restore_space)
                        .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                target[0] = us.get(which).intValue();
                                space.setText(spaceText(a, backupUser, target[0]));
                            }
                        })
                        .show();
            }
        });
        box.addView(space);

        final CheckBox ce = check(a, R.string.restore_part_ce);
        final CheckBox de = check(a, R.string.restore_part_de);
        final CheckBox apk = check(a, R.string.restore_part_apk);
        final CheckBox perm = check(a, R.string.restore_part_perm);
        box.addView(ce);
        box.addView(de);
        box.addView(apk);
        box.addView(perm);

        new AlertDialog.Builder(a)
                .setTitle(R.string.restore_options)
                .setView(box)
                .setPositiveButton(R.string.backup_restore, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int p = 0;
                        if (ce.isChecked()) p |= AppData.PART_CE;
                        if (de.isChecked()) p |= AppData.PART_DE;
                        if (apk.isChecked()) p |= AppData.PART_APK;
                        if (perm.isChecked()) p |= AppData.PART_PERM;
                        cb.onOk(target[0], p == 0 ? AppData.PART_CE : p);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static CheckBox check(Activity a, int text) {
        CheckBox c = new CheckBox(a);
        c.setText(text);
        c.setTextSize(15);
        c.setChecked(true);
        c.setPadding(0, dp(a, 4), 0, dp(a, 4));
        return c;
    }

    private static String spaceText(Activity a, int backupUser, int target) {
        String n = target < 0 ? a.getString(R.string.restore_space_orig)
                : spaceName(a, target);
        return a.getString(R.string.restore_space_row, n);
    }

    static String spaceName(Context c, int u) {
        String n = u == 0 ? c.getString(R.string.space_main) : CloneStore.nameOf(c, u);
        return n + " (" + u + ")";
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
