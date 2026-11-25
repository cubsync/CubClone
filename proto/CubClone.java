import android.content.Context;
import android.os.UserManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CubClone —— 应用分身 CLI（AOSP clone profile）
 *
 * 用法（shell/root 均可，需 MANAGE_USERS）：
 *   app_process ... CubClone list
 *   app_process ... CubClone create <名字>
 *   app_process ... CubClone start  <uid>
 *   app_process ... CubClone remove <uid>
 *   app_process ... CubClone install <uid> <包名>
 *   app_process ... CubClone launch  <uid> <包名>
 */
public final class CubClone {

    private static final String CLONE = "android.os.usertype.profile.CLONE";
    /**
     * 降级档：内核还编着 sdcardfs 的 ROM 上，vold 给 clone 用户 bind
     * `/mnt/user/<uid>/emulated/<uid>/Android/data` 时，MediaProvider 的 FUSE
     * 对非属主（含 root）的 lookup 直接回 EACCES，整卷回滚成 unmountable。
     * 这类机型改用普通用户（独立存储）：功能可用，代价是不与主空间共享相册/文件。
     */
    private static final String PRIVATE = "android.os.usertype.full.SECONDARY";
    private static UserManager UM;
    private static Object PM;   // PackageManager

    public static void main(String[] args) {
        try {
            unsealHiddenApi();
            Context ctx = systemContext();
            if (ctx == null) { say("ERR no context"); return; }
            UM = (UserManager) ctx.getSystemService("user");
            PM = ctx.getPackageManager();

            String cmd = args.length > 0 ? args[0] : "list";
            switch (cmd) {
                case "list":    list(); break;
                case "create":  create(args[1], args.length > 2 ? args[2].split(",") : null); break;
                case "start":   sh("am", "start-user", args[1]); break;
                case "remove":  remove(Integer.parseInt(args[1])); break;
                case "rename":  rename(Integer.parseInt(args[1]), args[2]); break;
                case "install": install(Integer.parseInt(args[1]), args[2]); break;
                case "launch":  launch(Integer.parseInt(args[1]), args[2]); break;
                default:
                    say("commands: list | create <name> | start <uid> | remove <uid>"
                            + " | install <uid> <pkg> | launch <uid> <pkg>");
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            say("ERR " + c);
            c.printStackTrace(System.out);
        }
    }

    // ---------- 命令 ----------

    private static void list() throws Exception {
        Method gu = UserManager.class.getMethod("getUsers");   // List<UserInfo>
        List<?> all = (List<?>) gu.invoke(UM);
        int n = 0;
        for (Object u : all) {
            String type = userTypeOf(u);
            String uid = uidOf(u);
            boolean hit = CLONE.equals(type)
                    || (PRIVATE.equals(type)
                    && sh("settings", "get", "--user", uid, "secure", "cubclone_owned").trim().startsWith("1"));
            say((hit ? "SPACE " : "      ") + u
                    + "   type=" + (type == null || type.isEmpty() ? "?" : type));
            if (hit) n++;
        }
        say("total=" + n);
    }

    /** userType 不是 public 字段，优先走 getUserType() */
    private static String userTypeOf(Object u) {
        try {
            Method m = u.getClass().getMethod("getUserType");
            return String.valueOf(m.invoke(u));
        } catch (Throwable ignored) {
            try { return String.valueOf(field(u, "userType")); }
            catch (Throwable t2) { return ""; }
        }
    }

    /**
     * 建空间。正常机型建 clone profile（与主空间共享存储、相册同一份）；
     * 建完立刻验存储，挂不上就删号重建为独立存储用户（见 {@link #PRIVATE}）。
     * 输出末行形如 `MODE=shared` / `MODE=private`，调用方据此提示用户。
     */
    /**
     * BFU（开机未解锁）时 /data 还没解密：emulated 卷一个都挂不上，
     * settings 也写不进去。这时建号只会白等 waitStorage 再降级成独立存储，
     * 所以直接拒绝并说清楚。应用内建号不会走到这里（应用只能在 AFU 运行）。
     */
    private static boolean bfu() {
        String v = sh("getprop", "sys.user.0.ce_available").trim();
        if ("true".equals(v)) return false;
        say("ERR 设备处于 BFU（开机未解锁），存储未解密 —— 先解锁屏幕再建空间");
        return true;
    }

    private static void create(String name, String[] disallow) throws Exception {
        if (bfu()) return;

        int uid = -1;
        Object info = null;

        // AOSP 正规路径：clone 是 parent（user 0）的 profile，只有把 parentId 传下去，
        // UserManagerService 才会写 profileGroupId=0。这个字段是同屏的硬前提：
        // 没有父组的 clone 只是一个带 PROFILE 标志的孤儿用户，启动后永远停在
        // INITIALIZING、进程不建、visible=false（2026-11-25 G7 实测）。
        Method m = pick("createProfileForUser");
        if (m != null) {
            Object r = (m.getParameterTypes().length == 4)
                    ? m.invoke(UM, name, CLONE, 0, 0)
                    : m.invoke(UM, name, CLONE, 0, 0, disallow);
            if (r != null) { uid = parseUid(uidOf(r)); info = r; }
        }
        // 有的 ROM 会忽略 parentId，建出没有父组的 clone。dumpsys user 里那一行不带
        // parentId= 就是证据；这种号删掉改用 pm 的显式 --profileOf 重建。
        if (uid > 0 && !hasParentGroup(uid)) {
            say("RETRY clone 没带父组，改用 pm --profileOf 重建");
            sh("pm", "remove-user", String.valueOf(uid));
            waitUserGone(uid, 15000);
            uid = -1;
        }
        if (uid <= 0) {
            String out = sh("pm", "create-user", "--profileOf", "0", "--user-type", CLONE, name);
            Matcher mm = Pattern.compile("id (\\d+)").matcher(out);
            if (!mm.find()) { say("ERR 建空间失败: " + out.trim()); return; }
            uid = Integer.parseInt(mm.group(1));
            info = "{" + uid + ":" + name + ":1000}";
        }
        if (!hasParentGroup(uid)) {
            say("ERR clone 建出来仍无父组（profileGroupId），已删除");
            sh("pm", "stop-user", String.valueOf(uid));
            sh("pm", "remove-user", String.valueOf(uid));
            return;
        }

        say("CREATED " + info);
        // 先把 uid 报给插件再 start-user：launcher 收到 USER_ADDED 就会拉 profile 列表，
        // 那时属性里没有这个 uid 的话，桌面照样会摆出分身应用（G7 实测）。
        addHookSpace(uid);
        sh("am", "start-user", String.valueOf(uid));
        // 存储是异步挂的，25 秒内没起来就如实上报；绝对不再降级成 full.SECONDARY：
        // full 是独立用户，AOSP 必须切用户才显示，建了也打不开（G7 实测）。
        boolean storage = waitStorage(uid, 25000);
        markOwned(uid);
        say(storage ? "MODE=shared" : "MODE=nofs");
    }

    /** 把新空间追加进插件用的属性（逗号分隔）；App 扫描后会整体覆盖，不冲突 */
    private static void addHookSpace(int uid) {
        try {
            String cur = sh("getprop", "persist.sys.cubclone.spaces").trim();
            String want = String.valueOf(uid);
            for (String s : cur.split(",")) {
                if (s.trim().equals(want)) return;
            }
            sh("setprop", "persist.sys.cubclone.spaces", cur.isEmpty() ? want : cur + "," + want);
        } catch (Throwable ignored) {
        }
    }

    /** dumpsys user 里该用户的父组字段（AOSP 打印的是 profileGroupId，名字叫 parentId） */
    private static boolean hasParentGroup(int uid) throws Exception {
        String out = sh("dumpsys", "user");
        for (String line : out.split("\n")) {
            if (line.contains("UserInfo{" + uid + ":")) {
                return line.contains(" parentId=");
            }
        }
        return false;
    }

    /**
     * 写归属标记。主应用只认这个标记：没有它的 full.SECONDARY 会被当成系统的访客/第二用户
     * 直接隐藏，profile.CLONE 会被显示成「非自建」只读。
     * 标记存在该用户自己的 settings 里，随空间一起删。
     */
    private static void markOwned(int uid) {
        String out = sh("settings", "put", "--user", String.valueOf(uid), "secure", "cubclone_owned", "1");
        String back = sh("settings", "get", "--user", String.valueOf(uid), "secure", "cubclone_owned");
        if (!back.trim().startsWith("1")) {
            say("WARN 归属标记写入失败 uid=" + uid + " out=" + out.trim());
        }
    }

    /** 等某个用户的 emulated 卷变成 mounted；超时返回 false */
    private static boolean waitStorage(int uid, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (sh("sm", "list-volumes").contains("emulated;" + uid + " mounted")) return true;
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /** 等某个用户从用户表里消失（remove-user 是异步的，删一半时建新号会撞上限） */
    private static void waitUserGone(int uid, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (!sh("cmd", "user", "list", "--all", "-v").contains("id=" + uid + ",")) return;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static int parseUid(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 从 UserInfo{uid:name:flags} 里抠出 uid */
    private static String uidOf(Object u) {
        String s = String.valueOf(u);
        int i = s.indexOf('{');
        int j = s.indexOf(':', i + 1);
        return (i >= 0 && j > i) ? s.substring(i + 1, j) : "?";
    }

    /**
     * 删除分身。⚠ 这里是唯一会真正抹数据的地方，三道闸：
     * ① uid < 10 一律拒绝（机主是 0，绝不能碰）
     * ② 类型必须是 clone profile 或 CubClone 建的独立存储用户
     * ③ 必须带 cubclone_owned 归属标记（只有我们建的空间才写），
     *    避免把用户自己建的普通用户 / ROM 自带分身当替身删掉
     */
    private static void remove(int uid) throws Exception {
        if (uid < 10) { say("REFUSED: uid<10 是机主/系统用户，不删"); return; }
        if (!isOurs(uid)) { say("REFUSED: " + uid + " 不是 CubClone 建的空间，不删"); return; }
        Object r = invoke(UM, "removeUser", new Class[]{int.class}, uid);
        say("removeUser(" + uid + ") -> " + r);
    }

    private static boolean isOurs(int uid) throws Exception {
        Method gu = UserManager.class.getMethod("getUsers");
        List<?> all = (List<?>) gu.invoke(UM);
        String want = String.valueOf(uid);
        for (Object u : all) {
            if (!want.equals(uidOf(u))) continue;
            String type = String.valueOf(userTypeOf(u));
            if (!CLONE.equals(type) && !PRIVATE.equals(type)) return false;
            String owned = sh("settings", "get", "--user", want, "secure", "cubclone_owned").trim();
            return owned.startsWith("1");
        }
        return false;
    }

    private static void rename(int uid, String name) throws Exception {
        try {
            Method m = UserManager.class.getDeclaredMethod(
                    "setUserName", int.class, String.class);
            m.setAccessible(true);
            m.invoke(UM, uid, name);
        } catch (NoSuchMethodException e) {
            // 退回 AIDL：IUserManager.setUserName(int, String)
            Object ium = field(UM, "mService");
            Method m = ium.getClass().getMethod("setUserName", int.class, String.class);
            m.invoke(ium, uid, name);
        }
        say("renamed " + uid + " -> " + name);
    }

    private static void install(int uid, String pkg) throws Exception {
        Class<?> pmc = Class.forName("android.content.pm.PackageManager");
        Method m = pmc.getMethod("installExistingPackageAsUser", String.class, int.class);
        m.setAccessible(true);
        Object r = m.invoke(PM, pkg, uid);
        say("installExistingPackageAsUser(" + pkg + "," + uid + ") -> " + r);
    }

    /** 先解析出 launcher activity，再跨用户拉起 */
    private static void launch(int uid, String pkg) throws Exception {
        String act = sh("cmd", "package", "resolve-activity", "--brief",
                "-c", "android.intent.category.LAUNCHER", pkg);
        // 输出形如 pkg/.MainActivity
        String comp = null;
        for (String line : act.split("\n")) {
            line = line.trim();
            if (line.contains("/")) { comp = line; break; }
        }
        if (comp == null) { say("ERR 解析不到入口: " + act.trim()); return; }
        say("component=" + comp);
        say(sh("am", "start", "--user", String.valueOf(uid), "-n", comp));
    }

    // ---------- 工具 ----------

    private static Method pick(String name) {
        // 优先 5 参数版本：createProfileForUser(..., disallowedPackages) 才是能「建号时不装」的那个；
        // 4 参数版本没有这个参数，先拿到它的话清单会被静默丢掉（G7 实测过一次）。
        Method four = null;
        for (Method m : UserManager.class.getDeclaredMethods()) {
            if (!name.equals(m.getName())) continue;
            int n = m.getParameterTypes().length;
            if (n == 5) {
                m.setAccessible(true);
                return m;
            }
            if (n == 4) four = m;
        }
        if (four != null) four.setAccessible(true);
        return four;
    }

    private static Object invoke(Object target, String name, Class<?>[] sig, Object... a)
            throws Exception {
        Method m = UserManager.class.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(target, a);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method m = UserManager.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static String sh(String... cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
        } catch (Throwable t) {
            sb.append("exec fail: ").append(t);
        }
        return sb.toString().trim();
    }

    private static void say(String s) { System.out.println(s); }

    private static void unsealHiddenApi() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});
            Object rt = getRuntime.invoke(null);
            setExemptions.invoke(rt, new Object[]{new String[]{"L"}});
        } catch (Throwable ignored) {
        }
    }

    private static Context systemContext() throws Exception {
        android.os.Looper.prepare();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method systemMain = at.getDeclaredMethod("systemMain");
        Object thread = systemMain.invoke(null);
        Method getSystemContext = at.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(thread);
    }
}
