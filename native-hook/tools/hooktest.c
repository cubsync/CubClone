// 单独验证跳转引擎（与 module.cpp 里 hook_func 的非 arm64 分支同代码）。
// 不需要 root：hook 的是本进程自己的函数。
#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

__attribute__((noinline)) static int target(int x) {
    __asm__ __volatile__("nop;nop;nop;nop;nop;nop;nop;nop;nop;nop;nop;nop;nop;nop;");
    return x * 3 + 1;
}

static int (*backup)(int) = 0;

__attribute__((noinline)) static int replacement(int x) { return x + 100; }

int main(void) {
    void *t = (void *) target;
    const size_t pg = (size_t) sysconf(_SC_PAGESIZE);
    uintptr_t ps = (uintptr_t) t & ~(pg - 1);
    if (mprotect((void *) ps, pg * 2, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        perror("mprotect");
        return 1;
    }
    uint8_t *tramp = (uint8_t *) mmap(0, 256, PROT_READ | PROT_WRITE | PROT_EXEC,
                                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tramp == MAP_FAILED) return 1;
#if defined(__x86_64__)
    memcpy(tramp, t, 14);
    uint8_t *t2 = tramp + 12;
    t2[0] = 0xFF; t2[1] = 0x25; t2[2] = t2[3] = t2[4] = t2[5] = 0;
    *(uint64_t *) (t2 + 6) = (uint64_t) ((uint8_t *) t + 14);
    uint8_t patch[14] = {0xFF, 0x25, 0, 0, 0, 0};
    *(uint64_t *) (patch + 6) = (uint64_t) replacement;
    memcpy(t, patch, 14);
    __builtin___clear_cache((char *) t, (char *) t + 14);
#elif defined(__i386__)
    memcpy(tramp, t, 5);
    tramp[5] = 0xE9;
    *(int32_t *) (tramp + 6) = (int32_t) (((uint8_t *) t + 5) - (tramp + 10));
    uint8_t *p8 = (uint8_t *) t;
    p8[0] = 0xE9;
    *(int32_t *) (p8 + 1) = (int32_t) ((uint8_t *) replacement - (p8 + 5));
    __builtin___clear_cache((char *) t, (char *) t + 5);
#else
#error "only x86/x86_64 in this test"
#endif
    backup = (int (*)(int)) tramp;
    printf("patched=%d via_backup=%d\n", target(1), backup(1));
    return !(target(1) == 101 && backup(1) == 2);
}
