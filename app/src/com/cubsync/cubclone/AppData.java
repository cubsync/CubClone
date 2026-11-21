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
        return copy(srcUser, dstUser, pkg, appId, "none", 0);
    }

    /**
     * @param algo 复制完成后把这份备份压缩成包（"none" 或 null 表示只留目录）
     */
    public static String copy(int srcUser, int dstUser, String pkg, int appId,
                              String algo, int level) {
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
        if (i >= 0) {
            String bakPath = out.substring(i + 7).trim();
            // 复制成功后按设置把这份备份压成包；压缩失败不影响复制结果，
            // 目录形态的备份照样能恢复
            if (algo != null && algo.length() > 0 && !"none".equals(algo)) {
                // ts 必须带上：压缩包的命名要 <包>-<空间>-<ts>，少了 ts 列表就认不出来
                Backup nb = new Backup(dstUser, pkg, bakPath, 0, ts, "");
                String zr = compress(nb, algo, level);
                android.util.Log.i("CubClone", "auto compress " + bakPath + " -> " + zr);
                if (zr.startsWith("OK ")) {
                    String rest = zr.substring(3).trim();
                    int bar = rest.lastIndexOf('|');
                    return "OK " + (bar > 0 ? rest.substring(0, bar) : rest);
                }
            }
            return "OK " + bakPath;
        }
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
        /**
     * 列出所有备份：备份目录里的新包 + /data/user 下老版本留下的目录/包。
     * 一次 du 拿全部大小，不逐个进目录。
     */
    public static java.util.List<Backup> listBackups() {
        java.util.List<Backup> r = new java.util.ArrayList<Backup>();
        String[][] exts = {{".tar.zst", "zst"}, {".tar.gz", "gz"},
                {".tar.bz2", "bz2"}, {".tar", "tar"}};

        // 新位置：<备份目录>/<包名>-<空间>-<时间戳>.tar[.zst|.gz|.bz2]
        String out = Shell.execScript("du -sm '" + rootDir + "'/* 2>/dev/null\n");
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
            String name = path.substring(path.lastIndexOf('/') + 1);
            String[] p = parseExported(name);
            if (p == null) continue;
            String algo = algoOf(p[3]);
            if (algo.length() == 0) continue;     // 只有包才在这层，目录形态是老位置
            int user;
            try {
                user = Integer.parseInt(p[1]);
            } catch (Throwable e) {
                continue;
            }
            r.add(new Backup(user, p[0], path, kb, p[2], algo));
        }

        // 旧位置：/data/user/<U>/<包>.cubclone-bak-<时间戳>[.tar…]
        out = Shell.execScript("du -sm /data/user/*/*.cubclone-bak-* 2>/dev/null\n");
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


    /** 本机能真正跑起来的算法，按优先级从好到差；纯打包（none）永远可用 */
    public static java.util.List<String> usableAlgos() {
        java.util.List<String> r = new java.util.ArrayList<String>();
        String out = Shell.execScript(
                "command -v gzip >/dev/null 2>&1 && echo hi | gzip -6 -c >/dev/null 2>&1 && echo GZ_OK\n"
                        + "command -v bzip2 >/dev/null 2>&1 && echo hi | bzip2 -9 -c >/dev/null 2>&1 && echo BZ_OK\n");
        // zstd 用应用自带的那份（系统那个在不少机型上缺失或损坏）
        if (Zstd.path() != null) r.add("zst");
        if (out.contains("GZ_OK")) r.add("gz");
        if (out.contains("BZ_OK")) r.add("bz2");
        r.add("none");
        return r;
    }

    /**
     * 内置 zstd 的压缩命令。多线程（-T0）是关键：单线程 zstd 跟 gzip 差不了多少，
     * 开满核心实测 41 MB 数据 2.9 s → 0.58 s。
     * @return null = 本机没有可用的 zstd
     */
    public static String zstdCmd(int level) {
        String p = Zstd.path();
        if (p == null) return null;
        return level > 19 ? (p + " --ultra -" + level + " -T0 -q -c")
                : (p + " -" + level + " -T0 -q -c");
    }

    /** 对应算法怎么解压；空串 = 未压缩（或 zstd 没有内置那份），tar 直接读 */
    public static String decoderOf(String algo) {
        if ("zst".equals(algo)) { String d = Zstd.d(); return d == null ? "" : d; }
        if ("gz".equals(algo)) return "gzip -d -c";
        if ("bz2".equals(algo)) return "bzip2 -d -c";
        return "";
    }

    /** 从当前选择开始按 zstd → gzip → bzip2 → 纯打包 降级 */
    private static java.util.List<String> degradeChain(String algo) {
        String[] order = {"zst", "gz", "bz2", "none"};
        java.util.List<String> r = new java.util.ArrayList<String>();
        int at = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(algo)) at = i;
        }
        for (int i = at; i < order.length; i++) r.add(order[i]);
        return r;
    }

    /** 从 algo 起找第一个本机可用的算法；usable 为 null 时按链路第一项算 */
    public static String nextUsable(String algo, java.util.List<String> usable) {
        for (String a : degradeChain(algo)) {
            if (usable == null || usable.contains(a)) return a;
        }
        return "none";
    }

    /**
     * 给某个空间里的某个应用立刻做一份备份，不涉及另一个空间。
     * 先停止该应用，避免把写到一半的数据库复制走；原数据一个字节都不动。
     * @return "OK <备份路径>|<实际算法>" 或 "ERR_xxx"
     */
        /**
     * 给某个空间里的某个应用立刻做一份备份，不涉及另一个空间。
     * 先停止该应用，避免把写到一半的数据库复制走；原数据一个字节都不动。
     * 直接 tar 流式打包到备份目录，不在 /data 留中间副本——
     * 留副本要写两遍（先 cp 再压），大应用能差出十几秒。
     * 给某个空间里的某个应用做一份备份，不涉及另一个空间。
     * 先停止该应用，避免把写到一半的数据库复制走；原数据一个字节都不动。
     *
     * 备份范围：`/data/user/<U>/<pkg>`（CE，凭据加密存储）+ `/data/user_de/<U>/<pkg>`
     * （DE，直接启动可用）。很多应用把账号/设备标识放 DE，只备 CE 恢复后会掉登录态。
     * **不备** APK、OBB、`Android/media`——体积大且能从商店/文件管理器另行处理。
     *
     * tar 里保留 `data/user/...` / `data/user_de/...` 两级以上的路径，恢复时按原位置放回；
     * cache/code_cache 用 --exclude 剔掉（toybox tar 支持）。
     *
     * @return "OK <备份路径>|<实际算法>" 或 "ERR_xxx"
     */
    public static String backupNow(int user, String pkg, String algo, int level) {
        return backupNow(user, pkg, algo, level, backupBase(user, pkg));
    }

    /**
     * 这次备份的目标名（不含扩展名）。批量页拿它轮询输出文件大小，
     * 才能在跑的过程中报速度——所以生成规则只留这一处。
     */
    public static String backupBase(int user, String pkg) {
        return rootDir + "/" + pkg + "-" + user + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    public static String backupNow(int user, String pkg, String algo, int level, String base) {
        android.content.Context c = CloneStore.appCtx;
        return backupNow(user, pkg, algo, level, base,
                c == null ? PARTS_DEFAULT : parts(c),
                c != null && apkAll(c));
    }

    /**
     * 给某个空间里的某个应用做一份备份：先停止应用，原数据一个字节都不动。
     *
     * 备份内容按 parts 勾选：
     *   PART_CE   /data/user/&lt;U&gt;/&lt;pkg&gt;
     *   PART_DE   /data/user_de/&lt;U&gt;/&lt;pkg&gt;
     *   PART_APK  pm path 列出来的 base + split（默认一份；apkAll 时按空间各一份）
     *   PART_PERM 该空间里已授予的运行时权限与 appops（写成 perm.txt）
     * cache / code_cache 一律排除。包里不带 data/ 前缀，顶层就是应用包名。
     *
     * @return "OK &lt;备份路径&gt;|&lt;实际算法&gt;" 或 "ERR_xxx"
     */
    public static String backupNow(int user, String pkg, String algo, int level, String base,
                                   int parts, boolean apkAll) {
        String want = (algo == null || algo.length() == 0) ? "none" : algo;
        int lv = level > 0 ? level : defaultLevel(want);
        String stage = "/data/local/tmp/cc-pack-" + System.currentTimeMillis();

        StringBuilder s = new StringBuilder();
        s.append("am force-stop --user ").append(user).append(" '").append(pkg).append("'\n");
        s.append("[ -d '/data/user/").append(user).append('/').append(pkg)
                .append("' ] || { echo ERR_NO_SRC; exit 1; }\n");
        // 锁屏后 CE 密钥被驱逐，边读边压会读到一半 ENOKEY
        s.append("touch '/data/user/").append(user).append('/').append(pkg)
                .append("/.cc-probe' 2>/dev/null || { echo ERR_LOCKED; exit 1; }\n");
        s.append("rm -f '/data/user/").append(user).append('/').append(pkg).append("/.cc-probe'\n");
        s.append("mkdir -p '").append(rootDir).append("' || { echo ERR_DIR; exit 1; }\n");
        s.append("chown 1023:1023 '").append(rootDir).append("' 2>/dev/null;");
        s.append(" chmod 2775 '").append(rootDir).append("' 2>/dev/null\n");
        s.append("cd /\n");
        s.append("L=''\n");
        if ((parts & PART_CE) != 0) {
            s.append("L=\"data/user/").append(user).append('/').append(pkg).append("\"\n");
        }
        if ((parts & PART_DE) != 0) {
            s.append("[ -d 'data/user_de/").append(user).append('/').append(pkg)
                    .append("' ] && L=\"$L data/user_de/").append(user).append('/').append(pkg).append("\"\n");
        }
        // 外部三块：走 /data/media/<U>/Android/…（root 视角），包里分别折成 <pkg>/ext|obb|media
        if ((parts & PART_EXT) != 0) {
            s.append("[ -d 'data/media/").append(user).append("/Android/data/").append(pkg)
                    .append("' ] && L=\"$L data/media/").append(user).append("/Android/data/").append(pkg).append("\"\n");
        }
        if ((parts & PART_OBB) != 0) {
            s.append("[ -d 'data/media/").append(user).append("/Android/obb/").append(pkg)
                    .append("' ] && L=\"$L data/media/").append(user).append("/Android/obb/").append(pkg).append("\"\n");
        }
        if ((parts & PART_MEDIA) != 0) {
            s.append("[ -d 'data/media/").append(user).append("/Android/media/").append(pkg)
                    .append("' ] && L=\"$L data/media/").append(user).append("/Android/media/").append(pkg).append("\"\n");
        }

        // APK / 权限：先在 /data/local/tmp 摆一个和包里同构的目录，再由 xform 折成 <包名>/…
        boolean needStage = (parts & (PART_APK | PART_PERM)) != 0;
        if (needStage) {
            s.append("STAGE='").append(stage).append("'; rm -rf \"$STAGE\"; mkdir -p \"$STAGE/")
                    .append(pkg).append("\" || { echo ERR_TMP; exit 1; }\n");
            s.append("trap 'rm -rf \"$STAGE\"' EXIT\n");
            s.append("L=\"$L ").append(stage.substring(1)).append('/').append(pkg).append("\"\n");
        }
        if ((parts & PART_APK) != 0) {
            s.append("mkdir -p \"$STAGE/").append(pkg).append("/apk\"\n");
            // pm path 一次列全 base + split；apkAll 时按空间各存一份（同一版本时两份内容一样）
            s.append("APKS=$(pm path '").append(pkg).append("' 2>/dev/null | sed 's/^package://')\n");
            if (apkAll) {
                s.append("for U2 in $(pm list users | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do\n");
                s.append("  pm list packages --user $U2 2>/dev/null | grep -qx 'package:").append(pkg).append("' || continue\n");
                s.append("  mkdir -p \"$STAGE/").append(pkg).append("/apk/$U2\"\n");
                s.append("  for f in $APKS; do cp -f \"$f\" \"$STAGE/").append(pkg).append("/apk/$U2/\" 2>/dev/null; done\n");
                s.append("done\n");
            } else {
                s.append("for f in $APKS; do cp -f \"$f\" \"$STAGE/").append(pkg).append("/apk/\" 2>/dev/null; done\n");
            }
        }
        if ((parts & PART_PERM) != 0) {
            StringBuilder pl = new StringBuilder();
            for (String pm : grantedRuntimePerms(user, pkg)) pl.append("P ").append(pm).append('\n');
            for (String op : grantedAppOps(user, pkg)) pl.append("O ").append(op).append('\n');
            if (pl.length() > 0) {
                s.append("cat > \"$STAGE/").append(pkg).append("/perm.txt\" <<'CCPERM'\n");
                s.append(pl);
                s.append("CCPERM\n");
            }
        }

        // 包里不要以 data/ 打头：解出来第一眼就是应用包名，CE / DE / apk 各自一层
        s.append("XF=\" --xform=s|^data/user/").append(user).append('/').append(pkg)
                .append('|').append(pkg).append("/user|")
                .append(" --xform=s|^data/user_de/").append(user).append('/').append(pkg)
                .append('|').append(pkg).append("/user_de|");
        if (needStage) {
            s.append(" --xform=s|^").append(stage.substring(1)).append('/').append(pkg)
                    .append('|').append(pkg).append('|');
        }
        if ((parts & PART_EXT) != 0) {
            s.append(" --xform=s|^data/media/").append(user).append("/Android/data/").append(pkg)
                    .append('|').append(pkg).append("/ext|");
        }
        if ((parts & PART_OBB) != 0) {
            s.append(" --xform=s|^data/media/").append(user).append("/Android/obb/").append(pkg)
                    .append('|').append(pkg).append("/obb|");
        }
        if ((parts & PART_MEDIA) != 0) {
            s.append(" --xform=s|^data/media/").append(user).append("/Android/media/").append(pkg)
                    .append('|').append(pkg).append("/media|");
        }
        s.append("\"\n");
        s.append("B='").append(base).append("'\n");
        // $1=压缩器命令（空=纯打包）$2=扩展名 $3=解压命令（空=未压缩）
        s.append("pack() {\n");
        s.append("  O=\"$B$2\"; rm -f \"$O\"\n");
        s.append("  if [ -z \"$1\" ]; then tar -cf \"$O\" $XF --exclude='*/cache' --exclude='*/code_cache' $L 2>/dev/null\n");
        s.append("  else tar -cf - $XF --exclude='*/cache' --exclude='*/code_cache' $L 2>/dev/null | $1 > \"$O\" 2>/dev/null; fi\n");
        // 校验：管道里 tar 的失败被压缩器吞掉了，只能反过来把包解开验一遍
        s.append("  if [ -s \"$O\" ] && { if [ -z \"$3\" ]; then tar -tf \"$O\" >/dev/null 2>&1; else $3 < \"$O\" | tar -tf - >/dev/null 2>&1; fi; }; then\n");
        s.append("    chown 1023:1023 \"$O\" 2>/dev/null; chmod 664 \"$O\" 2>/dev/null\n");
        s.append("    echo CUB_OK \"$O\"; return 0; fi\n");
        s.append("  rm -f \"$O\"; return 1\n");
        s.append("}\n");
        for (String a : degradeChain(want)) {
            int use = a.equals(want) ? lv : defaultLevel(a);
            if ("zst".equals(a)) {
                String z = zstdCmd(use);
                if (z != null) {
                    s.append("pack \"").append(z).append("\" .tar.zst \"")
                            .append(decoderOf("zst")).append("\" && exit 0\n");
                }
            } else if ("gz".equals(a)) {
                s.append("command -v gzip >/dev/null 2>&1 && pack \"gzip -").append(use)
                        .append("\" .tar.gz \"").append(decoderOf("gz")).append("\" && exit 0\n");
            } else if ("bz2".equals(a)) {
                s.append("command -v bzip2 >/dev/null 2>&1 && pack \"bzip2 -").append(use)
                        .append("\" .tar.bz2 \"").append(decoderOf("bz2")).append("\" && exit 0\n");
            } else {
                s.append("pack \"\" .tar \"\" && exit 0\n");
            }
        }
        s.append("echo ERR_PACK\n");

        android.util.Log.i("CubClone", "backupNow " + pkg + " user=" + user + " parts=" + parts + " -> " + base);
        String out = Shell.execScript(s.toString());
        int i = out.indexOf("CUB_OK ");
        if (i < 0) {
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.startsWith("ERR")) return t;
            }
            return "ERR " + out.trim();
        }
        String path = out.substring(i + 7).trim();
        int dot = path.indexOf(".tar");
        return "OK " + path + "|" + (dot > 0 ? algoOf(path.substring(dot)) : "none");
    }

    /** 各算法的默认级别（与各自官方默认一致） */
    public static int defaultLevel(String algo) {
        if ("zst".equals(algo)) return 3;
        if ("gz".equals(algo)) return 6;
        if ("bz2".equals(algo)) return 9;
        return 0;
    }

    /** 某算法的最高级别（zstd 1–22，20 以上要 --ultra；gzip/bzip2 1–9） */
    public static int maxLevel(String algo) {
        if ("zst".equals(algo)) return 22;
        if ("gz".equals(algo)) return 9;
        if ("bz2".equals(algo)) return 9;
        return 0;
    }

    /** 某算法可选的级别（保留给需要枚举的场景，UI 用 maxLevel + SeekBar） */
    public static int[] levelsOf(String algo) {
        int max = maxLevel(algo);
        if (max <= 0) return new int[0];
        int[] r = new int[max];
        for (int i = 0; i < max; i++) r[i] = i + 1;
        return r;
    }

    /**
     * 把目录形态的备份打包，可指定级别。
     * 用 `tar -cf - | <压缩器> -<级别>` 而不是 tar 的 -z/-Z/-j：
     * toybox tar 的选项不接受级别参数，管道才能真正给到压缩器。
     * 失败按 zstd → gzip → bzip2 → 纯打包 逐级降级（本机 zstd 二进制实测不可用）。
     * 用户选的级别只用在用户选的那个算法上，降级后的算法用各自默认档。
     * 打包并校验通过后才删原目录；任何一步失败都保留原样。
     * 把目录形态的旧备份打成包，搬到备份目录，成功后删掉原目录。
     * 用 `tar -cf - | <压缩器> -<级别>` 而不是 tar 的 -z/-Z/-j：
     * toybox tar 的选项不接受级别参数，管道才能真正给到压缩器。
     * 失败按选择 → gzip → bzip2 → 纯打包降级（本机 zstd 二进制可能坏）。
     * 打包并校验通过后才删原目录；任何一步失败都保留原样。
     * @return "OK <包路径>|<实际算法>" 或 "ERR_xxx"
     */
    public static String compress(Backup b, String algo, int level) {
        if (b.packed()) return "ERR_ALREADY";
        String parent = b.path.substring(0, b.path.lastIndexOf('/'));
        String name = b.path.substring(b.path.lastIndexOf('/') + 1);
        String want = (algo == null || algo.length() == 0) ? "none" : algo;
        int lv = level > 0 ? level : defaultLevel(want);
        String bts = (b.ts != null && b.ts.length() > 0) ? b.ts
                : new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String base = rootDir + "/" + b.pkg + "-" + b.user + "-" + bts;

        StringBuilder s = new StringBuilder();
        s.append("SRC='").append(b.path).append("'\n");
        s.append("P='").append(parent).append("'\n");
        s.append("N='").append(name).append("'\n");
        s.append("B='").append(base).append("'\n");
        s.append("mkdir -p '").append(rootDir).append("' || { echo ERR_DIR; exit 1; }\n");
        s.append("chown 1023:1023 '").append(rootDir).append("' 2>/dev/null;");
        s.append(" chmod 2775 '").append(rootDir).append("' 2>/dev/null\n");
        // $1=压缩器命令（空=纯打包）$2=扩展名 $3=算法名 $4=解压命令
        s.append("pack() {\n");
        s.append("  O=\"$B$2\"; rm -f \"$O\"\n");
        s.append("  if [ -z \"$1\" ]; then tar -cf \"$O\" -C \"$P\" \"$N\" 2>/dev/null\n");
        s.append("  else tar -cf - -C \"$P\" \"$N\" 2>/dev/null | $1 > \"$O\" 2>/dev/null; fi\n");
        s.append("  if [ -s \"$O\" ] && { if [ -z \"$4\" ]; then tar -tf \"$O\" >/dev/null 2>&1; else $4 < \"$O\" | tar -tf - >/dev/null 2>&1; fi; }; then\n");
        s.append("    chown 1023:1023 \"$O\" 2>/dev/null; chmod 664 \"$O\" 2>/dev/null\n");
        s.append("    rm -rf \"$SRC\"; echo CUB_OK \"$O\" $3; return 0\n");
        s.append("  fi\n");
        s.append("  rm -f \"$O\"; return 1\n");
        s.append("}\n");
        for (String a : degradeChain(want)) {
            int use = a.equals(want) ? lv : defaultLevel(a);
            if ("zst".equals(a)) {
                String z = zstdCmd(use);
                if (z != null) {
                    s.append("pack \"").append(z).append("\" .tar.zst zst \"")
                            .append(decoderOf("zst")).append("\" && exit 0\n");
                }
            } else if ("gz".equals(a)) {
                s.append("command -v gzip >/dev/null 2>&1 && pack \"gzip -").append(use)
                        .append("\" .tar.gz gz \"").append(decoderOf("gz")).append("\" && exit 0\n");
            } else if ("bz2".equals(a)) {
                s.append("command -v bzip2 >/dev/null 2>&1 && pack \"bzip2 -").append(use)
                        .append("\" .tar.bz2 bz2 \"").append(decoderOf("bz2")).append("\" && exit 0\n");
            } else {
                s.append("pack \"\" .tar tar \"\" && exit 0\n");
            }
        }
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
     * 把备份换回原位。当前数据不再另留一份：备份本来就是你要的东西，
     * 每次恢复再复制一份只会让列表膨胀。
     * 但也不是直接 rm：先 mv 到 /data/local/tmp（同分区改名，零成本），
     * 换位成功才删，失败就挪回去。
     * @return "OK" 或 "ERR_xxx"
     */
        /**
     * 把备份换回原位。当前数据不再另留一份：备份本来就是你要的东西，
     * 每次恢复再复制一份只会让列表膨胀。
     * 但也不是直接 rm：先 mv 到 /data/local/tmp（同分区改名，零成本），
     * 换位成功才删，失败就挪回去。
     *
     * 两种包都认：新包内是 `data/user/<U>/<pkg>` + `data/user_de/<U>/<pkg>`，
     * 老包内是 `<pkg>` 或 `<pkg>.cubclone-bak-<ts>`。
     * @return "OK" 或 "ERR_xxx"
     */
    public static String restore(Backup b, int appId) {
        return restore(b, appId, b.user, PARTS_DEFAULT);
    }

    /**
     * 把备份换回原位（或换到指定用户）。当前数据不再另留一份：备份本来就是你要的，
     * 每次恢复再复制一份只会让列表膨胀。但也不是直接 rm：先 mv 到 /data/local/tmp，
     * 换位成功才删，失败就挪回去。
     *
     * @param targetUser 恢复到哪个空间；&lt; 0 表示按备份里的空间
     * @param parts      恢复哪些内容（PART_CE / PART_DE / PART_APK / PART_PERM）
     * @return "OK" 或 "ERR_xxx"
     */
    public static String restore(Backup b, int appId, int targetUser, int parts) {
        int T = targetUser < 0 ? b.user : targetUser;
        String ce = "/data/user/" + T + "/" + b.pkg;
        String de = "/data/user_de/" + T + "/" + b.pkg;
        long uid = (long) appId + (long) T * 100000L;
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        // 上下文里带本应用实例的 MLS 类别，跨空间恢复时必须取目标空间自己的；
        // 目标空间还没装这个包时才退回备份来源空间的
        String ctx = contextOf(T, b.pkg);
        if (ctx.length() == 0) ctx = contextOf(b.user, b.pkg);
        boolean wantCe = (parts & PART_CE) != 0;
        boolean wantDe = (parts & PART_DE) != 0;

        StringBuilder s = new StringBuilder();
        s.append("am force-stop --user ").append(T).append(" '").append(b.pkg).append("'\n");
        s.append("OLD='/data/local/tmp/cc-old-").append(ts).append("'\n");
        s.append("rm -rf \"$OLD\"; mkdir -p \"$OLD\"\n");
        s.append("CE='").append(ce).append("'; DE='").append(de).append("'\n");
        s.append("T='").append(T).append("'\n");
        // 分身里的 appId 可能和主空间不同：属主按目标空间里实际装的这个包来取，
        // 用主空间 appId 推出来的数字会把目录设成别的应用（曾把支付宝目录设成 u0_a161）
        s.append("AU=$(stat -c %u \"/data/user/").append(T).append('/').append(b.pkg)
                .append("\" 2>/dev/null); [ -n \"$AU\" ] || AU=").append(uid).append("\n");

        if (b.packed()) {
            String tmp = "/data/local/tmp/cc-restore-" + ts;
            // 老包（目录形态压出来的）顶层直接是 <pkg> 或 <pkg>.cubclone-bak-<ts>
            String flat = (b.path.startsWith("/data/user/") && b.path.contains(".cubclone-bak-"))
                    ? b.path.substring(b.path.lastIndexOf('/') + 1).replaceFirst("\\.tar.*$", "")
                    : b.pkg;
            s.append("TMP='").append(tmp).append("'\n");
            s.append("rm -rf \"$TMP\"; mkdir -p \"$TMP\" || { echo ERR_TMP; exit 1; }\n");
            String dec = decoderOf(b.algo);
            if (dec.length() == 0 && "zst".equals(b.algo)) {
                // 没有内置 zstd 的极端情况：退回系统 tar 的 -Z，成不成就看机型
                s.append("tar -xZf '").append(b.path).append("' -C \"$TMP\"")
                        .append(" || { rm -rf \"$TMP\"; echo ERR_UNPACK; exit 1; }\n");
            } else if (dec.length() > 0) {
                s.append(dec).append(" < '").append(b.path).append("'")
                        .append(" | tar -xf - -C \"$TMP\"")
                        .append(" || { rm -rf \"$TMP\"; echo ERR_UNPACK; exit 1; }\n");
            } else {
                s.append("tar -xf '").append(b.path).append("' -C \"$TMP\"")
                        .append(" || { rm -rf \"$TMP\"; echo ERR_UNPACK; exit 1; }\n");
            }
            // 包内布局认三种：<pkg>/user…、data/user/<U>/<pkg>…、顶层直接是目录名
            s.append("if [ -d \"$TMP/").append(b.pkg).append("/user\" ]; then\n");
            s.append("  SCE=\"$TMP/").append(b.pkg).append("/user\"\n");
            s.append("  SDE=\"$TMP/").append(b.pkg).append("/user_de\"\n");
            s.append("elif [ -d \"$TMP/data/user/").append(b.user).append('/').append(b.pkg).append("\" ]; then\n");
            s.append("  SCE=\"$TMP/data/user/").append(b.user).append('/').append(b.pkg).append("\"\n");
            s.append("  SDE=\"$TMP/data/user_de/").append(b.user).append('/').append(b.pkg).append("\"\n");
            s.append("elif [ -d \"$TMP/").append(flat).append("\" ]; then\n");
            s.append("  SCE=\"$TMP/").append(flat).append("\"; SDE=''\n");
            s.append("else\n");
            s.append("  SCE=\"$TMP/").append(b.pkg).append("/user\"; SDE=''\n");
            s.append("fi\n");
        } else {
            s.append("[ -d '").append(b.path).append("' ] || { echo ERR_NO_BAK; exit 1; }\n");
            s.append("SCE='").append(b.path).append("'; SDE=''\n");
        }

        // CE：先挪开当前数据，换位失败原样退回
        if (wantCe) {
            s.append("if [ -d \"$SCE\" ]; then\n");
            s.append("  [ -d \"$CE\" ] && mv \"$CE\" \"$OLD/ce\"\n");
            s.append("  if ! mv \"$SCE\" \"$CE\"; then\n");
            s.append("    [ -d \"$OLD/ce\" ] && mv \"$OLD/ce\" \"$CE\"\n");
            s.append("    rm -rf \"$TMP\"; echo ERR_MV2; exit 1\n");
            s.append("  fi\n");
            s.append("fi\n");
        }
        // DE：包里没有就保持现状
        if (wantDe) {
            s.append("if [ -n \"$SDE\" ] && [ -d \"$SDE\" ]; then\n");
            s.append("  [ -d \"$DE\" ] && mv \"$DE\" \"$OLD/de\"\n");
            s.append("  mv \"$SDE\" \"$DE\" || { [ -d \"$OLD/de\" ] && mv \"$OLD/de\" \"$DE\"; }\n");
            s.append("fi\n");
        }
        // 外部三块：包里有 <pkg>/ext|obb|media 就换到 /data/media/<T>/Android/… 对应目录。
        // 这些目录在 FUSE 后面，属主必须是 media_rw(1023)，否则应用和文件管理器都读不到
        int[] extBits = {PART_EXT, PART_OBB, PART_MEDIA};
        String[] extIn = {"ext", "obb", "media"};
        String[] extOut = {"data", "obb", "media"};
        StringBuilder extDstList = new StringBuilder();
        for (int k = 0; k < extBits.length; k++) {
            if ((parts & extBits[k]) == 0) continue;
            String dst = "/data/media/" + T + "/Android/" + extOut[k] + "/" + b.pkg;
            extDstList.append(" '").append(dst).append('\'');
            s.append("if [ -d \"$TMP/").append(b.pkg).append('/').append(extIn[k]).append("\" ]; then\n");
            s.append("  rm -rf '").append(dst).append("'\n");
            s.append("  mkdir -p '/data/media/").append(T).append("/Android/").append(extOut[k]).append("' 2>/dev/null\n");
            s.append("  mv \"$TMP/").append(b.pkg).append('/').append(extIn[k]).append("\" '")
                    .append(dst).append("' || echo ERR_EXT\n");
            s.append("fi\n");
        }

        // APK：装了就把新包按用户补上（先装再 install-existing 到目标空间）
        if ((parts & PART_APK) != 0) {
            s.append("if [ -d \"$TMP/").append(b.pkg).append("/apk\" ]; then\n");
            s.append("  AD=\"$TMP/").append(b.pkg).append("/apk\"\n");
            s.append("  [ -d \"$AD/$T\" ] && AD=\"$AD/$T\"\n");
            s.append("  NA=$(ls \"$AD\"/*.apk 2>/dev/null | wc -l)\n");
            s.append("  if [ \"$NA\" -eq 1 ]; then\n");
            s.append("    pm install -r -d -t \"$(ls \"$AD\"/*.apk | head -1)\" >/dev/null 2>&1 || echo ERR_APK\n");
            s.append("  elif [ \"$NA\" -gt 1 ]; then\n");
            s.append("    pm install-multiple -r -d -t $(ls \"$AD\"/*.apk) >/dev/null 2>&1 || echo ERR_APK\n");
            s.append("  fi\n");
            s.append("  cmd package install-existing --user \"$T\" '").append(b.pkg).append("' >/dev/null 2>&1\n");
            s.append("fi\n");
        }
        // 权限：包里有 perm.txt 就按目标用户重新授予
        if ((parts & PART_PERM) != 0) {
            s.append("if [ -f \"$TMP/").append(b.pkg).append("/perm.txt\" ]; then\n");
            s.append("  while read -r K P; do\n");
            s.append("    [ -z \"$K\" ] && continue\n");
            s.append("    if [ \"$K\" = \"P\" ]; then pm grant --user \"$T\" '").append(b.pkg).append("' \"$P\" >/dev/null 2>&1\n");
            s.append("    else appops set --user \"$T\" '").append(b.pkg).append("' \"$P\" allow >/dev/null 2>&1; fi\n");
            s.append("  done < \"$TMP/").append(b.pkg).append("/perm.txt\"\n");
            s.append("fi\n");
        }
        s.append("rm -rf \"$TMP\"\n");

        // 换回的数据要换属主与上下文；APK 装的包由系统自己管
        s.append("for D in \"$CE\" \"$DE\"; do\n");
        s.append("  [ -d \"$D\" ] || continue\n");
        s.append("  chown -R \"$AU:$AU\" \"$D\"\n");
        if (ctx.length() > 0) {
            s.append("  chcon -R '").append(ctx).append("' \"$D\"\n");
        }
        s.append("done\n");
        if (extDstList.length() > 0) {
            s.append("for D in").append(extDstList).append("; do\n");
            s.append("  [ -d \"$D\" ] || continue\n");
            // 系统约定（实测）：owner 是这个空间里的应用 uid，不是 media_rw。
            // Android/data 的组是 ext_data_rw(1078)、Android/media|obb 的组是 media_rw(1023)。
            // FUSE 下真正做权限判定的是 MediaProvider 进程，它的补充组里有这两个组；
            // 组写错（比如算成 xxx+10000 那种）它就被挡在外面，应用侧只看到 EPERM。
            s.append("  case \"$D\" in */Android/data/*)\n");
            s.append("    chown -R ${AU:-0}:1078 \"$D\" 2>/dev/null\n");
            s.append("    find \"$D\" -type d -exec chmod 2770 {} + 2>/dev/null\n");
            s.append("    find \"$D\" -type f -exec chmod 660 {} + 2>/dev/null;;\n");
            s.append("  *)\n");
            s.append("    chown -R ${AU:-0}:1023 \"$D\" 2>/dev/null\n");
            s.append("    find \"$D\" -type d -exec chmod 2775 {} + 2>/dev/null\n");
            s.append("    find \"$D\" -type f -exec chmod 664 {} + 2>/dev/null;;\n");
            s.append("  esac\n");
            s.append("  chcon -R 'u:object_r:media_rw_data_file:s0' \"$D\" 2>/dev/null\n");
            s.append("done\n");
        }
        s.append("rm -rf \"$OLD\"\n");
        s.append("echo CUB_OK\n");

        String out = Shell.execScript(s.toString());
        if (out.contains("CUB_OK")) {
            if (out.contains("ERR_APK")) return "OK_NOAPK";
            return "OK";
        }
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("ERR")) return t;
        }
        return "ERR " + out.trim();
    }


    /** 备份根目录默认值；用户可在「数据备份」页改成 /sdcard 下任意目录 */
    public static final String DEFAULT_ROOT = "/sdcard/Download/CubClone-backup";

    /** 备份内容：应用私有数据（CE） */
    public static final int PART_CE = 1;
    /** 设备加密存储（DE）：很多应用把账号、设备标识放这里，缺了恢复后容易掉登录态 */
    public static final int PART_DE = 2;
    /** 安装包（base + split） */
    public static final int PART_APK = 4;
    /** 用户授予过的运行时权限与 appops */
    public static final int PART_PERM = 8;
    /** 外部私有目录 /sdcard/Android/data/&lt;pkg&gt;（缓存类的也在这里，故默认不备） */
    public static final int PART_EXT = 16;
    /** /sdcard/Android/obb/&lt;pkg&gt;：游戏资源包，重下要几百 MB ~ 几 GB */
    public static final int PART_OBB = 32;
    /** /sdcard/Android/media/&lt;pkg&gt;：不少应用把下载/导出放这里（如支付类账单） */
    public static final int PART_MEDIA = 64;

    /** 默认：数据 + 权限。APK 体积大、能重装，外部目录默认不带 */
    public static final int PARTS_DEFAULT = PART_CE | PART_DE | PART_PERM;

    /** 备份里带的所有内容（恢复页「全选」用） */
    public static final int PARTS_ALL = PART_CE | PART_DE | PART_APK | PART_PERM
            | PART_EXT | PART_OBB | PART_MEDIA;

    /** 外部三块的真实路径（root 视角）；/storage/emulated 在 nsenter 下不稳，统一走 /data/media */
    public static String extDir(String kind, int user, String pkg) {
        return "/data/media/" + user + "/Android/" + kind + "/" + pkg;
    }

    public static int parts(android.content.Context c) {
        return c.getSharedPreferences("clone", 0).getInt("backup_parts", PARTS_DEFAULT);
    }

    /** APK 是否按空间各存一份（默认只存一份：同一个包在 Android 上就是同一套文件） */
    public static boolean apkAll(android.content.Context c) {
        return c.getSharedPreferences("clone", 0).getBoolean("backup_apk_all", false);
    }

    private static String rootDir = DEFAULT_ROOT;

    public static void setRoot(String dir) {
        rootDir = (dir == null || dir.trim().length() == 0) ? DEFAULT_ROOT : dir.trim();
    }

    public static String root() {
        return rootDir;
    }

    /** 设置项 backup_dir 是唯一真源，三个入口打开时都读一遍 */
    public static void loadRoot(android.content.SharedPreferences sp) {
        setRoot(sp.getString("backup_dir", DEFAULT_ROOT));
    }

    /** ".tar.zst" → "zst"、".tar" → "tar"、其它 → "" */
    public static String algoOf(String tail) {
        if (tail == null) return "";
        if (tail.endsWith(".zst")) return "zst";
        if (tail.endsWith(".gz")) return "gz";
        if (tail.endsWith(".bz2")) return "bz2";
        if (tail.endsWith(".tar")) return "tar";
        return "";
    }

    /**
     * 解析备份文件名 `<包名>-<空间>-<yyyyMMdd-HHmmss><扩展名>`。
     * 包名里不会有 '-'，所以从右往左找 `-<数字>-<8位-6位>` 不会切错。
     * @return {pkg, user, ts, ext}；ext 是 "" 或 ".tar"/".tar.zst"/".tar.gz"/".tar.bz2"
     */
    public static String[] parseExported(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.+)-(\\d+)-(\\d{8}-\\d{6})(\\.tar(?:\\.zst|\\.gz|\\.bz2)?)?$")
                .matcher(name);
        if (!m.matches()) return null;
        return new String[]{m.group(1), m.group(2), m.group(3),
                m.group(4) == null ? "" : m.group(4)};
    }

    /**
     * 换备份目录时把旧目录里的备份搬过去。同一分区，mv 只是改名。
     * @return "OK <搬了几个>" 或 "ERR_xxx"
     */
    /**
     * 从备份路径反解出 Backup。UI 之间只传路径，省得序列化整个对象。
     * 两种命名都认：新包 `<包>-<U>-<ts>.tar*`、老包 `<包>.cubclone-bak-<ts>[.tar*]` 或目录。
     * @return null = 认不出来
     */
    public static Backup fromPath(String path) {
        if (path == null || path.length() == 0) return null;
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.indexOf(".tar");
        String tail = dot > 0 ? name.substring(dot) : "";
        String base = dot > 0 ? name.substring(0, dot) : name;
        int k = base.indexOf(".cubclone-bak-");
        if (k > 0) {
            int user = userOfPath(path);
            if (user < 0) return null;
            return new Backup(user, base.substring(0, k), path, 0, base.substring(k + 14), algoOf(tail));
        }
        String[] p = parseExported(name);
        if (p == null) return null;
        int user;
        try {
            user = Integer.parseInt(p[1]);
        } catch (Throwable t) {
            return null;
        }
        return new Backup(user, p[0], path, 0, p[2], algoOf(p[3]));
    }

    private static int userOfPath(String path) {
        if (!path.startsWith("/data/user/")) return -1;
        String rest = path.substring("/data/user/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return -1;
        try {
            return Integer.parseInt(rest.substring(0, slash));
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String moveBackups(String from, String to) {
        if (from == null || to == null || from.length() == 0 || from.equals(to)) return "OK 0";
        StringBuilder s = new StringBuilder();
        s.append("mkdir -p '").append(to).append("' || { echo ERR_DIR; exit 1; }\n");
        s.append("N=0\n");
        s.append("for f in '").append(from).append("'/*; do\n");
        s.append("  [ -f \"$f\" ] || continue\n");
        s.append("  case \"$f\" in *.tar|*.tar.zst|*.tar.gz|*.tar.bz2) ;; *) continue;; esac\n");
        s.append("  mv -f \"$f\" '").append(to).append("/' && N=$((N+1))\n");
        s.append("done\n");
        s.append("echo CUB_OK $N\n");
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

    /** 批量删除，逐条 rm 并统计成功数 */
    public static String deleteMany(java.util.List<Backup> list) {
        StringBuilder s = new StringBuilder();
        s.append("N=0\n");
        for (Backup b : list) {
            s.append("[ -e '").append(b.path).append("' ] && rm -rf '").append(b.path)
                    .append("' && N=$((N+1))\n");
        }
        s.append("echo CUB_OK $N\n");
        String out = Shell.execScript(s.toString());
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
            String out = Shell.execScript("ls -Zd '" + p + "' 2>/dev/null\n");
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("u:object_r:[^\\s]+").matcher(out);
            if (m.find()) return m.group();
        }
        return "";
    }
}
