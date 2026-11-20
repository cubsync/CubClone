package com.cubsync.cubclone;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用数据克隆：把一个用户里的应用私有数据复制到另一个用户（主空间 ↔ 分身）。
 *
 * 只能 root：数据在 /data/user/&lt;N&gt;/&lt;包&gt;，属主是各自用户的应用 uid，
 * Shizuku 是 shell(2000) 身份，读不了也写不了。
 *
 * ⚠ 这里生成的脚本里**不能出现 $变量**：APatch 的 su 会把 -c 的字符串先 eval 一遍，
 * 变量会在外层被展开成空（实测 `su -c 'A=5; echo $A'` 输出空）。
 * 所以所有路径、uid 全部在 Java 侧拼成字面量。
 */
public final class AppData {

    private AppData() {}

    /**
     * @return "OK &lt;备份路径&gt;" 或 "ERR_xxx" / "ERR &lt;原文&gt;"
     */
    public static String copy(int srcUser, int dstUser, String pkg, int appId) {
        String src = "/data/user/" + srcUser + "/" + pkg;
        String dst = "/data/user/" + dstUser + "/" + pkg;
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String bak = dst + ".cubclone-bak-" + ts;
        long uid = (long) appId + (long) dstUser * 100000L;

        // 目标应用数据现在的 SELinux 上下文，必须在 mv 之前读（里面带本应用的 MLS 类别，
        // restorecon 只能给出没有类别的 system_data_file，那会让应用起不来）
        String ctx = contextOf(dstUser, pkg);

        StringBuilder s = new StringBuilder();
        s.append("am force-stop --user ").append(srcUser).append(" '").append(pkg).append("'\n");
        s.append("am force-stop --user ").append(dstUser).append(" '").append(pkg).append("'\n");
        s.append("[ -d '").append(src).append("' ] || { echo ERR_NO_SRC; exit 1; }\n");
        s.append("[ -d '").append(dst).append("' ] || { echo ERR_NO_DST; exit 1; }\n");
        // 锁屏后 CE 存储的密钥被驱逐，新建文件会 ENOKEY：先探一下再动手，
        // 否则 cp 到一半失败、回滚也可能跟着失败
        s.append("touch '").append(dst).append("/.cc-probe' 2>/dev/null || { echo ERR_LOCKED; exit 1; }\n");
        s.append("rm -f '").append(dst).append("/.cc-probe'\n");
        s.append("mv '").append(dst).append("' '").append(bak).append("' || { echo ERR_MV; exit 1; }\n");
        s.append("cp -a '").append(src).append("' '").append(dst).append("' || { ")
                .append("rm -rf '").append(dst).append("'; mv '").append(bak).append("' '")
                .append(dst).append("'; echo ERR_CP; exit 1; }\n");
        // 缓存没必要搬，白占空间
        s.append("rm -rf '").append(dst).append("/cache' '").append(dst).append("/code_cache'\n");
        s.append("chown -R ").append(uid).append(":").append(uid).append(" '").append(dst)
                .append("' || { echo ERR_CHOWN; exit 1; }\n");
        if (ctx.length() > 0) {
            s.append("chcon -R '").append(ctx).append("' '").append(dst)
                    .append("' || { echo ERR_CHCON; exit 1; }\n");
        } else {
            s.append("restorecon -R '").append(dst).append("' 2>/dev/null\n");
        }
        s.append("echo CUB_OK '").append(bak).append("'\n");

        String out = Shell.execWithRoot(s.toString());
        int i = out.indexOf("CUB_OK ");
        if (i >= 0) return "OK " + out.substring(i + 7).trim();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("ERR")) return t;
        }
        return "ERR " + out.trim();
    }

    /**
     * 取某个用户里该包应用数据的 SELinux 上下文。
     * 先读目标自己的数据目录；它不存在时退到 /data/user_de（同一应用、同一用户的类别集合相同）。
     * 读不到就返回空串，调用方退回 restorecon。
     */
    private static String contextOf(int user, String pkg) {
        String[] paths = {
                "/data/user/" + user + "/" + pkg,
                "/data/user_de/" + user + "/" + pkg,
        };
        for (String p : paths) {
            String out = Shell.execWithRoot("ls -Zd '" + p + "' 2>/dev/null");
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("u:object_r:[^\\s]+").matcher(out);
            if (m.find()) return m.group();
        }
        return "";
    }
}
