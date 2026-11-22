package com.cubsync.cubclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * 排序对话框：上面一组选「按什么排」，下面一组选「升序 / 降序」，点「确定」才生效。
 *
 * 两条硬要求：
 *  1) 每一行整行可点（撑满宽度 + 48dp 高 + 水波纹），不是只有圆点和文字能点；
 *  2) 选项只是选中，按「确定」才应用，按「取消」什么都不改。
 *
 * 方向属于排序的一部分，别在工具栏外面另挂一个箭头。
 */
final class SortDialog {

    interface OnPick {
        /** @param index 维度下标；@param desc true = 降序 */
        void onPick(int index, boolean desc);
    }

    private SortDialog() {}

    static void show(Activity a, int titleRes, String[] labels,
                     int curIndex, boolean curDesc, OnPick cb) {
        show(a, titleRes, labels, curIndex, curDesc, 0, null, cb);
    }

    /** 带一个次要按钮（比如批量删除页的「重新统计」） */
    static void show(final Activity a, int titleRes, final String[] labels,
                     final int curIndex, final boolean curDesc,
                     int neutralRes, DialogInterface.OnClickListener neutral, final OnPick cb) {
        final int fg = Theme.fg(a);
        final int sec = Theme.sec(a);
        final int accent = Theme.accent(a);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        // 两侧留白自己做：DeviceDefault 的对话框正文区没有内边距，交给它会贴边
        int side = dp(a, 20);
        box.setPadding(side, dp(a, 2), side, 0);

        box.addView(caption(a, a.getString(R.string.sort_by), sec));
        final RadioGroup dims = new RadioGroup(a);
        for (int i = 0; i < labels.length; i++) dims.addView(radio(a, labels[i], fg, accent), i);
        box.addView(dims);

        box.addView(caption(a, a.getString(R.string.sort_order), sec));
        final RadioGroup order = new RadioGroup(a);
        order.addView(radio(a, a.getString(R.string.sort_dir_asc), fg, accent), 0);
        order.addView(radio(a, a.getString(R.string.sort_dir_desc), fg, accent), 1);
        box.addView(order);

        // 选中态必须在注册监听前设好
        ((RadioButton) dims.getChildAt(curIndex)).setChecked(true);
        ((RadioButton) order.getChildAt(curDesc ? 1 : 0)).setChecked(true);

        AlertDialog.Builder b = Theme.dialog(a)
                .setTitle(titleRes)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int idx = indexOf(dims, dims.getCheckedRadioButtonId());
                        if (idx < 0) return;
                        cb.onPick(idx, currentDesc(order));
                    }
                });
        if (neutralRes != 0) b.setNeutralButton(neutralRes, neutral);
        AlertDialog dlg = b.create();
        dlg.show();
        // 别让它按屏幕宽度铺满：固定一个窄一点的宽度，右侧不留大片空框
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            int screen = a.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(dp(a, 300), (int) (screen * 0.82f)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static boolean currentDesc(RadioGroup order) {
        View v = order.getChildAt(1);
        return v != null && v.getId() == order.getCheckedRadioButtonId();
    }

    private static int indexOf(RadioGroup g, int id) {
        for (int i = 0; i < g.getChildCount(); i++) {
            if (g.getChildAt(i).getId() == id) return i;
        }
        return -1;
    }

    private static TextView caption(Activity a, String text, int color) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(color);
        tv.setPadding(0, dp(a, 12), 0, dp(a, 2));
        return tv;
    }

    private static RadioButton radio(Activity a, String text, int fg, int accent) {
        RadioButton rb = new RadioButton(a);
        rb.setId(View.generateViewId());
        rb.setText(text);
        rb.setTextSize(15);
        rb.setTextColor(fg);
        rb.setButtonTintList(ColorStateList.valueOf(accent));
        // 整行可点：宽度撑满、48dp 高、带点按反馈；否则框再大也只有文字那一小截能点
        rb.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 44)));
        rb.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int bg = Theme.ripple(a);
        if (bg != 0) rb.setBackgroundResource(bg); else rb.setBackground(null);
        return rb;
    }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }
}
