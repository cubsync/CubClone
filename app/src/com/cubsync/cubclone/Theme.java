package com.cubsync.cubclone;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

/**
 * 色板与自建控件的唯一着色入口。
 *
 * 本应用的页面全是代码建的 UI，背景色自己按深浅色算，但系统主题（Theme.DeviceDefault）
 * 给 Button / CheckBox 的默认配色**不会跟着这个判断走**：背景画深了，系统按钮还是原来的
 * 黑字/深色方框，就成了看不见的按钮（11-22 批量删除页就是这样）。
 * 所以凡是自建控件都从这里取色，别再各自写 0xFF 常数。
 */
final class Theme {

    /** 与 SettingsActivity 的取值一致：0 跟随系统、1 深色、2 浅色 */
    static final int AUTO = 0, DARK = 1, LIGHT = 2;

    static final int BG_LIGHT = 0xFFFDFDFE;
    static final int BG_NIGHT = 0xFF121317;
    static final int BG_AMOLED = 0xFF000000;

    static final int LINE_LIGHT = 0xFFE3E3E8;
    static final int LINE_NIGHT = 0xFF2C2D31;
    static final int LINE_AMOLED = 0xFF1C1C1C;

    static final int FG_LIGHT = 0xFF1B1B1F;
    static final int FG_NIGHT = 0xFFEDEDED;

    static final int SEC_LIGHT = 0xFF5F6368;
    static final int SEC_NIGHT = 0xFF9AA0A6;
    static final int SEC_AMOLED = 0xFF8A8F96;

    static final int ACCENT_LIGHT = 0xFF0B57D0;
    static final int ACCENT_NIGHT = 0xFFA8C7FA;

    static final int GREEN_LIGHT = 0xFF2E7D32;
    static final int GREEN_NIGHT = 0xFF7BC67E;
    static final int RED_LIGHT = 0xFFC62828;
    static final int RED_NIGHT = 0xFFFF8A80;

    /** 卡片 / 列表行底色：比页面背景亮一档，深色下才有层次 */
    static final int CARD_LIGHT = 0xFFF3F3F6;
    static final int CARD_NIGHT = 0xFF1E1F22;
    static final int CARD_AMOLED = 0xFF0C0C0C;

    /** 次要按钮（浅蓝底 + 蓝字），用于「新建 / 刷新 / 安装」这类非主操作 */
    static final int CHIP_BG_LIGHT = 0xFFD3E3FD;
    static final int CHIP_BG_NIGHT = 0xFF1F3A5F;
    static final int CHIP_BG_AMOLED = 0xFF16283D;

    /** 警告橙与「正常」青绿，存储检测那类状态行用 */
    static final int WARN = 0xFFEF6C00;
    static final int TEAL_LIGHT = 0xFF0F8F88;
    static final int TEAL_NIGHT = 0xFF35C1B8;

    /** 列表行里的强调底色（比卡片再浅一档），把开关行和背景分开 */
    static final int ROW_LIGHT = 0xFFEFEFF1;
    static final int ROW_NIGHT = 0xFF2A2B2E;
    static final int ROW_AMOLED = 0xFF141414;

    /** 被隐藏 / 不可用的条目文字，比次要色再淡一档 */
    static final int DIM_LIGHT = 0xFFA0A0A0;
    static final int DIM_NIGHT = 0xFF7A7A7A;

    /** 分身角标配色，按序号轮换 */
    static final int[] BADGE = {
            0xFF34A853, 0xFFEA8600, 0xFF9334E6, 0xFF1A73E8, 0xFFE52592
    };

    /** 角标上的文字：不管深浅都画白 */
    static final int ON_BADGE = 0xFFFFFFFF;
    /** 离屏位图画布的底：先铺白再缩放，深色下图标不变黑 */
    static final int CANVAS_WHITE = 0xFFFFFFFF;

    private Theme() { }

