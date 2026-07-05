/**
 * libfakeroot_preload.so — TrinityVirtual LD_PRELOAD injection library.
 *
 * Injected into target processes via LD_PRELOAD (child processes) and
 * dlopen(RTLD_GLOBAL) (host process). Overrides key libc symbols so
 * root-detection calls inside guest apps return values consistent with a
 * rooted device — without touching the kernel mount namespace (blocked by
 * SELinux untrusted_app on Android 12+).
 *
 * Intercepted symbols:
 *   getuid / geteuid / getgid / getegid  → return 0  (root UID/GID)
 *   access(su_path, ...)                 → return 0  (file present & executable)
 *   faccessat(su_path, ...)              → return 0
 *   stat / lstat / __xstat / __lxstat    → fake executable stat for su paths
 *   open(su_path, O_RDONLY)              → redirect to /dev/null (safe read)
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <string.h>
#include <stddef.h>
#include <stdarg.h>
#include <android/log.h>

#define FAKETAG "FakeRoot"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, FAKETAG, __VA_ARGS__)

// ── Known su binary paths ──────────────────────────────────────────────────
static const char* const SU_PATHS[] = {
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/su/bin/su",
    "/su/xbin/su",
    "/magisk/.core/bin/su",
    "/data/adb/magisk/busybox",
    nullptr
};

static bool is_su_path(const char* path) {
    if (!path) return false;
    for (int i = 0; SU_PATHS[i] != nullptr; i++) {
        if (__builtin_strcmp(path, SU_PATHS[i]) == 0) return true;
    }
    return false;
}

// ── UID / GID overrides ────────────────────────────────────────────────────

__attribute__((visibility("default")))
uid_t getuid(void) {
    LOGD("getuid() → 0 (faked)");
    return 0;
}

__attribute__((visibility("default")))
uid_t geteuid(void) {
    LOGD("geteuid() → 0 (faked)");
    return 0;
}

__attribute__((visibility("default")))
gid_t getgid(void) {
    return 0;
}

__attribute__((visibility("default")))
gid_t getegid(void) {
    return 0;
}

// ── access() — su paths appear present & executable ───────────────────────

__attribute__((visibility("default")))
int access(const char* pathname, int mode) {
    if (is_su_path(pathname)) {
        LOGD("access(%s, %d) → 0 (faked su)", pathname, mode);
        return 0;
    }
    typedef int (*fn_t)(const char*, int);
    static fn_t real_fn = nullptr;
    if (!real_fn) real_fn = (fn_t)dlsym(RTLD_NEXT, "access");
    return real_fn ? real_fn(pathname, mode) : -1;
}

// ── faccessat() ───────────────────────────────────────────────────────────

__attribute__((visibility("default")))
int faccessat(int dirfd, const char* pathname, int mode, int flags) {
    if (is_su_path(pathname)) {
        LOGD("faccessat(%s) → 0 (faked su)", pathname);
        return 0;
    }
    typedef int (*fn_t)(int, const char*, int, int);
    static fn_t real_fn = nullptr;
    if (!real_fn) real_fn = (fn_t)dlsym(RTLD_NEXT, "faccessat");
    return real_fn ? real_fn(dirfd, pathname, mode, flags) : -1;
}

// ── Shared fake stat filler ────────────────────────────────────────────────

static void fill_fake_su_stat(struct stat* buf) {
    memset(buf, 0, sizeof(*buf));
    buf->st_mode    = S_IFREG | 0755;   // regular file, rwxr-xr-x
    buf->st_size    = 2048;
    buf->st_uid     = 0;
    buf->st_gid     = 0;
    buf->st_nlink   = 1;
    buf->st_blksize = 512;
    buf->st_blocks  = 4;
}

// ── stat() / lstat() ──────────────────────────────────────────────────────

__attribute__((visibility("default")))
int stat(const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*fn_t)(const char*, struct stat*);
    static fn_t real_fn = nullptr;
    if (!real_fn) real_fn = (fn_t)dlsym(RTLD_NEXT, "stat");
    return real_fn ? real_fn(path, buf) : -1;
}

__attribute__((visibility("default")))
int lstat(const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*fn_t)(const char*, struct stat*);
    static fn_t real_fn = nullptr;
    if (!real_fn) real_fn = (fn_t)dlsym(RTLD_NEXT, "lstat");
    return real_fn ? real_fn(path, buf) : -1;
}

// ── __xstat / __lxstat (used by some libc internals) ─────────────────────

__attribute__((visibility("default")))
int __xstat(int ver, const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*fn_t)(int, const char*, struct stat*);
    static fn_t real_fn = nullptr;
    if (!real_fn) real_fn = (fn_t)dlsym(RTLD_NEXT, "__xstat");
    return real_fn ? real_fn(ver, path, buf) : -1;
}

__attribute__((visibility("default")))
int __lxstat(int ver, const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*fn_t)(int, const char*, struct stat*);
    static fn_t real_fn = nullptr;
    if (!real_fn) real_fn = (fn_t)dlsym(RTLD_NEXT, "__lxstat");
    return real_fn ? real_fn(ver, path, buf) : -1;
}

// ── open() — redirect O_RDONLY on su paths to /dev/null ──────────────────

__attribute__((visibility("default")))
int open(const char* pathname, int flags, ...) {
    typedef int (*fn_t)(const char*, int, ...);
    static fn_t real_fn = nullptr;
    if (!real_fn) real_fn = (fn_t)dlsym(RTLD_NEXT, "open");

    if (is_su_path(pathname) && (flags & O_ACCMODE) == O_RDONLY) {
        LOGD("open(%s, O_RDONLY) → /dev/null (faked su)", pathname);
        return real_fn ? real_fn("/dev/null", O_RDONLY) : -1;
    }

    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = (mode_t)va_arg(ap, int);
        va_end(ap);
        return real_fn ? real_fn(pathname, flags, mode) : -1;
    }
    return real_fn ? real_fn(pathname, flags) : -1;
}
