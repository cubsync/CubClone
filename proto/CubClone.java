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
                case "create":  create(args[1]); break;
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

    private static void create(String name) throws Exception {
        if (bfu()) return;
        // 不再预判「已有 clone 空间没挂」：卷是异步挂载的，刚重启时全都没挂，
        // 预判会把好机器误判成存储损坏、直接降级成独立存储（2026-11-24 连踩两次）。
        // 正确顺序是先建 CLONE，建不成或建完存不上再降级 —— 降级路径本来就在下面。
        Method m = pick("createProfileForUser");
        if (m == null) { say("ERR no createProfileForUser"); return; }
        Object r = (m.getParameterTypes().length == 4)
                ? m.invoke(UM, name, CLONE, 0, 0)
                : m.invoke(UM, name, CLONE, 0, 0, null);
        if (r == null) {
            // Flyme 等 ROM 不认 profile.CLONE（G7 实测：createProfileForUser 直接返回 null，
            // 自带分身走的是自己的 999 空间），但 pm create-user --user-type full 可用 ——
            // 降级而不是直接报错，否则这台机器一个空间都建不出来。
            say("FALLBACK createProfileForUser 返回 null，改用独立存储");
            createPrivate(name);
            return;
        }
        int uid = parseUid(uidOf(r));
        if (uid <= 0) { say("ERR 建号返回的 uid 解析失败: " + r); return; }
        say("CREATED " + r);
        sh("am", "start-user", String.valueOf(uid));
        if (waitStorage(uid, 25000)) {
            markOwned(uid);
            say("MODE=shared");
            return;
        }
        // 共享存储挂不上：vold 在 Android/data 的 lookup 被 MediaProvider 的 FUSE 拒掉（EACCES）
        say("FALLBACK clone 共享存储不可用，改用独立存储（不与主空间共享相册/文件）");
        // stop-user 对刚启动的 clone 用户常回 -4（related users can't be stopped），
        // 但 remove-user 照样能删；删是异步的，必须等它真的从用户表里消失再建新号。
        sh("am", "stop-user", String.valueOf(uid));
        sh("pm", "remove-user", String.valueOf(uid));
        waitUserGone(uid, 15000);
        createPrivate(name);
    }

    /** 建独立存储用户：功能与分身一致（独立应用数据、可 pm uninstall --user），只是不共享主空间文件 */
    private static void createPrivate(String name) throws Exception {
        if (bfu()) return;
        String out = sh("pm", "create-user", "--profileOf", "0", "--user-type", PRIVATE, name);
        say("CREATE " + out.trim());
        Matcher mm = Pattern.compile("id (\\d+)").matcher(out);
        if (!mm.find()) { say("ERR 建独立存储空间失败: " + out.trim()); return; }
        int uid = Integer.parseInt(mm.group(1));
        sh("am", "start-user", String.valueOf(uid));
        if (waitStorage(uid, 12000)) {
            markOwned(uid);
            say("CREATED {" + uid + ":" + name + ":0}");
            say("MODE=private");
        } else {
            say("ERR 独立存储空间也未挂载（uid=" + uid + "）");
        }
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
        for (Method m : UserManager.class.getDeclaredMethods()) {
            if (!name.equals(m.getName())) continue;
            int n = m.getParameterTypes().length;
            if (n == 4 || n == 5) { m.setAccessible(true); return m; }
        }
        return null;
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