    /**
     * 是否深色：以用户在设置里选的模式为准，跟随系统时再看系统夜间模式。
     * 注意取值是 1=深色、2=浅色（以前备份页/过程页写反了，选浅色反而画深色）。
     */
    static boolean isNight(Context c) {
        int t = sp(c).getInt("theme", AUTO);
        if (t == DARK) return true;
        if (t == LIGHT) return false;
        return (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 纯黑只对夜间生效；白天无视这个开关 */
    static boolean isBlack(Context c) {
        if (!isNight(c)) return false;
        return sp(c).getBoolean("amoled", false);
    }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("clone", Context.MODE_PRIVATE);
    }

    static int bg(Context c) {
        if (isBlack(c)) return BG_AMOLED;
        return isNight(c) ? BG_NIGHT : BG_LIGHT;
    }

    static int line(Context c) {
        if (isBlack(c)) return LINE_AMOLED;
        return isNight(c) ? LINE_NIGHT : LINE_LIGHT;
    }

    static int fg(Context c) { return isNight(c) ? FG_NIGHT : FG_LIGHT; }

    static int sec(Context c) {
        if (isBlack(c)) return SEC_AMOLED;
        return isNight(c) ? SEC_NIGHT : SEC_LIGHT;
    }

    static int accent(Context c) { return isNight(c) ? ACCENT_NIGHT : ACCENT_LIGHT; }

    static int green(Context c) { return isNight(c) ? GREEN_NIGHT : GREEN_LIGHT; }

    static int red(Context c) { return isNight(c) ? RED_NIGHT : RED_LIGHT; }

    static int card(Context c) {
        if (isBlack(c)) return CARD_AMOLED;
        return isNight(c) ? CARD_NIGHT : CARD_LIGHT;
    }

    static int chipBg(Context c) {
        if (isBlack(c)) return CHIP_BG_AMOLED;
        return isNight(c) ? CHIP_BG_NIGHT : CHIP_BG_LIGHT;
    }

    /** 次要按钮的文字色：就是强调色，单独留个名字让调用点读起来对得上 */
    static int chipFg(Context c) { return accent(c); }

    static int warn(Context c) { return WARN; }

    static int teal(Context c) { return isNight(c) ? TEAL_NIGHT : TEAL_LIGHT; }

    static int row(Context c) {
        if (isBlack(c)) return ROW_AMOLED;
        return isNight(c) ? ROW_NIGHT : ROW_LIGHT;
    }

    static int dim(Context c) { return isNight(c) ? DIM_NIGHT : DIM_LIGHT; }

    /**
     * 对话框统一入口。
     *
     * Activity 用的是 `Theme.DeviceDefault.NoActionBar`（跟系统深浅），而页面背景是按应用内的
     * 「跟随/深色/浅色」画的。两者不一致时，对话框里的自绘控件（用 Theme.fg/sec 上色）会撞上
     * 系统给对话框的底色：应用选深色、系统是浅色 → 白底浅字；反过来就是黑底黑字。
     * 所以对话框必须跟应用同一个判断走，一律从这里建。
     */
    static AlertDialog.Builder dialog(Context c) {
        int style = isNight(c)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        return new AlertDialog.Builder(new android.view.ContextThemeWrapper(c, style));
    }

    // ── 控件 ────────────────────────────────────────────────────────

    /** 文字按钮（顶部工具条那类）：只有文字，点一下有水波纹 */
    static void chip(TextView v, int color) {
        v.setTextColor(color);
        int bg = ripple(v.getContext());
        if (bg != 0) v.setBackgroundResource(bg); else v.setBackground(null);
        v.setPadding(dp(v, 12), dp(v, 8), dp(v, 12), dp(v, 8));
        v.setAllCaps(false);
    }

    /** 复选框：方框跟随强调色，文字跟随正文色 */
    static void check(CheckBox c, Context ctx) {
        c.setButtonTintList(ColorStateList.valueOf(accent(ctx)));
        c.setTextColor(fg(ctx));
    }

    /** 主操作按钮：填充强调色、白（或深色）字；disabled 时整体转灰 */
    static void primary(Button b, Context ctx) {
        int a = accent(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(b, 10));
        d.setColor(a);
        b.setBackground(d);
        // 夜间强调色是浅蓝，白字看不清，用深色字
        b.setTextColor(isNight(ctx) ? 0xFF0A2540 : 0xFFFFFFFF);
        b.setAllCaps(false);
    }

    static void disabledPrimary(Button b, Context ctx) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(b, 10));
        d.setColor(line(ctx));
        b.setBackground(d);
        b.setTextColor(sec(ctx));
    }

    /**
     * 水波纹背景的资源 id。
     * ⚠ 不能把 `android.R.attr.selectableItemBackground` 直接塞给 setBackgroundResource——
     * 那是属性 id（0x0101030e），不是 drawable 资源 id，运行时会 Resources$NotFoundException
     * 直接闪退（aapt2 编译期查不出来）。必须先从当前主题 resolve 出 resourceId。
     */
    static int ripple(Context c) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                && tv.resourceId != 0) {
            return tv.resourceId;
        }
        return 0;
    }

    static int dp(android.view.View v, int n) {
        return Math.round(n * v.getResources().getDisplayMetrics().density);
    }
}
