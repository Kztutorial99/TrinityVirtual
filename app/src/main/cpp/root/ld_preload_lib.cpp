/**
 * libfakeroot_preload.so — TrinityVirtual LD_PRELOAD injection library.
 *
 * Loaded RTLD_GLOBAL into host process + all children via LD_PRELOAD.
 * Three stealth layers:
 *
 *  Layer 1 — UID/GID spoof:
 *    getuid/geteuid/getgid/getegid → 0
 *
 *  Layer 2 — su binary presence:
 *    access/faccessat/stat/lstat/__xstat/__lxstat/open
 *
 *  Layer 3 — Integrity check bypass:
 *    __system_property_get → Samsung S23 Ultra profile; filter suspicious strings
 *    fopen + open + openat for /proc/self/maps → memfd with filtered content
 *    (covers both libc callers AND Java File.readText via open/openat)
 *
 * Exported config API:
 *    void fakeroot_set_prop(const char* key, const char* value)
 *    void fakeroot_add_hidden_pattern(const char* pattern)
 *    void fakeroot_reset_props(void)
 *
 * Thread-safety:
 *    g_initialized uses std::atomic; contains_pattern reads under lock.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <string.h>
#include <stddef.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <pthread.h>
#include <atomic>
#include <sys/system_properties.h>
#include <android/log.h>

#define FAKETAG  "FakeRoot"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, FAKETAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  FAKETAG, __VA_ARGS__)

// ══════════════════════════════════════════════════════════════════════════════
// § 1 — Configuration tables
// ══════════════════════════════════════════════════════════════════════════════

#define MAX_PROPS    64
#define MAX_PATTERNS 32
#define KEY_MAX      92
#define VAL_MAX      PROP_VALUE_MAX   // 92

struct PropEntry { char key[KEY_MAX]; char val[VAL_MAX]; };

static pthread_mutex_t       g_lock       = PTHREAD_MUTEX_INITIALIZER;
static PropEntry             g_props[MAX_PROPS];
static int                   g_prop_count = 0;
static char                  g_patterns[MAX_PATTERNS][64];
static int                   g_pat_count  = 0;
static std::atomic<bool>     g_initialized{false};

static const PropEntry DEFAULT_PROPS[] = {
    { "ro.product.manufacturer",  "samsung"                                                   },
    { "ro.product.brand",         "samsung"                                                   },
    { "ro.product.model",         "SM-S918B"                                                  },
    { "ro.product.device",        "dm3q"                                                      },
    { "ro.product.name",          "dm3qxxx"                                                   },
    { "ro.build.fingerprint",
      "samsung/dm3qxxx/dm3q:13/TP1A.220624.014/S918BXXS3BWF1:user/release-keys"              },
    { "ro.build.version.release", "13"                                                        },
    { "ro.build.version.sdk",     "33"                                                        },
    { "ro.build.id",              "TP1A.220624.014"                                            },
    { "ro.build.type",            "user"                                                      },
    { "ro.build.tags",            "release-keys"                                              },
    { "ro.serialno",              "R5CW301234X"                                               },
    { "ro.hardware",              "qcom"                                                      },
    { "ro.product.cpu.abi",       "arm64-v8a"                                                 },
    { "ro.secure",                "1"                                                         },
    { "ro.debuggable",            "0"                                                         },
    { "ro.boot.verifiedbootstate","green"                                                     },
    { "ro.boot.veritymode",       "enforcing"                                                 },
};

static const char* DEFAULT_PATTERNS[] = {
    "qemu", "goldfish", "ranchu", "android_x86",
    "magisk", "zygisk", "xposed", "edxposed",
    "substrate", "frida", "gadget",
    "trinity", "fakeroot", "virtualapp", "vmos",
    nullptr
};

// ── Internal helpers ───────────────────────────────────────────────────────

static void ensure_initialized() {
    if (g_initialized.load(std::memory_order_acquire)) return;
    pthread_mutex_lock(&g_lock);
    if (!g_initialized.load(std::memory_order_relaxed)) {
        for (auto& e : DEFAULT_PROPS) {
            if (g_prop_count >= MAX_PROPS) break;
            strncpy(g_props[g_prop_count].key, e.key, KEY_MAX - 1);
            strncpy(g_props[g_prop_count].val, e.val, VAL_MAX - 1);
            g_prop_count++;
        }
        for (int i = 0; DEFAULT_PATTERNS[i]; i++) {
            if (g_pat_count >= MAX_PATTERNS) break;
            strncpy(g_patterns[g_pat_count++], DEFAULT_PATTERNS[i], 63);
        }
        g_initialized.store(true, std::memory_order_release);
        LOGI("prop table: %d props, %d patterns", g_prop_count, g_pat_count);
    }
    pthread_mutex_unlock(&g_lock);
}

// Must be called with g_lock held (or during single-thread init)
static const char* lookup_prop_locked(const char* key) {
    for (int i = 0; i < g_prop_count; i++)
        if (strncmp(g_props[i].key, key, KEY_MAX) == 0)
            return g_props[i].val;
    return nullptr;
}

// Contains_pattern: reads under lock to prevent data race with writers
static bool contains_pattern(const char* str) {
    if (!str) return false;
    pthread_mutex_lock(&g_lock);
    bool found = false;
    for (int i = 0; i < g_pat_count && !found; i++)
        if (g_patterns[i][0] && strcasestr(str, g_patterns[i]))
            found = true;
    pthread_mutex_unlock(&g_lock);
    return found;
}

static bool is_su_path(const char* path) {
    if (!path) return false;
    static const char* const SU[] = {
        "/system/bin/su",  "/system/xbin/su", "/sbin/su",
        "/su/bin/su",      "/su/xbin/su",     "/magisk/.core/bin/su",
        "/data/adb/magisk/busybox", nullptr
    };
    for (int i = 0; SU[i]; i++)
        if (strcmp(path, SU[i]) == 0) return true;
    return false;
}

// ══════════════════════════════════════════════════════════════════════════════
// § 2 — Exported config API
// ══════════════════════════════════════════════════════════════════════════════

extern "C" __attribute__((visibility("default")))
void fakeroot_set_prop(const char* key, const char* value) {
    if (!key || !value) return;
    ensure_initialized();
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_prop_count; i++) {
        if (strncmp(g_props[i].key, key, KEY_MAX) == 0) {
            strncpy(g_props[i].val, value, VAL_MAX - 1);
            pthread_mutex_unlock(&g_lock);
            return;
        }
    }
    if (g_prop_count < MAX_PROPS) {
        strncpy(g_props[g_prop_count].key, key,   KEY_MAX - 1);
        strncpy(g_props[g_prop_count].val, value, VAL_MAX - 1);
        g_prop_count++;
    }
    pthread_mutex_unlock(&g_lock);
}

extern "C" __attribute__((visibility("default")))
void fakeroot_add_hidden_pattern(const char* pattern) {
    if (!pattern || !*pattern) return;
    ensure_initialized();
    pthread_mutex_lock(&g_lock);
    if (g_pat_count < MAX_PATTERNS)
        strncpy(g_patterns[g_pat_count++], pattern, 63);
    pthread_mutex_unlock(&g_lock);
}

extern "C" __attribute__((visibility("default")))
void fakeroot_reset_props() {
    pthread_mutex_lock(&g_lock);
    g_prop_count = 0;
    g_pat_count  = 0;
    pthread_mutex_unlock(&g_lock);
    g_initialized.store(false, std::memory_order_release);
    ensure_initialized();
}

// ══════════════════════════════════════════════════════════════════════════════
// § 3 — UID / GID
// ══════════════════════════════════════════════════════════════════════════════

__attribute__((visibility("default"))) uid_t getuid(void)  { return 0; }
__attribute__((visibility("default"))) uid_t geteuid(void) { return 0; }
__attribute__((visibility("default"))) gid_t getgid(void)  { return 0; }
__attribute__((visibility("default"))) gid_t getegid(void) { return 0; }

// ══════════════════════════════════════════════════════════════════════════════
// § 4 — System property hook
// ══════════════════════════════════════════════════════════════════════════════

__attribute__((visibility("default")))
int __system_property_get(const char* name, char* value) {
    ensure_initialized();
    pthread_mutex_lock(&g_lock);
    const char* ov = lookup_prop_locked(name);
    if (ov) {
        strncpy(value, ov, PROP_VALUE_MAX - 1);
        value[PROP_VALUE_MAX - 1] = '\0';
        int len = (int)strlen(value);
        pthread_mutex_unlock(&g_lock);
        return len;
    }
    pthread_mutex_unlock(&g_lock);

    typedef int (*Fn)(const char*, char*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "__system_property_get");
    if (!real) { value[0] = '\0'; return 0; }

    int ret = real(name, value);
    if (ret > 0 && contains_pattern(value)) { value[0] = '\0'; ret = 0; }
    return ret;
}

// ══════════════════════════════════════════════════════════════════════════════
// § 5 — su binary hooks
// ══════════════════════════════════════════════════════════════════════════════

__attribute__((visibility("default")))
int access(const char* pathname, int mode) {
    if (is_su_path(pathname)) return 0;
    typedef int (*Fn)(const char*, int);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "access");
    return real ? real(pathname, mode) : -1;
}

__attribute__((visibility("default")))
int faccessat(int dirfd, const char* pathname, int mode, int flags) {
    if (is_su_path(pathname)) return 0;
    typedef int (*Fn)(int, const char*, int, int);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "faccessat");
    return real ? real(dirfd, pathname, mode, flags) : -1;
}

static void fill_fake_su_stat(struct stat* buf) {
    memset(buf, 0, sizeof(*buf));
    buf->st_mode = S_IFREG | 0755; buf->st_size = 2048;
    buf->st_uid = 0; buf->st_gid = 0; buf->st_nlink = 1;
}

__attribute__((visibility("default")))
int stat(const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*Fn)(const char*, struct stat*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "stat");
    return real ? real(path, buf) : -1;
}

__attribute__((visibility("default")))
int lstat(const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*Fn)(const char*, struct stat*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "lstat");
    return real ? real(path, buf) : -1;
}

__attribute__((visibility("default")))
int __xstat(int ver, const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*Fn)(int, const char*, struct stat*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "__xstat");
    return real ? real(ver, path, buf) : -1;
}

__attribute__((visibility("default")))
int __lxstat(int ver, const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_fake_su_stat(buf); return 0; }
    typedef int (*Fn)(int, const char*, struct stat*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "__lxstat");
    return real ? real(ver, path, buf) : -1;
}

// ══════════════════════════════════════════════════════════════════════════════
// § 6 — /proc/self/maps  filtering (covers libc + Java/open paths)
//
// Three entry points ALL share the same filtered-memfd helper:
//  • fopen("/proc/self/maps", ...)     — libc callers
//  • open("/proc/self/maps", ...)      — low-level C callers
//  • openat(AT_FDCWD, "/proc/self/maps", ...) — Java File / NDK callers
//
// This closes the gap found in code review where Java File.readText() used
// open/openat and bypassed the fopen hook.
// ══════════════════════════════════════════════════════════════════════════════

#define PROC_MAPS "/proc/self/maps"

static bool should_hide_map_line(const char* line) {
    return contains_pattern(line);
}

// Create a memfd containing filtered /proc/self/maps.
// real_open must be the RTLD_NEXT open so we don't re-enter ourselves.
static int make_filtered_maps_fd() {
    typedef int (*open_fn)(const char*, int, ...);
    static const open_fn real_open = (open_fn)dlsym(RTLD_NEXT, "open");

    int src_fd = real_open ? real_open(PROC_MAPS, O_RDONLY) : -1;
    if (src_fd < 0) return -1;

    FILE* src = fdopen(src_fd, "r");
    if (!src) { close(src_fd); return -1; }

    int mfd = (int)syscall(__NR_memfd_create, "maps_clean", 1 /*MFD_CLOEXEC*/);
    if (mfd < 0) { fclose(src); return -1; }

    char line[512];
    while (fgets(line, sizeof(line), src)) {
        if (!should_hide_map_line(line))
            write(mfd, line, strlen(line));
    }
    fclose(src);  // also closes src_fd
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

