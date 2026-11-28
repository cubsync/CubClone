// CubClone 原生放行：让自建分身拿到共享存储。做两件事，都在媒体存储进程内：
//
// 1) 附加组：媒体存储要读 /data/media/<分身>，sdcardfs 把该目录的分组回显成
//    <uid>09997（AID_EVERYBODY 的每用户派生组）。AOSP 只给主用户和系统自带分身
//    配这组，自建分身没有，于是 lstat 被 DAC 拒绝（EACCES），vold 绑定 Android/data
//    前的 umount 失败，整卷回滚成 unmountable。这里在 zygote 落地附加组的那一刻
//    （仍是 root，setgroups 可用）把每个分身的 <uid>09997 并进去。
// 2) FUSE 跨用户放行：接管 AOSP 的
//    _ZN13mediaprovider4fuse20MediaProviderWrapper17ShouldAllowLookupEji，
//    参数里的 pathUserId 属于分身时返回 true。
// 不改挂载方式、不改任何数据。

#include <jni.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <grp.h>
#include <android/log.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstdarg>
#include <thread>
#include <chrono>
#include <string>
#include <vector>

#include "zygisk.hpp"

#define LOG_TAG "CubCloneNative"

static void logi(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, LOG_TAG, fmt, ap);
    va_end(ap);
}

// 文件日志：logcat 缓冲会在几十秒内被系统日志冲掉，这里写进媒体存储自己的数据目录，
// 出问题后可以直接用 root 读。
static void flog(const char *fmt, ...) {
    char path[160];
    snprintf(path, sizeof(path),
             "/data/user/%d/com.android.providers.media.module/cubclone_native.log",
             (int) (getuid() / 100000));
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd < 0) return;
    char buf[320];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0) {
        if (n > (int) sizeof(buf) - 1) n = sizeof(buf) - 1;
        ssize_t w = write(fd, buf, (size_t) n);
        (void) w;
        w = write(fd, "\n", 1);
        (void) w;
    }
    close(fd);
}

// ---------- 空间列表 ----------

static bool in_spaces(int userId) {
    static char buf[256];
    static long last = 0;
    long now = (long) (std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());
    if (now - last > 1000) {
        last = now;
        buf[0] = 0;
        __system_property_get("persist.sys.cubclone.spaces", buf);
    }
    if (buf[0] == 0) return false;
    std::string s(buf);
    size_t pos = 0;
    while (pos <= s.size()) {
        size_t comma = s.find(',', pos);
        std::string item = s.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
        if (!item.empty() && atoi(item.c_str()) == userId) return true;
        if (comma == std::string::npos) break;
        pos = comma + 1;
    }
    return false;
}

// 分身用户列表：先读属性，读不到就扫描 /data/media 下的数字目录
// （每个已建用户在那儿都有一份，重启后依然在）。
static void collect_space_users(std::vector<int> *out) {
    char buf[256];
    buf[0] = 0;
    __system_property_get("persist.sys.cubclone.spaces", buf);
    if (buf[0] != 0) {
        std::string s(buf);
        size_t pos = 0;
        while (pos <= s.size()) {
            size_t comma = s.find(',', pos);
            std::string item = s.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
            if (!item.empty()) {
                int id = atoi(item.c_str());
                if (id > 0) out->push_back(id);
            }
            if (comma == std::string::npos) break;
            pos = comma + 1;
        }
        if (!out->empty()) return;
    }
    DIR *d = opendir("/data/media");
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d)) != nullptr) {
        if (e->d_name[0] < '1' || e->d_name[0] > '9') continue;
        char *end = nullptr;
        long id = strtol(e->d_name, &end, 10);
        if (end && *end == 0 && id > 0 && id < 100000) out->push_back((int) id);
    }
    closedir(d);
}

// ---------- 极简 arm64 内联钩子 ----------

static bool relocatable(const uint32_t *insn, int n) {
    for (int i = 0; i < n; i++) {
        uint32_t v = insn[i];
        // ADRP / ADR / LDR(literal) / B / BL：PC 相对，直接搬会算错
        if ((v & 0x9F000000u) == 0x90000000u) return false;
        if ((v & 0x9F000000u) == 0x10000000u) return false;
        if ((v & 0x3B000000u) == 0x18000000u) return false;
        if ((v & 0x7C000000u) == 0x14000000u) return false;
    }
    return true;
}

