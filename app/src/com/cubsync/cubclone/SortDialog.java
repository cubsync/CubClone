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
 * 排序对话框：上面一组选「按什么排」，下面一组选「升序 / 降序」，点任一项立即生效。
 *
 * 别在工具栏外面挂一个翻方向的箭头——方向属于排序的一部分，跟维度一起在同一张对话框里选。
 * 参考系统文件管理器、Google Files 与 MaterialFiles 的做法：一个入口，进来两组单选。
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
        int pad = dp(a, 20);
        box.setPadding(pad, dp(a, 6), pad, dp(a, 2));

        box.addView(caption(a, a.getString(R.string.sort_by), sec));
        final RadioGroup dims = new RadioGroup(a);
        for (int i = 0; i < labels.length; i++) {
            dims.addView(radio(a, labels[i], fg, accent), i);
        }
        box.addView(dims);

        box.addView(caption(a, a.getString(R.string.sort_order), sec));
        final RadioGroup order = new RadioGroup(a);
        order.addView(radio(a, a.getString(R.string.sort_dir_asc), fg, accent), 0);
        order.addView(radio(a, a.getString(R.string.sort_dir_desc), fg, accent), 1);
        box.addView(order);

        // 选中态必须在注册监听前设好，否则一打开就当作用户点了
        ((RadioButton) dims.getChildAt(curIndex)).setChecked(true);
        ((RadioButton) order.getChildAt(curDesc ? 1 : 0)).setChecked(true);

        final AlertDialog[] holder = new AlertDialog[1];
        AlertDialog.Builder b = Theme.dialog(a)
                .setTitle(titleRes)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null);
        if (neutralRes != 0) b.setNeutralButton(neutralRes, neutral);
        AlertDialog dlg = b.create();
        holder[0] = dlg;

        dims.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) {
                int idx = indexOf(g, id);
                if (idx < 0) return;
                holder[0].dismiss();
                cb.onPick(idx, currentDesc(order));
            }
        });
        order.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) {
                int idx = indexOf(dims, checkedId(dims, curIndex));
                if (idx < 0) return;
                holder[0].dismiss();
                cb.onPick(idx, id == ((RadioButton) g.getChildAt(1)).getId());
            }
        });
        dlg.show();
    }

    private static boolean currentDesc(RadioGroup order) {
        View v = order.getChildAt(1);
        return v != null && v.getId() == order.getCheckedRadioButtonId();
    }

    private static int checkedId(RadioGroup g, int fallback) {
        int id = g.getCheckedRadioButtonId();
        if (id != -1) return id;
        View v = g.getChildAt(fallback);
        return v == null ? -1 : v.getId();
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
        rb.setPadding(dp(a, 4), dp(a, 10), dp(a, 4), dp(a, 10));
        return rb;
    }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }
}
