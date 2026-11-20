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
                "command -v zstd >/dev/null 2>&1 && echo hi | zstd -q -c >/dev/null 2>&1 && echo ZST_OK\n"
                        + "command -v gzip >/dev/null 2>&1 && echo hi | gzip -6 -c >/dev/null 2>&1 && echo GZ_OK\n"
                        + "command -v bzip2 >/dev/null 2>&1 && echo hi | bzip2 -9 -c >/dev/null 2>&1 && echo BZ_OK\n");
        if (out.contains("ZST_OK")) r.add("zst");
        if (out.contains("GZ_OK")) r.add("gz");
        if (out.contains("BZ_OK")) r.add("bz2");
        r.add("none");
        return r;
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
     * @return "OK <备份路径>|<实际算法>" 或 "ERR_xxx"
     */
        /**
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
        String base = rootDir + "/" + pkg + "-" + user + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String want = (algo == null || algo.length() == 0) ? "none" : algo;
        int lv = level > 0 ? level : defaultLevel(want);

        StringBuilder s = new StringBuilder();
        s.append("am force-stop --user ").append(user).append(" '").append(pkg).append("'\n");
        s.append("[ -d '/data/user/").append(user).append('/').append(pkg)
                .append("' ] || { echo ERR_NO_SRC; exit 1; }\n");
        // 锁屏后 CE 密钥被驱逐，边读边压会读到一半 ENOKEY
        s.append("touch '/data/user/").append(user).append('/').append(pkg)
                .append("/.cc-probe' 2>/dev/null || { echo ERR_LOCKED; exit 1; }\n");
        s.append("rm -f '/data/user/").append(user).append('/').append(pkg).append("/.cc-probe'\n");
        s.append("mkdir -p '").append(rootDir).append("' || { echo ERR_DIR; exit 1; }\n");
        // tar 从 / 出发，保留完整相对路径
        s.append("cd /\n");
        s.append("L='data/user/").append(user).append('/').append(pkg).append("'\n");
        s.append("[ -d 'data/user_de/").append(user).append('/').append(pkg)
                .append("' ] && L=\"$L data/user_de/").append(user).append('/').append(pkg).append("\"\n");
        s.append("B='").append(base).append("'\n");
        // $1=压缩器命令（空=纯打包）$2=扩展名 $3=tar 列表标志
        s.append("pack() {\n");
        s.append("  O=\"$B$2\"; rm -f \"$O\"\n");
        s.append("  if [ -z \"$1\" ]; then tar -cf \"$O\" --exclude='*/cache' --exclude='*/code_cache' $L 2>/dev/null\n");
        s.append("  else tar -cf - --exclude='*/cache' --exclude='*/code_cache' $L 2>/dev/null | $1 > \"$O\" 2>/dev/null; fi\n");
        // tar -t 校验：管道里 tar 的失败被压缩器吞掉了，只能反过来验包
        s.append("  if [ -s \"$O\" ] && tar -t$3f \"$O\" >/dev/null 2>&1; then\n");
        s.append("    chown 1023:1023 \"$O\" 2>/dev/null; chmod 660 \"$O\" 2>/dev/null\n");
        s.append("    echo CUB_OK \"$O\"; return 0; fi\n");
        s.append("  rm -f \"$O\"; return 1\n");
        s.append("}\n");
        for (String a : degradeChain(want)) {
            int use = a.equals(want) ? lv : defaultLevel(a);
            if ("zst".equals(a)) {
                // 20 级以上 zstd 要求 --ultra，否则直接拒绝
                String z = use > 19 ? ("zstd --ultra -" + use + " -q -c") : ("zstd -" + use + " -q -c");
                s.append("command -v zstd >/dev/null 2>&1 && pack \"").append(z)
                        .append("\" .tar.zst Z && exit 0\n");
            } else if ("gz".equals(a)) {
                s.append("command -v gzip >/dev/null 2>&1 && pack \"gzip -").append(use)
                        .append("\" .tar.gz z && exit 0\n");
            } else if ("bz2".equals(a)) {
                s.append("command -v bzip2 >/dev/null 2>&1 && pack \"bzip2 -").append(use)
                        .append("\" .tar.bz2 j && exit 0\n");
            } else {
                s.append("pack \"\" .tar \"\" && exit 0\n");
            }
        }
        s.append("echo ERR_PACK\n");

        android.util.Log.i("CubClone", "backupNow " + pkg + " user=" + user + " -> " + base);
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
     * @return "OK <包路径> <实际算法>" 或 "ERR_xxx"
     */
        /**
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
        // $1=压缩器命令（空=纯打包）$2=扩展名 $3=算法名 $4=tar 列表标志
        s.append("pack() {\n");
        s.append("  O=\"$B$2\"; rm -f \"$O\"\n");
        s.append("  if [ -z \"$1\" ]; then tar -cf \"$O\" -C \"$P\" \"$N\" 2>/dev/null\n");
        s.append("  else tar -cf - -C \"$P\" \"$N\" 2>/dev/null | $1 > \"$O\" 2>/dev/null; fi\n");
        s.append("  if [ -s \"$O\" ] && tar -t$4f \"$O\" >/dev/null 2>&1; then\n");
        s.append("    chown 1023:1023 \"$O\" 2>/dev/null; chmod 660 \"$O\" 2>/dev/null\n");
        s.append("    rm -rf \"$SRC\"; echo CUB_OK \"$O\" $3; return 0\n");
        s.append("  fi\n");
        s.append("  rm -f \"$O\"; return 1\n");
        s.append("}\n");
        for (String a : degradeChain(want)) {
            int use = a.equals(want) ? lv : defaultLevel(a);
            if ("zst".equals(a)) {
                String z = use > 19 ? ("zstd --ultra -" + use + " -q -c") : ("zstd -" + use + " -q -c");
                s.append("command -v zstd >/dev/null 2>&1 && pack \"").append(z)
                        .append("\" .tar.zst zst Z && exit 0\n");
            } else if ("gz".equals(a)) {
                s.append("command -v gzip >/dev/null 2>&1 && pack \"gzip -").append(use)
                        .append("\" .tar.gz gz z && exit 0\n");
            } else if ("bz2".equals(a)) {
                s.append("command -v bzip2 >/dev/null 2>&1 && pack \"bzip2 -").append(use)
                        .append("\" .tar.bz2 bz2 j && exit 0\n");
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
        String ce = "/data/user/" + b.user + "/" + b.pkg;
        String de = "/data/user_de/" + b.user + "/" + b.pkg;
        long uid = (long) appId + (long) b.user * 100000L;
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String ctx = contextOf(b.user, b.pkg);

        StringBuilder s = new StringBuilder();
        s.append("am force-stop --user ").append(b.user).append(" '").append(b.pkg).append("'\n");
        s.append("OLD='/data/local/tmp/cc-old-").append(ts).append("'\n");
        s.append("rm -rf \"$OLD\"; mkdir -p \"$OLD\"\n");
        s.append("CE='").append(ce).append("'; DE='").append(de).append("'\n");

        if (b.packed()) {
            String tmp = "/data/local/tmp/cc-restore-" + ts;
            s.append("TMP='").append(tmp).append("'\n");
            s.append("rm -rf \"$TMP\"; mkdir -p \"$TMP\" || { echo ERR_TMP; exit 1; }\n");
            s.append("tar -x").append(b.tarFlag()).append("f '").append(b.path)
                    .append("' -C \"$TMP\" || { rm -rf \"$TMP\"; echo ERR_UNPACK; exit 1; }\n");
            // 新布局：$TMP/data/user/<U>/<pkg>
            s.append("NCE=\"$TMP/data/user/").append(b.user).append('/').append(b.pkg).append("\"\n");
            s.append("NDE=\"$TMP/data/user_de/").append(b.user).append('/').append(b.pkg).append("\"\n");
            s.append("if [ -d \"$NCE\" ]; then\n");
            s.append("  [ -d \"$CE\" ] && mv \"$CE\" \"$OLD/ce\"\n");
            s.append("  [ -d \"$DE\" ] && mv \"$DE\" \"$OLD/de\"\n");
            s.append("  if ! mv \"$NCE\" \"$CE\"; then\n");
            s.append("    [ -d \"$OLD/ce\" ] && mv \"$OLD/ce\" \"$CE\"\n");
            s.append("    [ -d \"$OLD/de\" ] && mv \"$OLD/de\" \"$DE\"\n");
            s.append("    rm -rf \"$TMP\"; echo ERR_MV2; exit 1\n");
            s.append("  fi\n");
            s.append("  [ -d \"$NDE\" ] && mv \"$NDE\" \"$DE\"\n");
            s.append("else\n");
            // 老布局：顶层就是 <pkg> 或 <pkg>.cubclone-bak-<ts>
            String oldInner = (b.path.startsWith("/data/user/") && b.path.contains(".cubclone-bak-"))
                    ? b.path.substring(b.path.lastIndexOf('/') + 1).replaceFirst("\\.tar.*$", "")
                    : b.pkg;
            s.append("  [ -d \"$TMP/").append(oldInner).append("\" ] || { rm -rf \"$TMP\"; echo ERR_UNPACK2; exit 1; }\n");
            s.append("  [ -d \"$CE\" ] && mv \"$CE\" \"$OLD/ce\"\n");
            s.append("  if ! mv \"$TMP/").append(oldInner).append("\" \"$CE\"; then\n");
            s.append("    [ -d \"$OLD/ce\" ] && mv \"$OLD/ce\" \"$CE\"\n");
            s.append("    rm -rf \"$TMP\"; echo ERR_MV2; exit 1\n");
            s.append("  fi\n");
            s.append("fi\n");
            s.append("rm -rf \"$TMP\"\n");
        } else {
            s.append("[ -d '").append(b.path).append("' ] || { echo ERR_NO_BAK; exit 1; }\n");
            s.append("[ -d \"$CE\" ] && mv \"$CE\" \"$OLD/ce\"\n");
            s.append("if ! mv '").append(b.path).append("' \"$CE\"; then\n");
            s.append("  [ -d \"$OLD/ce\" ] && mv \"$OLD/ce\" \"$CE\"\n");
            s.append("  echo ERR_MV2; exit 1\n");
            s.append("fi\n");
        }
        // 两处数据都要换属主与上下文
        s.append("for D in \"$CE\" \"$DE\"; do\n");
        s.append("  [ -d \"$D\" ] || continue\n");
        s.append("  chown -R ").append(uid).append(":").append(uid).append(" \"$D\"\n");
        if (ctx.length() > 0) {
            s.append("  chcon -R '").append(ctx).append("' \"$D\"\n");
        }
        s.append("done\n");
        s.append("rm -rf \"$OLD\"\n");
        s.append("echo CUB_OK\n");

        String out = Shell.execScript(s.toString());
        if (out.contains("CUB_OK")) return "OK";
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("ERR")) return t;
        }
        return "ERR " + out.trim();
    }


    /** 备份根目录默认值；用户可在「数据备份」页改成 /sdcard 下任意目录 */
    public static final String DEFAULT_ROOT = "/sdcard/Download/CubClone-backup";

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