static bool hook_func(void *target, void *replace, void **backup) {
    if (!target || !replace || !backup) return false;
#if !defined(__aarch64__)
    const size_t pg2 = (size_t) sysconf(_SC_PAGESIZE);
    uintptr_t ps2 = (uintptr_t) target & ~(pg2 - 1);
    if (mprotect((void *) ps2, pg2 * 2, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return false;
    uint8_t *tramp2 = (uint8_t *) mmap(nullptr, 256, PROT_READ | PROT_WRITE | PROT_EXEC,
                                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tramp2 == MAP_FAILED) return false;
#if defined(__x86_64__)
    memcpy(tramp2, target, 14);
    uint8_t *t2 = tramp2 + 14;
    t2[0] = 0xFF; t2[1] = 0x25; t2[2] = t2[3] = t2[4] = t2[5] = 0;
    *(uint64_t *) (t2 + 6) = (uint64_t) ((uint8_t *) target + 14);
    uint8_t patch2[14] = {0xFF, 0x25, 0, 0, 0, 0};
    *(uint64_t *) (patch2 + 6) = (uint64_t) replace;
    memcpy(target, patch2, 14);
    __builtin___clear_cache((char *) target, (char *) target + 14);
#elif defined(__i386__)
    memcpy(tramp2, target, 5);
    tramp2[5] = 0xE9;
    *(int32_t *) (tramp2 + 6) = (int32_t) (((uint8_t *) target + 5) - (tramp2 + 10));
    uint8_t *p8 = (uint8_t *) target;
    p8[0] = 0xE9;
    *(int32_t *) (p8 + 1) = (int32_t) ((uint8_t *) replace - (p8 + 5));
    __builtin___clear_cache((char *) target, (char *) target + 5);
#elif defined(__arm__)
    memcpy(tramp2, target, 8);
    ((uint32_t *) tramp2)[2] = 0xE51FF004u;                 // ldr pc, [pc, #-4]
    ((uint32_t *) tramp2)[3] = (uint32_t) ((uint8_t *) target + 8);
    ((uint32_t *) target)[0] = 0xE51FF004u;
    ((uint32_t *) target)[1] = (uint32_t) replace;
    __builtin___clear_cache((char *) target, (char *) target + 8);
#else
    logi("hook_func: unknown ABI, skip");
    return false;
#endif
    *backup = tramp2;
    return true;
#endif
    const size_t page = (size_t) sysconf(_SC_PAGESIZE);
    uintptr_t pageStart = (uintptr_t) target & ~(page - 1);
    if (mprotect((void *) pageStart, page * 2, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        logi("mprotect failed");
        return false;
    }
    uint32_t saved[4];
    memcpy(saved, target, sizeof(saved));
    if (!relocatable(saved, 4)) {
        logi("prologue not relocatable, skip");
        return false;
    }
    // 跳板：原始 4 条指令 + 跳到 target+16
    uint8_t *tramp = (uint8_t *) mmap(nullptr, 128, PROT_READ | PROT_WRITE | PROT_EXEC,
                                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tramp == MAP_FAILED) return false;
    memcpy(tramp, saved, 16);
    uint32_t *t = (uint32_t *) (tramp + 16);
    t[0] = 0x58000050u;                      // ldr x16, #8
    t[1] = 0xD61F0200u;                      // br  x16
    *(uint64_t *) (tramp + 24) = (uint64_t) ((uint8_t *) target + 16);
    __builtin___clear_cache((char *) tramp, (char *) tramp + 48);

    // 目标处：ldr x16,#8 ; br x16 ; <replace>
    uint8_t patch[16];
    uint32_t head[2] = {0x58000050u, 0xD61F0200u};   // ldr x16,#8 ; br x16
    uint64_t dest = (uint64_t) replace;
    memcpy(patch, head, 8);
    memcpy(patch + 8, &dest, 8);
    memcpy((void *) target, patch, sizeof(patch));
    __builtin___clear_cache((char *) target, (char *) target + 32);
    *backup = tramp;
    return true;
}

// ---------- 目标函数 ----------

typedef bool (*allow_fn)(void *self, uint32_t uid, int32_t pathUserId);
typedef bool (*clone_fn)(void *self, uint32_t userId);

static allow_fn g_orig = nullptr;
static clone_fn g_origClone = nullptr;

static bool allow_replacement(void *self, uint32_t uid, int32_t pathUserId) {
    if (uid == 0 || in_spaces(pathUserId)) {
        flog("ShouldAllowLookup uid=%u pathUser=%d ours=%d", uid, pathUserId,
             in_spaces(pathUserId) ? 1 : 0);
    }
    if (uid == 0) {
        static int rootLogs = 0;
        if (rootLogs++ < 40) {
            logi("lookup(root) pathUser=%d ours=%d", pathUserId, in_spaces(pathUserId) ? 1 : 0);
        }
    }
    if (in_spaces(pathUserId)) {
        static int once = 0;
        if (once++ < 8) logi("allow lookup: uid=%u pathUser=%d -> true", uid, pathUserId);
        return true;
    }
    return g_orig ? g_orig(self, uid, pathUserId) : false;
}

static bool clone_replacement(void *self, uint32_t userId) {
    flog("IsAppCloneUser(%u) ours=%d", userId, in_spaces((int) userId) ? 1 : 0);
    if (in_spaces((int) userId)) {
        static int once = 0;
        if (once++ < 8) logi("isAppCloneUser: %u -> true", userId);
        return true;
    }
    return g_origClone ? g_origClone(self, userId) : false;
}

// ---------- 附加组：把分身的 <uid>09997 并进媒体存储进程 ----------
//
// zygote 在落地 uid 之前会用 setgroups() 设置附加组，此刻仍是 root，
// 调用合法。这里做一次并集，之后原样交给 libc。

typedef int (*setgroups_fn)(size_t, const gid_t *);
static setgroups_fn g_origSetgroups = nullptr;

static int setgroups_replacement(size_t size, const gid_t *list) {
    gid_t buf[128];
    size_t n = 0;
    for (size_t i = 0; i < size && n < 128; i++) buf[n++] = list[i];

    std::vector<int> users;
    collect_space_users(&users);
    int added = 0;
    for (int u : users) {
        gid_t gid = (gid_t) (u * 100000 + 9997);   // AID_EVERYBODY 的每用户派生组
        bool dup = false;
        for (size_t i = 0; i < n; i++) {
            if (buf[i] == gid) { dup = true; break; }
        }
        if (dup || n >= 128) continue;
        buf[n++] = gid;
        added++;
    }
    if (added > 0) {
        flog("setgroups %zu -> %zu (+%d)", size, n, added);
        logi("setgroups %zu -> %zu (+%d)", size, n, added);
    }
    return g_origSetgroups ? g_origSetgroups(n, buf) : -1;
}

static void install_setgroups_hook() {
    if (g_origSetgroups) return;
    void *addr = dlsym(RTLD_DEFAULT, "setgroups");
    if (!addr) {
        logi("dlsym setgroups failed");
        return;
    }
    void *backup = nullptr;
    if (!hook_func(addr, (void *) setgroups_replacement, &backup)) {
        flog("hook failed setgroups at %p", addr);
        return;
    }
    g_origSetgroups = (setgroups_fn) backup;
    logi("setgroups hooked at %p", addr);
    flog("setgroups hooked");
}

static void *hook_symbol(void *handle, const char *name, void *replace, void **backup) {
    void *addr = dlsym(handle, name);
    if (!addr) {
        logi("dlsym %s failed", name);
        return nullptr;
    }
    if (!hook_func(addr, replace, backup)) {
        flog("hook failed %s at %p", name, addr);
        return nullptr;
    }
    logi("hooked %s at %p", name, addr);
    flog("hooked %s", name);
    return addr;
}

static void patch_lib(void *handle);

// ---------- 在库被加载的瞬间打补丁 ----------

typedef void *(*dlopen_fn)(const char *, int);
static dlopen_fn g_origDlopen = nullptr;
static thread_local bool g_inHook = false;

static void *dlopen_replacement(const char *name, int flags) {
    void *h = g_origDlopen ? g_origDlopen(name, flags) : nullptr;
    if (!g_inHook && h && name && strstr(name, "fuse_jni")) {
        flog("dlopen caught: %s", name);
        g_inHook = true;
        patch_lib(h);
        g_inHook = false;
    }
    return h;
}

static bool install_loader_hook() {
    void *dl = dlopen("libdl.so", RTLD_NOW);
    if (!dl) return false;
    void *addr = dlsym(dl, "dlopen");
    if (!addr) return false;
    void *backup = nullptr;
    if (!hook_func(addr, (void *) dlopen_replacement, &backup)) return false;
    g_origDlopen = (dlopen_fn) backup;
    logi("dlopen hooked at %p", addr);
    flog("dlopen hooked");
    return true;
}

static void patch_lib(void *handle) {
    if (!handle) return;
    if (!g_orig) {
        void *backup = nullptr;
        if (hook_symbol(handle,
                        "_ZN13mediaprovider4fuse20MediaProviderWrapper17ShouldAllowLookupEji",
                        (void *) allow_replacement, &backup)) {
            g_orig = (allow_fn) backup;
        }
    }
    if (!g_origClone) {
        void *backup = nullptr;
        if (hook_symbol(handle,
                        "_ZN13mediaprovider4fuse20MediaProviderWrapper14IsAppCloneUserEj",
                        (void *) clone_replacement, &backup)) {
            g_origClone = (clone_fn) backup;
        }
    }
}

static void try_install() {
    void *handle = dlopen("libfuse_jni.so", RTLD_NOW);
    if (!handle) {
        return;
    }
    patch_lib(handle);
}

// ---------- Zygisk ----------

class CubCloneNative : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        const char *name = env->GetStringUTFChars(args->nice_name, nullptr);
        is_media = name && strstr(name, "providers.media.module") != nullptr;
        if (name && strstr(name, "providers.media")) flog("process: %s media=%d", name, is_media ? 1 : 0);
        env->ReleaseStringUTFChars(args->nice_name, name);
        if (!is_media) {
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        // 这里还没落地 uid/gid，仍是 root，先把附加组的钩子装上。
        install_setgroups_hook();
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!is_media) return;
        flog("postAppSpecialize uid=%d", (int) getuid());
        // 库是随 FUSE 会话按需加载的，启动时还不在。先接管 dlopen，
        // 在 libfuse_jni 被加载的那一刻立刻打补丁，赶得上 vold 的第一次 lookup。
        install_loader_hook();
        try_install();
        std::thread([] {
            for (int i = 0; i < 600; i++) {
                try_install();
                if (g_orig && g_origClone) return;
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            }
            logi("give up installing hook");
        }).detach();
    }

private:
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
    bool is_media = false;
};

REGISTER_ZYGISK_MODULE(CubCloneNative)
