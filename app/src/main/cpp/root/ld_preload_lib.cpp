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
 *   access / faccessat (su paths)        → return 0  (file present & exec)
 *   stat / lstat / __xstat / __lxstat    → fake executable stat for su paths
 *   open (su paths, O_RDONLY)            → redirect to /dev/null (safe read)
 *
 * Thread-safety:
 *   All RTLD_NEXT lookups use C++11 guaranteed thread-safe static-local
 *   initialisation (ISO C++11 §6.7 [stmt.dcl] ¶4). No mutex needed.
 *
 * ABI compatibility:
 *   Compiled for arm64-v8a / armeabi-v7a / x86_64 as declared in
 *   app/build.gradle → ndk { abiFilters }.
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

// ── Known su binary locations ──────────────────────────────────────────────
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
    for (int i = 0; SU_PATHS[i] != nullptr; i++)
        if (__builtin_strcmp(path, SU_PATHS[i]) == 0) return true;
    return false;
}

// ── Resolve real libc symbol exactly once, thread-safely ──────────────────
//
//   C++11 §6.7 ¶4: "If control enters the declaration concurrently while
//   the variable is being initialized, the concurrent execution shall wait
//   for completion of the initialization."
//
//   Pattern used throughout this file:
//     static const SomeFn real_fn = (SomeFn)dlsym(RTLD_NEXT, "symbol");
//
//   The initialiser runs once and is protected by the compiler-generated
//   guard variable.  Subsequent calls use the cached pointer directly.

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

// ── access() ──────────────────────────────────────────────────────────────

__attribute__((visibility("default")))
int access(const char* pathname, int mode) {
    if (is_su_path(pathname)) {
        LOGD("access(%s, %d) → 0 (faked su)", pathname, mode);
        return 0;
    }
    typedef int (*Fn)(const char*, int);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "access");   // thread-safe init
    return real ? real(pathname, mode) : -1;
}

// ── faccessat() ───────────────────────────────────────────────────────────

__attribute__((visibility("default")))
int faccessat(int dirfd, const char* pathname, int mode, int flags) {
    if (is_su_path(pathname)) {
        LOGD("faccessat(%s) → 0 (faked su)", pathname);
        return 0;
    }
    typedef int (*Fn)(int, const char*, int, int);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "faccessat"); // thread-safe init
    return real ? real(dirfd, pathname, mode, flags) : -1;
}

// ── Shared stat filler ────────────────────────────────────────────────────

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

// ── stat() ────────────────────────────────────────────────────────────────

__attribute__((visibility("default")))
int stat(const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*Fn)(const char*, struct stat*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "stat");
    return real ? real(path, buf) : -1;
}

// ── lstat() ───────────────────────────────────────────────────────────────

__attribute__((visibility("default")))
int lstat(const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*Fn)(const char*, struct stat*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "lstat");
    return real ? real(path, buf) : -1;
}

// ── __xstat() — used internally by some Bionic versions ──────────────────

__attribute__((visibility("default")))
int __xstat(int ver, const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*Fn)(int, const char*, struct stat*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "__xstat");
    return real ? real(ver, path, buf) : -1;
}

// ── __lxstat() ────────────────────────────────────────────────────────────

__attribute__((visibility("default")))
int __lxstat(int ver, const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*Fn)(int, const char*, struct stat*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "__lxstat");
    return real ? real(ver, path, buf) : -1;
}

// ── open() ────────────────────────────────────────────────────────────────
//
// Redirect O_RDONLY opens of su paths to /dev/null so read() succeeds
// without crashing (apps that open+read to verify binary content).
// O_CREAT is handled correctly by forwarding the mode_t va_arg.

__attribute__((visibility("default")))
int open(const char* pathname, int flags, ...) {
    typedef int (*Fn)(const char*, int, ...);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "open");  // thread-safe init

    if (is_su_path(pathname) && (flags & O_ACCMODE) == O_RDONLY) {
        LOGD("open(%s, O_RDONLY) → /dev/null (faked su)", pathname);
        return real ? real("/dev/null", O_RDONLY) : -1;
    }
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = (mode_t)va_arg(ap, int);
        va_end(ap);
        return real ? real(pathname, flags, mode) : -1;
    }
    return real ? real(pathname, flags) : -1;
}
