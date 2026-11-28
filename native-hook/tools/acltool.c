// acltool —— 给目录/文件加一条 POSIX ACL 用户条目，或按指定 uid 复核可达性。
//
//   acltool set <path> <uid> <rwx>     # rwx 是八进制 0-7
//   acltool get <path>
//   acltool check <uid> <path>...
//
// 只用内核的 system.posix_acl_access xattr，不依赖 libacl / setfacl（Android 都没有）。

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>

#define ACL_XATTR "system.posix_acl_access"
#define ACL_VER 2

#define TAG_USER_OBJ 0x01
#define TAG_USER 0x02
#define TAG_GROUP_OBJ 0x04
#define TAG_GROUP 0x08
#define TAG_MASK 0x10
#define TAG_OTHER 0x20
#define ID_UNDEF 0xFFFFFFFFu

struct ent {
    uint16_t tag;
    uint16_t perm;
    uint32_t id;
};

struct aclbuf {
    uint32_t version;
    struct ent e[32];
    int n;
};

static int cmp_ent(const void *a, const void *b) {
    const struct ent *x = a, *y = b;
    // 规范顺序：USER_OBJ, USER, GROUP_OBJ, GROUP, MASK, OTHER
    int rank[] = {[TAG_USER_OBJ] = 0, [TAG_USER] = 1, [TAG_GROUP_OBJ] = 2,
                  [TAG_GROUP] = 3, [TAG_MASK] = 4, [TAG_OTHER] = 5};
    int rx = rank[x->tag & 0xff], ry = rank[y->tag & 0xff];
    if (rx != ry) return rx - ry;
    return (x->id > y->id) - (x->id < y->id);
}

static int load(const char *path, mode_t mode, struct aclbuf *out) {
    out->version = ACL_VER;
    out->n = 0;
    unsigned char raw[512];
    ssize_t len = getxattr(path, ACL_XATTR, raw, sizeof(raw));
    if (len >= 4 + 8) {
        uint32_t ver;
        memcpy(&ver, raw, 4);
        if (ver == ACL_VER) {
            int n = (int) ((len - 4) / 8);
            for (int i = 0; i < n && out->n < 32; i++) {
                memcpy(&out->e[out->n], raw + 4 + i * 8, 8);
                out->n++;
            }
            return 0;
        }
    }
    // 没有 ACL：按 mode 造一份最小集合
    out->e[0] = (struct ent) {TAG_USER_OBJ, (uint16_t) ((mode >> 6) & 7), ID_UNDEF};
    out->e[1] = (struct ent) {TAG_GROUP_OBJ, (uint16_t) ((mode >> 3) & 7), ID_UNDEF};
    out->e[2] = (struct ent) {TAG_MASK, (uint16_t) ((mode >> 3) & 7), ID_UNDEF};
    out->e[3] = (struct ent) {TAG_OTHER, (uint16_t) (mode & 7), ID_UNDEF};
    out->n = 4;
    return 0;
}

static int store(const char *path, struct aclbuf *acl) {
    qsort(acl->e, acl->n, sizeof(struct ent), cmp_ent);
    unsigned char raw[4 + 32 * 8];
    memcpy(raw, &acl->version, 4);
    for (int i = 0; i < acl->n; i++) memcpy(raw + 4 + i * 8, &acl->e[i], 8);
    if (setxattr(path, ACL_XATTR, raw, 4 + acl->n * 8, 0) != 0) {
        fprintf(stderr, "setxattr %s: %s\n", path, strerror(errno));
        return -1;
    }
    return 0;
}

static int cmd_set(const char *path, uid_t uid, int perm) {
    struct stat st;
    if (stat(path, &st) != 0) {
        fprintf(stderr, "stat %s: %s\n", path, strerror(errno));
        return 1;
    }
    struct aclbuf acl;
    load(path, st.st_mode, &acl);

    int replaced = 0, has_mask = 0;
    for (int i = 0; i < acl.n; i++) {
        if (acl.e[i].tag == TAG_USER && acl.e[i].id == (uint32_t) uid) {
            acl.e[i].perm = (uint16_t) perm;
            replaced = 1;
        }
        if (acl.e[i].tag == TAG_MASK) has_mask = 1;
    }
    if (!replaced && acl.n < 32) {
        acl.e[acl.n++] = (struct ent) {TAG_USER, (uint16_t) perm, (uint32_t) uid};
    }
    if (!has_mask && acl.n < 32) {
        uint16_t g = 0;
        for (int i = 0; i < acl.n; i++)
            if (acl.e[i].tag == TAG_GROUP_OBJ) g = acl.e[i].perm;
        acl.e[acl.n++] = (struct ent) {TAG_MASK, g, ID_UNDEF};
    }
    if (store(path, &acl) != 0) return 1;
    printf("ok %s u:%u:%o\n", path, (unsigned) uid, (unsigned) perm);
    return 0;
}

static int cmd_get(const char *path) {
    struct stat st;
    if (stat(path, &st) != 0) {
        fprintf(stderr, "stat %s: %s\n", path, strerror(errno));
        return 1;
    }
    printf("%s mode=%o uid=%u gid=%u\n", path, (unsigned) (st.st_mode & 07777),
           (unsigned) st.st_uid, (unsigned) st.st_gid);
    struct aclbuf acl;
    load(path, st.st_mode, &acl);
    for (int i = 0; i < acl.n; i++) {
        const char *t = "?";
        switch (acl.e[i].tag) {
            case TAG_USER_OBJ: t = "user::"; break;
            case TAG_USER: t = "user:"; break;
            case TAG_GROUP_OBJ: t = "group::"; break;
            case TAG_GROUP: t = "group:"; break;
            case TAG_MASK: t = "mask::"; break;
            case TAG_OTHER: t = "other::"; break;
        }
        if (acl.e[i].tag == TAG_USER || acl.e[i].tag == TAG_GROUP)
            printf("  %s%u:rwx=%o\n", t, (unsigned) acl.e[i].id, (unsigned) acl.e[i].perm);
        else
            printf("  %srwx=%o\n", t, (unsigned) acl.e[i].perm);
    }
    return 0;
}

// 以指定 uid 复核可达性：清空附加组、清空 capabilities（去掉 CAP_DAC_OVERRIDE）
static int cmd_check(uid_t uid, char **paths, int np) {
    if (setgroups(0, NULL) != 0) perror("setgroups");
    if (setgid(uid) != 0) perror("setgid");
    if (setuid(uid) != 0) perror("setuid");
    struct { uint32_t version; int pid; } hdr = {0x20080522, 0};
    struct { uint32_t eff, prm, inh; } data[2] = {{0, 0, 0}, {0, 0, 0}};
    syscall(SYS_capset, &hdr, data);
    printf("as uid=%u: ", (unsigned) getuid());
    for (int i = 0; i < np; i++) {
        struct stat st;
        int rc = stat(paths[i], &st);
        printf("%s=%s ", paths[i], rc == 0 ? "OK" : strerror(errno));
    }
    printf("\n");
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: acltool set <path> <uid> <perm> | get <path> | check <uid> <path>...\n");
        return 2;
    }
    if (!strcmp(argv[1], "set") && argc == 5) return cmd_set(argv[2], (uid_t) atoi(argv[3]), (int) strtol(argv[4], NULL, 8));
    if (!strcmp(argv[1], "get")) return cmd_get(argv[2]);
    if (!strcmp(argv[1], "check")) return cmd_check((uid_t) atoi(argv[2]), argv + 3, argc - 3);
    fprintf(stderr, "bad args\n");
    return 2;
}
