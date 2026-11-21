package com.cubsync.cubclone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 备份 / 恢复的通知：一条通知跟到底（进度 → 总结），点开回到过程页。
 *
 * 默认不响不震：建的是 IMPORTANCE_LOW 通道，并显式 setSound(null) + 关掉振动，
 * 用户改了系统设置也不会被应用这边覆盖成响铃。用 setOnlyAlertOnce，
 * 每次更新只换文字，不会再弹一次。
 */
public final class Notify {

    private static final String CH = "jobs";
    private static final int ID = 4001;

    private Notify() {}

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager m = nm(c);
        if (m == null || m.getNotificationChannel(CH) != null) return;
        NotificationChannel ch = new NotificationChannel(CH,
                c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.notif_channel_desc));
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setVibrationPattern(null);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        m.createNotificationChannel(ch);
    }

    /** 进度：total > 0 时画进度条，否则不定长 */
    @SuppressWarnings("deprecation")
    public static void progress(Context c, boolean restore, int done, int total,
                                String head, String sub) {
        channel(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        String title = head != null && head.length() > 0 ? head
                : c.getString(restore ? R.string.batch_restore_title : R.string.batch_backup_title);
        b.setSmallIcon(R.drawable.ic_stat_job)
                .setContentTitle(title)
                .setContentText(sub == null ? "" : sub)
                .setContentIntent(open(c))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setDefaults(0)
                .setPriority(Notification.PRIORITY_LOW);
        if (total > 0) b.setProgress(total, Math.min(done, total), false);
        else b.setProgress(0, 0, true);
        post(c, b.build());
    }

    /** 收尾：把同一条通知换成总结，不再常驻 */
    @SuppressWarnings("deprecation")
    public static void finish(Context c, boolean restore, String summary, boolean failed) {
        channel(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        String title = c.getString(restore ? R.string.batch_restore_title : R.string.batch_backup_title)
                + " · " + c.getString(failed ? R.string.notif_done_bad : R.string.notif_done_ok);
        b.setSmallIcon(R.drawable.ic_stat_job)
                .setContentTitle(title)
                .setContentText(summary == null ? "" : summary)
                .setStyle(new Notification.BigTextStyle().bigText(summary == null ? "" : summary))
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setDefaults(0)
                .setPriority(Notification.PRIORITY_LOW);
        post(c, b.build());
    }

    public static void cancel(Context c) {
        NotificationManager m = nm(c);
        if (m != null) m.cancel(ID);
    }

    /** 点通知 = 回到过程页（不带参数时它就是「挂到当前任务上看」） */
    private static PendingIntent open(Context c) {
        Intent it = new Intent(c, BatchRunActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 0, it, flags);
    }

    private static void post(Context c, Notification n) {
        NotificationManager m = nm(c);
        if (m == null) return;
        try {
            m.notify(ID, n);
        } catch (Throwable ignored) {   // 没给通知权限就静默算了，不影响任务本身
        }
    }
}
