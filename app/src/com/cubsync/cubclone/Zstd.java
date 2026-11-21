package com.cubsync.cubclone;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 内置 zstd（NDK 编译的单文件 CLI，放 assets/zstd-&lt;abi&gt;）。
 *
 * 资源名是扁平的 zstd-&lt;abi&gt;：aapt2 在 Windows 上会把子目录写成反斜杠
 * （assets/zstdrm64-v8a\zstd），设备上 getAssets().open 就找不到。
 *
 * 为什么要由 root 释放到 /data/local/tmp 再执行：Android 10 起 SELinux 不允许
 * 应用在自己的数据目录里执行文件（neverallow untrusted_app app_data_file:file exec），
 * 本机 /system_ext/bin/zstd 又实测损坏（任何调用都报 Unsupported parameter）。
 * 所以「自带一份、放到 root 能执行的地方」是唯一既通用又不依赖用户装东西的做法。
 */
public final class Zstd {

    private static final String DIR = "/data/local/tmp/cubclone";
    private static final String DST = DIR + "/zstd";

    /** 可用路径；null = 本机用不了 */
    private static String bin;
    private static boolean tried;

    private Zstd() {}

    public static synchronized String path() {
        if (tried) return bin;
        tried = true;
        if (works(DST)) {
            bin = DST;
            return bin;
        }
        Context c = CloneStore.appCtx;
        if (c == null) return null;
        byte[] data = assetFor(c);
        if (data == null) return null;
        Shell.execQuick("mkdir -p " + DIR);
        String err = Shell.write(DST, data);
        Shell.execQuick("chmod 755 " + DST);
        android.util.Log.i("CubClone", "zstd extract err=" + err.trim());
        if (works(DST)) bin = DST;
        return bin;
    }

    /** 二次确认：真的能跑才算可用（放进去但架构不对/缺库都会在这里挂） */
    private static boolean works(String p) {
        if (!Shell.ready()) return false;
        return Shell.execQuick(p + " --version 2>/dev/null").contains("Zstandard");
    }

    /** 按设备 ABI 取对应那份；本机架构没打进去就返回 null */
    private static byte[] assetFor(Context c) {
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            try {
                InputStream in = c.getAssets().open("zstd-" + abi);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(800 * 1024);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                return bos.toByteArray();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** zstd 命令行前缀：压缩用 {@code cmd(level, thr)}，解压用 {@code d} */
    public static String d() {
        String p = path();
        return p == null ? null : p + " -d -q -c";
    }
}
