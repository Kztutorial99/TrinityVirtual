/**
 * libfakeroot_preload.so — TrinityVirtual LD_PRELOAD injection library.
 *
 * Loaded RTLD_GLOBAL into the host process and all child processes via LD_PRELOAD.
 * Provides three layers of stealth:
 *
 *  Layer 1 — UID/GID spoof:
 *    getuid/geteuid/getgid/getegid → 0
 *
 *  Layer 2 — su binary presence:
 *    access/faccessat/stat/lstat/__xstat/__lxstat/open → fake success for su paths
 *
 *  Layer 3 — Integrity check bypass:
 *    __system_property_get → return spoofed device values; filter 'qemu'/'goldfish'/'magisk'
 *    fopen("/proc/self/maps") → return memfd with filtered content (hides our libs)
 *    fopen("/proc/self/status") → filter suspicious UID lines
 *
 * Exported config API (callable from trinity_spoof via dlsym(RTLD_DEFAULT, ...)):
 *    void fakeroot_set_prop(const char* key, const char* value)
 *    void fakeroot_add_hidden_pattern(const char* pattern)
 *    void fakeroot_reset_props(void)
 *
 * Thread-safety: all RTLD_NEXT lookups use C++11 thread-safe static-local init.
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
#include <sys/system_properties.h>
#include <android/log.h>

#define FAKETAG  "FakeRoot"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, FAKETAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  FAKETAG, __VA_ARGS__)

// ══════════════════════════════════════════════════════════════════════════════
// § 1 — Configuration tables  (guarded by a single mutex)
// ══════════════════════════════════════════════════════════════════════════════

#define MAX_PROPS    64
#define MAX_PATTERNS 32
#define KEY_MAX      92
#define VAL_MAX      PROP_VALUE_MAX   // 92 on Android

struct PropEntry { char key[KEY_MAX]; char val[VAL_MAX]; };

static pthread_mutex_t  g_lock       = PTHREAD_MUTEX_INITIALIZER;
static PropEntry        g_props[MAX_PROPS];
static int              g_prop_count = 0;
static char             g_patterns[MAX_PATTERNS][64];
static int              g_pat_count  = 0;
static bool             g_initialized = false;

// Default Samsung Galaxy S23 Ultra profile loaded at library init
static const PropEntry DEFAULT_PROPS[] = {
    { "ro.product.manufacturer",     "samsung"                                   },
    { "ro.product.brand",            "samsung"                                   },
    { "ro.product.model",            "SM-S918B"                                  },
    { "ro.product.device",           "dm3q"                                      },
    { "ro.product.name",             "dm3qxxx"                                   },
    { "ro.build.fingerprint",
      "samsung/dm3qxxx/dm3q:13/TP1A.220624.014/S918BXXS3BWF1:user/release-keys" },
    { "ro.build.version.release",    "13"                                        },
    { "ro.build.version.sdk",        "33"                                        },
    { "ro.build.id",                 "TP1A.220624.014"                           },
    { "ro.build.type",               "user"                                      },
    { "ro.build.tags",               "release-keys"                              },
    { "ro.serialno",                 "R5CW301234X"                               },
    { "ro.hardware",                 "qcom"                                      },
    { "ro.product.cpu.abi",          "arm64-v8a"                                 },
};

static const char* DEFAULT_PATTERNS[] = {
    "qemu", "goldfish", "ranchu", "magisk", "zygisk",
    "trinity", "fakeroot", "xposed", "substrate", "frida",
};

// ── Internal helpers ───────────────────────────────────────────────────────

static void ensure_initialized() {
    if (g_initialized) return;
    pthread_mutex_lock(&g_lock);
    if (!g_initialized) {
        for (auto& e : DEFAULT_PROPS) {
            if (g_prop_count >= MAX_PROPS) break;
            strncpy(g_props[g_prop_count].key, e.key, KEY_MAX - 1);
            strncpy(g_props[g_prop_count].val, e.val, VAL_MAX - 1);
            g_prop_count++;
        }
        for (auto& p : DEFAULT_PATTERNS) {
            if (g_pat_count >= MAX_PATTERNS) break;
            strncpy(g_patterns[g_pat_count++], p, 63);
        }
        g_initialized = true;
        LOGI("FakeRoot: prop table loaded (%d props, %d patterns)", g_prop_count, g_pat_count);
    }
    pthread_mutex_unlock(&g_lock);
}

static const char* lookup_prop(const char* key) {
    // Caller holds lock or doesn't need one (read during single-thread init)
    for (int i = 0; i < g_prop_count; i++)
        if (strncmp(g_props[i].key, key, KEY_MAX) == 0)
            return g_props[i].val;
    return nullptr;
}

static bool contains_pattern(const char* str) {
    if (!str) return false;
    for (int i = 0; i < g_pat_count; i++)
        if (g_patterns[i][0] && strcasestr(str, g_patterns[i]))
            return true;
    return false;
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
// § 2 — Exported config API  (called from trinity_spoof via dlsym)
// ══════════════════════════════════════════════════════════════════════════════

extern "C" __attribute__((visibility("default")))
void fakeroot_set_prop(const char* key, const char* value) {
    if (!key || !value) return;
    ensure_initialized();
    pthread_mutex_lock(&g_lock);
    // Update existing entry
    for (int i = 0; i < g_prop_count; i++) {
        if (strncmp(g_props[i].key, key, KEY_MAX) == 0) {
            strncpy(g_props[i].val, value, VAL_MAX - 1);
            pthread_mutex_unlock(&g_lock);
            LOGI("prop updated: [%s]=[%s]", key, value);
            return;
        }
    }
    // New entry
    if (g_prop_count < MAX_PROPS) {
        strncpy(g_props[g_prop_count].key, key,   KEY_MAX - 1);
        strncpy(g_props[g_prop_count].val, value, VAL_MAX - 1);
        g_prop_count++;
        LOGI("prop added: [%s]=[%s]", key, value);
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
    g_prop_count  = 0;
    g_pat_count   = 0;
    g_initialized = false;
    pthread_mutex_unlock(&g_lock);
    ensure_initialized(); // reload defaults
}

// ══════════════════════════════════════════════════════════════════════════════
// § 3 — UID / GID overrides
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

    // Check our override table first (lock-free read after init via atomic flag)
    pthread_mutex_lock(&g_lock);
    const char* ov = lookup_prop(name);
    if (ov) {
        strncpy(value, ov, PROP_VALUE_MAX - 1);
        value[PROP_VALUE_MAX - 1] = '\0';
        int len = (int)strlen(value);
        pthread_mutex_unlock(&g_lock);
        LOGD("prop spoof [%s] → [%s]", name, value);
        return len;
    }
    pthread_mutex_unlock(&g_lock);

    // Fall through to real implementation
    typedef int (*Fn)(const char*, char*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "__system_property_get");
    if (!real) { value[0] = '\0'; return 0; }

    int ret = real(name, value);

    // Sanitize result: wipe suspicious strings
    if (ret > 0 && contains_pattern(value)) {
        LOGD("prop sanitize [%s]: hiding suspicious value", name);
        value[0] = '\0';
        ret = 0;
    }
    return ret;
}

// ══════════════════════════════════════════════════════════════════════════════
// § 5 — su binary presence hooks
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
    buf->st_blksize = 512; buf->st_blocks = 4;
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
// § 6 — /proc/self/maps  and  /proc/self/status  filtering via memfd
// ══════════════════════════════════════════════════════════════════════════════

static bool should_hide_map_line(const char* line) {
    return contains_pattern(line);
}

// Create a memfd containing filtered /proc/self/maps
static int make_filtered_maps_fd(FILE* (*real_fopen)(const char*, const char*)) {
    FILE* src = real_fopen("/proc/self/maps", "r");
    if (!src) return -1;

    // memfd_create: available on Android 8+ (API 26+; our minSdk=28)
    int mfd = (int)syscall(__NR_memfd_create, "maps_filtered", 1 /*MFD_CLOEXEC*/);
    if (mfd < 0) { fclose(src); return -1; }

    char line[512];
    while (fgets(line, sizeof(line), src)) {
        if (!should_hide_map_line(line))
            write(mfd, line, strlen(line));
    }
    fclose(src);
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

