package com.cubsync.cubclone;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

/** 统一提权执行入口：root 优先，Shizuku 兜底 */
public final class Shell {

    public static boolean rootOk;
    public static boolean shizukuOk;

    private Shell() {}

    public static boolean ready() {
        return rootOk || shizukuOk;
    }

    public static String exec(String cmd) {
        if (rootOk) return raw("su", "-c", cmd);
        if (shizukuOk) return shizuku(cmd);
        return "ERR 无 root 且未授权 Shizuku";
    }

    public static boolean checkRoot() {
        String out = raw("su", "-c", "id");
        rootOk = out.contains("uid=0");
        Root.setAvailable(rootOk);
        // 出问题时这一行能直接看出是「su 没找到」还是「授权被拒」
        android.util.Log.i("CubClone", "checkRoot=" + rootOk + " out=" + out.trim());
        return rootOk;
    }

    /**
     * 只有 root 能做的操作（改名 setUserName 要 MANAGE_USERS，shell 身份必被拒）：
     * 执行前实时探一次 root，有就用，没有如实返回失败。不做轮询/节流。
     */
    public static String execWithRoot(String cmd) {
        if (checkRoot()) return raw("su", "-c", cmd);
        return "ERR 需要 root";
    }

    /**
     * 执行一段多行脚本。
     * ⚠ 直接 `su -c "<多行脚本>"` 在部分管理器上会被重新拼参数，引号/换行可能变形
     * （实测同样的脚本 adb 能跑、App 里 [ -d ] 却判假）。改成 base64 落盘再 sh，
     * 命令行只剩 base64 字符，不存在任何需要转义的东西。
     */
    public static String execScript(String script) {
        if (!checkRoot()) return "ERR 需要 root";
        String b64;
        try {
            b64 = android.util.Base64.encodeToString(
                    script.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return "ERR " + t;
        }
        // Android 15 起每个应用有自己的 app data isolation：App 进程的 namespace 里
        // /data/user 只有自己那个用户、/data/data 只有自己那个包。直接 sh 会看不到
        // 别的用户/别的应用（实测 [ -d /data/user/11/... ] 判假）。
        // 所以进 init 的 mount namespace 再执行，那里能看到全部用户。
        String cmd = "echo '" + b64 + "' | base64 -d > /data/local/tmp/.cc-run.sh"
                + " && chmod 700 /data/local/tmp/.cc-run.sh"
                + " && ( if command -v nsenter >/dev/null 2>&1"
                + " && nsenter -t 1 -m -- true >/dev/null 2>&1;"
                + " then nsenter -t 1 -m -- sh /data/local/tmp/.cc-run.sh;"
                + " else sh /data/local/tmp/.cc-run.sh; fi )"
                + "; rm -f /data/local/tmp/.cc-run.sh";
        return raw("su", "-c", cmd);
    }

    private static String shizuku(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            moe.shizuku.server.IShizukuService svc =
                    moe.shizuku.server.IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (svc == null) return "ERR Shizuku 服务为空";
            moe.shizuku.server.IRemoteProcess p =
                    svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            if (p == null) return "ERR newProcess 返回空";
            drain(new android.os.ParcelFileDescriptor.AutoCloseInputStream(p.getInputStream()), sb);
            drain(new android.os.ParcelFileDescriptor.AutoCloseInputStream(p.getErrorStream()), sb);
            p.waitFor();
        } catch (Throwable t) {
            sb.append("ERR ").append(t);
        }
        return sb.toString();
    }

    private static void drain(InputStream is, StringBuilder sb) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
        } catch (Throwable ignored) {
        }
    }

    public static String raw(String... argv) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(argv);
            drain(p.getInputStream(), sb);
            drain(p.getErrorStream(), sb);
            p.waitFor();
        } catch (Throwable t) {
            sb.append("ERR ").append(t);
        }
        return sb.toString();
    }

    /**
     * 把一段字节写到设备路径。
     * 走管道而不是 cp：Shizuku 是 shell 身份，读不到 App 私有目录、
     * 也读不到 /sdcard/Android/data/<pkg>（A11+ 限制），但能往 stdin 灌。
     */
    public static String write(String path, byte[] data) {
        final String cmd = "cat > '" + path + "' && chmod 644 '" + path + "'";
        try {
            if (rootOk) {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                java.io.OutputStream os = p.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
                StringBuilder sb = new StringBuilder();
                drain(p.getErrorStream(), sb);
                p.waitFor();
                return sb.toString().trim();
            }
            if (shizukuOk) {
                moe.shizuku.server.IShizukuService svc =
                        moe.shizuku.server.IShizukuService.Stub.asInterface(Shizuku.getBinder());
                if (svc == null) return "ERR Shizuku 服务为空";
                moe.shizuku.server.IRemoteProcess p =
                        svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
                if (p == null) return "ERR newProcess 返回空";
                java.io.OutputStream os =
                        new android.os.ParcelFileDescriptor.AutoCloseOutputStream(p.getOutputStream());
                os.write(data);
                os.flush();
                os.close();
                StringBuilder sb = new StringBuilder();
                drain(new android.os.ParcelFileDescriptor.AutoCloseInputStream(p.getErrorStream()), sb);
                p.waitFor();
                return sb.toString().trim();
            }
        } catch (Throwable t) {
            return "ERR " + t;
        }
        return "ERR 无 root 且未授权 Shizuku";
    }
}
