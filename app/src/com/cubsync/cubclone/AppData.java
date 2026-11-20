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

        android.util.Log.i("CubClone", "SCRIPT>>>\n" + s.toString() + "\n<<<");
        String out = Shell.execScript(s.toString());
        android.util.Log.i("CubClone", "copy " + pkg + " " + srcUser + "->" + dstUser
                + " out=" + out.trim());
        int i = out.indexOf("CUB_OK ");
        if (i >= 0) return "OK " + out.substring(i + 7).trim();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("ERR")) return t;
        }
        return "ERR " + out.trim();
    }

    /**
     * 把源用户里「用户已授予的」运行时权限、以及两个特殊授权同步到目标用户。
     *
     * 不直接改 runtime-permissions.xml：system_server 的内存态才是权威，改文件要么被盖回去、
     * 要么得重启才生效。pm grant / appops set 是官方入口，立即生效、重启不丢。
     *
     * @return 实际同步成功的项数
     */
    public static int syncPermissions(int srcUser, int dstUser, String pkg) {
        StringBuilder s = new StringBuilder();
        int want = 0;
        for (String p : grantedRuntimePerms(srcUser, pkg)) {
            s.append("pm grant --user ").append(dstUser).append(" '").append(pkg)
                    .append("' '").append(p).append("' >/dev/null 2>&1 && echo PG\n");
            want++;
        }
        for (String op : grantedAppOps(srcUser, pkg)) {
            s.append("appops set --user ").append(dstUser).append(" '").append(pkg)
                    .append("' ").append(op).append(" allow >/dev/null 2>&1 && echo PG\n");
            want++;
        }
        if (want == 0) return 0;
        String out = Shell.execWithRoot(s.toString());
        int n = 0;
        for (String line : out.split("\n")) {
            if (line.trim().equals("PG")) n++;
        }
        return n;
    }

    /** 读 dumpsys package 里源用户那一段的 runtime permissions，返回 granted=true 的权限名 */
    private static java.util.List<String> grantedRuntimePerms(int srcUser, String pkg) {
        java.util.List<String> r = new java.util.ArrayList<String>();
        String dump = Shell.execWithRoot("dumpsys package '" + pkg + "' 2>/dev/null");
        boolean inUser = false, inPerms = false;
        for (String line : dump.split("\n")) {
            if (line.startsWith("    User ")) {
                String rest = line.substring(9);
                int c = rest.indexOf(':');
                String u = c > 0 ? rest.substring(0, c).trim() : "";
                inUser = u.equals(String.valueOf(srcUser));
                inPerms = false;
                continue;
            }
            if (!inUser) continue;
            String t = line.trim();
            if (t.startsWith("runtime permissions:")) { inPerms = true; continue; }
            if (!inPerms) continue;
            if (line.length() > 0 && !line.startsWith("        ")) { inPerms = false; continue; }
            int g = t.indexOf(": granted=true");
            if (g > 0) r.add(t.substring(0, g));
        }
        return r;
    }

    /**
     * 只同步用户能在界面上点到的两个特殊授权：悬浮窗、所有文件访问。
     * 其它 appops 由系统按权限自动维护，照搬会把别的包的默认值也带过来。
     */
    private static java.util.List<String> grantedAppOps(int user, String pkg) {
        String[] wanted = {"SYSTEM_ALERT_WINDOW", "MANAGE_EXTERNAL_STORAGE"};
        String out = Shell.execWithRoot("appops get --user " + user + " '" + pkg + "' 2>/dev/null");
        java.util.Map<String, String> last = new java.util.HashMap<String, String>();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Uid mode:")) t = t.substring(9).trim();
            int c = t.indexOf(':');
            if (c <= 0) continue;
            String op = t.substring(0, c).trim();
            String rest = t.substring(c + 1).trim();
            int sc = rest.indexOf(';');
            last.put(op, (sc >= 0 ? rest.substring(0, sc) : rest).trim());
        }
        java.util.List<String> r = new java.util.ArrayList<String>();
        for (String op : wanted) {
            String m = last.get(op);
            if ("allow".equals(m) || "foreground".equals(m)) r.add(op);
        }
        return r;
    }


    /**
     * 一个数据备份。两种形态：
     *   目录 /data/user/<user>/<pkg>.cubclone-bak-<时间戳>         algo = ""
     *   压缩包 …<时间戳>.tar[.zst|.gz|.bz2]                       algo = zst/gz/bz2/tar
     */
    public static final class Backup {
        public final int user;
        public final String pkg;
        public final String path;
        public final long sizeKb;
        public final String ts;
        public final String algo;

        Backup(int user, String pkg, String path, long sizeKb, String ts, String algo) {
            this.user = user;
            this.pkg = pkg;
            this.path = path;
            this.sizeKb = sizeKb;
            this.ts = ts;
            this.algo = algo;
        }

        public boolean packed() {
            return algo.length() > 0;
        }

        /** tar 列表/解包用的压缩标志：Z=zstd、z=gzip、j=bzip2、空=纯打包 */
        public String tarFlag() {
            if ("zst".equals(algo)) return "Z";
            if ("gz".equals(algo)) return "z";
            if ("bz2".equals(algo)) return "j";
            return "";
        }

        public String sizeText() {
            return sizeKb >= 1024 ? (sizeKb / 1024) + " GB" : sizeKb + " MB";
        }
    }

    /** 扫各空间下的备份目录；一次 du 拿到全部大小，不逐个进目录 */
    public static java.util.List<Backup> listBackups() {
        java.util.List<Backup> r = new java.util.ArrayList<Backup>();
        String out = Shell.execScript("du -sm /data/user/*/*.cubclone-bak-* 2>/dev/null\n");
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.length() == 0) continue;
            int tab = t.indexOf('\t');
            if (tab <= 0) continue;
            long kb;
            try {
                kb = Long.parseLong(t.substring(0, tab).trim());
            } catch (Throwable e) {
                continue;
            }
            String path = t.substring(tab + 1).trim();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            int k = name.indexOf(".cubclone-bak-");
            if (k <= 0) continue;
            String[] seg = path.split("/");
            int user;
            try {
                user = Integer.parseInt(seg[3]);
            } catch (Throwable e) {
                continue;
            }
            String rest = name.substring(k + 14);
            String algo = "";
            String[][] exts = {{".tar.zst", "zst"}, {".tar.gz", "gz"},
                    {".tar.bz2", "bz2"}, {".tar", "tar"}};
            for (String[] e : exts) {
                if (rest.endsWith(e[0])) {
                    algo = e[1];
                    rest = rest.substring(0, rest.length() - e[0].length());
                    break;
                }
            }
            r.add(new Backup(user, name.substring(0, k), path, kb, rest, algo));
        }
        return r;
    }

    /** 各算法的默认级别（与各自官方默认一致） */
    public static int defaultLevel(String algo) {
        if ("zst".equals(algo)) return 3;
        if ("gz".equals(algo)) return 6;
        if ("bz2".equals(algo)) return 9;
        return 0;
    }

    /** 某算法可选的级别 */
    public static int[] levelsOf(String algo) {
        if ("zst".equals(algo)) return new int[]{1, 3, 9, 19};
        if ("gz".equals(algo)) return new int[]{1, 6, 9};
        if ("bz2".equals(algo)) return new int[]{1, 9};
        return new int[0];
    }

    /**
     * 把目录形态的备份打包，可指定级别。
     * 用 `tar -cf - | <压缩器> -<级别>` 而不是 tar 的 -z/-Z/-j：
     * toybox tar 的选项不接受级别参数，管道才能真正给到压缩器。
     * 失败按 zstd → gzip → 纯 tar 降级（本机 zstd 二进制实测不可用）。
     * 打包并校验通过后才删原目录；任何一步失败都保留原样。
     * @return "OK <包路径> <实际算法>" 或 "ERR_xxx"
     */
    public static String compress(Backup b, String algo, int level) {
        if (b.packed()) return "ERR_ALREADY";
        String parent = b.path.substring(0, b.path.lastIndexOf('/'));
        String name = b.path.substring(b.path.lastIndexOf('/') + 1);
        int lv = level > 0 ? level : defaultLevel(algo);

        StringBuilder s = new StringBuilder();
        s.append("SRC='").append(b.path).append("'\n");
        s.append("P='").append(parent).append("'\n");
        s.append("N='").append(name).append("'\n");
        // 打一个包：$1=压缩器命令（空=纯打包）$2=输出文件 $3=算法名 $4=tar 列表标志
        s.append("pack() {\n");
        s.append("  O=$2; rm -f \"$O\"\n");
        s.append("  if [ -z \"$1\" ]; then\n");
        s.append("    tar -cf \"$O\" -C \"$P\" \"$N\" 2>/dev/null\n");
        s.append("  else\n");
        s.append("    tar -cf - -C \"$P\" \"$N\" 2>/dev/null | $1 > \"$O\" 2>/dev/null\n");
        s.append("  fi\n");
        s.append("  if [ -s \"$O\" ] && tar -t$4f \"$O\" >/dev/null 2>&1; then\n");
        s.append("    rm -rf \"$SRC\"; echo CUB_OK \"$O\" $3; return 0\n");
        s.append("  fi\n");
        s.append("  rm -f \"$O\"; return 1\n");
        s.append("}\n");
        if ("zst".equals(algo)) {
            s.append("command -v zstd >/dev/null 2>&1 && pack \"zstd -").append(lv)
                    .append(" -q -c\" \"$SRC.tar.zst\" zst Z && exit 0\n");
        }
        if ("zst".equals(algo) || "gz".equals(algo)) {
            s.append("pack \"gzip -").append("zst".equals(algo) ? String.valueOf(defaultLevel("gz")) : String.valueOf(lv))
                    .append("\" \"$SRC.tar.gz\" gz z && exit 0\n");
        }
        if ("bz2".equals(algo)) {
            s.append("pack \"bzip2 -").append(lv).append("\" \"$SRC.tar.bz2\" bz2 j && exit 0\n");
        }
        s.append("pack \"\" \"$SRC.tar\" tar \"\" && exit 0\n");
        s.append("echo ERR_PACK\n");

        String out = Shell.execScript(s.toString());
        int i = out.indexOf("CUB_OK ");
        if (i >= 0) return "OK " + out.substring(i + 7).trim().replace(' ', '|');
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("ERR")) return t;
        }
        return "ERR " + out.trim();
    }

    /**
     * 把备份换回原位；当前数据先改名成一份新备份，等于两个版本互换，不会丢。
     * @return "OK" 或 "ERR_xxx"
     */
    public static String restore(Backup b, int appId) {
        String dst = "/data/user/" + b.user + "/" + b.pkg;
        long uid = (long) appId + (long) b.user * 100000L;
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String ctx = contextOf(b.user, b.pkg);

        StringBuilder s = new StringBuilder();
        s.append("am force-stop --user ").append(b.user).append(" '").append(b.pkg).append("'\n");
        if (b.packed()) {
            // 压缩包：先解到临时目录，解完再换位，失败不动现有数据
            String tmp = "/data/local/tmp/cc-restore-" + ts;
            String inner = b.path.substring(b.path.lastIndexOf('/') + 1);
            inner = inner.substring(0, inner.indexOf(".tar"));
            s.append("TMP='").append(tmp).append("'\n");
            s.append("rm -rf \"$TMP\"; mkdir -p \"$TMP\" || { echo ERR_TMP; exit 1; }\n");
            s.append("tar -x").append(b.tarFlag()).append("f '").append(b.path)
                    .append("' -C \"$TMP\" || { rm -rf \"$TMP\"; echo ERR_UNPACK; exit 1; }\n");
            s.append("[ -d \"$TMP/").append(inner).append("\" ] || { rm -rf \"$TMP\"; echo ERR_UNPACK2; exit 1; }\n");
            s.append("[ -d '").append(dst).append("' ] && { mv '").append(dst).append("' '")
                    .append(dst).append(".cubclone-bak-").append(ts)
                    .append("' || { rm -rf \"$TMP\"; echo ERR_MV; exit 1; }; }\n");
            s.append("mv \"$TMP/").append(inner).append("\" '").append(dst).append("' || { echo ERR_MV2; exit 1; }\n");
            s.append("rm -rf \"$TMP\"\n");
        } else {
            s.append("[ -d '").append(b.path).append("' ] || { echo ERR_NO_BAK; exit 1; }\n");
            s.append("[ -d '").append(dst).append("' ] && { mv '").append(dst).append("' '")
                    .append(dst).append(".cubclone-bak-").append(ts)
                    .append("' || { echo ERR_MV; exit 1; }; }\n");
            s.append("mv '").append(b.path).append("' '").append(dst).append("' || { echo ERR_MV2; exit 1; }\n");
        }
        s.append("chown -R ").append(uid).append(":").append(uid).append(" '").append(dst).append("'\n");
        if (ctx.length() > 0) {
            s.append("chcon -R '").append(ctx).append("' '").append(dst).append("'\n");
        }
        s.append("echo CUB_OK\n");

        String out = Shell.execScript(s.toString());
        if (out.contains("CUB_OK")) return "OK";
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("ERR")) return t;
        }
        return "ERR " + out.trim();
    }

    /** 复制到 Download，普通文件管理器才拿得到 */
    public static String export(Backup b) {
        String dir = "/sdcard/Download/CubClone-backup";
        String ext = b.packed() ? b.path.substring(b.path.indexOf(".tar")) : "";
        String dst = dir + "/" + b.pkg + "-" + b.user + "-" + b.ts + ext;
        StringBuilder s = new StringBuilder();
        s.append("[ -e '").append(b.path).append("' ] || { echo ERR_NO_BAK; exit 1; }\n");
        s.append("mkdir -p '").append(dir).append("'\n");
        s.append("rm -rf '").append(dst).append("'\n");
        s.append("cp -a '").append(b.path).append("' '").append(dst).append("' || { echo ERR_CP; exit 1; }\n");
        s.append("echo CUB_OK '").append(dst).append("'\n");
        String out = Shell.execScript(s.toString());
        int i = out.indexOf("CUB_OK ");
        if (i >= 0) return "OK " + out.substring(i + 7).trim();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("ERR")) return t;
        }
        return "ERR " + out.trim();
    }

    public static String delete(Backup b) {
        String out = Shell.execScript("rm -rf '" + b.path + "' 2>&1 && echo CUB_OK\n");
        return out.contains("CUB_OK") ? "OK" : "ERR " + out.trim();
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
            String out = Shell.execScript("ls -Zd '" + p + "' 2>/dev/null\n");
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("u:object_r:[^\\s]+").matcher(out);
            if (m.find()) return m.group();
        }
        return "";
    }
}
