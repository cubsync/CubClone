package com.cubsync.cubclone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 桌面快捷方式的落地页：透明 Activity，收到 uid + pkg 后
 * 直接在被分身的用户里启动目标应用，然后自己消失。
 *
 * ⚠ 必须防重入：跨用户启动会把本 Activity 停掉，noHistory 又会让它被销毁，
 * 切回主用户时任务被重建 → onCreate 再跑一遍 → 又切去分身用户，
 * 表现就是「点快捷方式后那个应用一直循环打开」（ESP / 一加都复现）。
 * 所以同一个 uid+pkg 在 5 秒内只认一次，并且用完把整个任务删掉。
 */
public class LaunchProxyActivity extends Activity {

    private static String lastKey;
    private static long lastAt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent it = getIntent();
        final int uid = it.getIntExtra("uid", -1);
        final String pkg = it.getStringExtra("pkg");
        if (uid < 0 || pkg == null || pkg.equals(getPackageName())) {
            done();
            return;
        }
        final String key = uid + ":" + pkg;
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastAt < 5000) {   // 重入：直接走，不再启动
            done();
            return;
        }
        lastKey = key;
        lastAt = now;
        new Thread(new Runnable() {
            public void run() {
                Launcher.launch(LaunchProxyActivity.this, uid, pkg);
                runOnUiThread(new Runnable() {
                    public void run() { done(); }
                });
            }
        }).start();
    }

    /** 立刻收掉，连任务一起删 —— 留着任务，桌面/系统会把本 Activity 重建一次 */
    private void done() {
        if (android.os.Build.VERSION.SDK_INT >= 21) finishAndRemoveTask();
        else finish();
    }
}
