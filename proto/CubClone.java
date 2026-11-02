import android.content.Context;
import android.os.UserManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * wbclone —— 应用分身 CLI（AOSP clone profile）
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
            boolean hit = CLONE.equals(type);
            say((hit ? "CLONE " : "      ") + u
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

    private static void create(String name) throws Exception {
        Method m = pick("createProfileForUser");
        if (m == null) { say("ERR no createProfileForUser"); return; }
        Object r = (m.getParameterTypes().length == 4)
                ? m.invoke(UM, name, CLONE, 0, 0)
                : m.invoke(UM, name, CLONE, 0, 0, null);
        if (r == null) { say("ERR create returned null (上限或权限)"); return; }
        say("CREATED " + r);
        say("初始化请执行: start " + uidOf(r));
    }

    /** 从 UserInfo{uid:name:flags} 里抠出 uid */
    private static String uidOf(Object u) {
        String s = String.valueOf(u);
        int i = s.indexOf('{');
        int j = s.indexOf(':', i + 1);
        return (i >= 0 && j > i) ? s.substring(i + 1, j) : "?";
    }

    /**
     * 删除分身。⚠ 这里是唯一会真正抹数据的地方，两道闸：
     * ① uid < 10 一律拒绝（机主是 0，绝不能碰）
     * ② 必须是 CLONE 类型的用户，避免把机主或普通用户当替身删掉
     */
    private static void remove(int uid) throws Exception {
        if (uid < 10) { say("REFUSED: uid<10 是机主/系统用户，不删"); return; }
        if (!isClone(uid)) { say("REFUSED: " + uid + " 不是 clone 分身，不删"); return; }
        Object r = invoke(UM, "removeUser", new Class[]{int.class}, uid);
        say("removeUser(" + uid + ") -> " + r);
    }

    private static boolean isClone(int uid) throws Exception {
        Method gu = UserManager.class.getMethod("getUsers");
        List<?> all = (List<?>) gu.invoke(UM);
        String want = String.valueOf(uid);
        for (Object u : all) {
            if (!want.equals(uidOf(u))) continue;
            return CLONE.equals(userTypeOf(u));
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