static bool is_proc_maps(const char* path) {
    return path && strcmp(path, PROC_MAPS) == 0;
}

// ── fopen hook ────────────────────────────────────────────────────────────────
__attribute__((visibility("default")))
FILE* fopen(const char* path, const char* mode) {
    typedef FILE* (*Fn)(const char*, const char*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "fopen");

    if (is_proc_maps(path)) {
        int fd = make_filtered_maps_fd();
        if (fd >= 0) { FILE* f = fdopen(fd, "r"); if (f) return f; close(fd); }
    }
    return real ? real(path, mode) : nullptr;
}

__attribute__((visibility("default")))
FILE* fopen64(const char* path, const char* mode) {
    return fopen(path, mode);
}

// ── open hook — covers Java File.readText() and NDK low-level callers ─────────
__attribute__((visibility("default")))
int open(const char* pathname, int flags, ...) {
    typedef int (*Fn)(const char*, int, ...);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "open");

    if (is_proc_maps(pathname) && (flags & O_ACCMODE) == O_RDONLY) {
        int fd = make_filtered_maps_fd();
        if (fd >= 0) return fd;
        // fallback: return real maps
    }

    if (is_su_path(pathname) && (flags & O_ACCMODE) == O_RDONLY)
        return real ? real("/dev/null", O_RDONLY) : -1;

    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags);
        mode_t mode = (mode_t)va_arg(ap, int); va_end(ap);
        return real ? real(pathname, flags, mode) : -1;
    }
    return real ? real(pathname, flags) : -1;
}

// ── openat hook — covers AT_FDCWD callers (ART / JVM file reads) ─────────────
__attribute__((visibility("default")))
int openat(int dirfd, const char* pathname, int flags, ...) {
    typedef int (*Fn)(int, const char*, int, ...);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "openat");

    if (is_proc_maps(pathname) && (flags & O_ACCMODE) == O_RDONLY) {
        int fd = make_filtered_maps_fd();
        if (fd >= 0) return fd;
    }

    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags);
        mode_t mode = (mode_t)va_arg(ap, int); va_end(ap);
        return real ? real(dirfd, pathname, flags, mode) : -1;
    }
    return real ? real(dirfd, pathname, flags) : -1;
}

// ══════════════════════════════════════════════════════════════════════════════
// § 7 — Constructor
// ══════════════════════════════════════════════════════════════════════════════

__attribute__((constructor))
static void fakeroot_init() {
    ensure_initialized();
    LOGI("libfakeroot_preload: uid/prop/maps(fopen+open+openat)/su hooks active");
}