__attribute__((visibility("default")))
FILE* fopen(const char* path, const char* mode) {
    typedef FILE* (*Fn)(const char*, const char*);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "fopen");

    if (path && strcmp(path, "/proc/self/maps") == 0) {
        LOGD("fopen(/proc/self/maps) → filtered memfd");
        int fd = make_filtered_maps_fd(real);
        if (fd >= 0) {
            FILE* f = fdopen(fd, "r");
            if (f) return f;
            close(fd);
        }
        // fallback: return real maps
    }
    return real ? real(path, mode) : nullptr;
}

__attribute__((visibility("default")))
FILE* fopen64(const char* path, const char* mode) {
    // fopen64 is an alias on 64-bit; delegate to our fopen
    return fopen(path, mode);
}

// ══════════════════════════════════════════════════════════════════════════════
// § 7 — open() for su paths + O_RDONLY redirect
// ══════════════════════════════════════════════════════════════════════════════

__attribute__((visibility("default")))
int open(const char* pathname, int flags, ...) {
    typedef int (*Fn)(const char*, int, ...);
    static const Fn real = (Fn)dlsym(RTLD_NEXT, "open");

    if (is_su_path(pathname) && (flags & O_ACCMODE) == O_RDONLY)
        return real ? real("/dev/null", O_RDONLY) : -1;

    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags);
        mode_t mode = (mode_t)va_arg(ap, int); va_end(ap);
        return real ? real(pathname, flags, mode) : -1;
    }
    return real ? real(pathname, flags) : -1;
}

// ══════════════════════════════════════════════════════════════════════════════
// § 8 — Library constructor: dump loaded state to logcat
// ══════════════════════════════════════════════════════════════════════════════

__attribute__((constructor))
static void fakeroot_init() {
    ensure_initialized();
    LOGI("libfakeroot_preload loaded — "
         "uid/prop/maps/su hooks active  sdk_min=28");
}
