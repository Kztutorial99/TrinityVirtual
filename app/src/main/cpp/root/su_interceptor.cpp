/**
 * su_interceptor.cpp — TrinityVirtual In-Process Hook Verifier & Maps Guard
 *
 * Responsibilities:
 *  1. Verify LD_PRELOAD hooks are live after injection (diagnostic)
 *  2. Confirm /proc/self/maps filtering is active
 *  3. Provide API to check intercept status from root_engine.cpp
 *
 * The actual hook implementation lives in libfakeroot_preload.so (RTLD_GLOBAL).
 * This module queries that library via RTLD_DEFAULT symbol resolution.
 */

#include "su_interceptor.h"
#include <dlfcn.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>
#include <string>

#define TAG "TrinitySU"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

static bool g_interceptor_active = false;

// ── Verify UID hooks ───────────────────────────────────────────────────────

static bool verify_uid_hook() {
    uid_t uid = geteuid();
    LOGI("geteuid() = %d (expect 0 for virtual root)", (int)uid);
    if (uid != 0)
        LOGW("UID hook not active — dlopen of preload lib may have failed");
    return uid == 0;
}

// ── Verify su access hook ─────────────────────────────────────────────────

static bool verify_su_access() {
    int r = access("/system/bin/su", F_OK);
    LOGI("access('/system/bin/su') = %d (expect 0)", r);
    return r == 0;
}

// ── Verify /proc/self/maps filtering ──────────────────────────────────────
//
// Open /proc/self/maps via our hooked fopen and scan for suspicious strings.
// A clean maps file should NOT contain "trinity", "magisk", "fakeroot", etc.

static bool verify_maps_filtering() {
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) {
        LOGW("Cannot open /proc/self/maps for verification");
        return false;
    }

    static const char* BANNED[] = {
        "trinity", "magisk", "zygisk", "fakeroot",
        "xposed", "substrate", "frida", nullptr
    };

    bool clean = true;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        for (int i = 0; BANNED[i]; i++) {
            if (strcasestr(line, BANNED[i])) {
                LOGE("maps leak: line contains '%s': %.*s",
                     BANNED[i], (int)strcspn(line, "\n"), line);
                clean = false;
            }
        }
    }
    fclose(f);

    if (clean) LOGI("/proc/self/maps — clean (no suspicious strings)");
    return clean;
}

// ── Verify which library owns getuid ──────────────────────────────────────

static void log_getuid_source() {
    typedef uid_t (*getuid_fn)(void);
    getuid_fn fn = (getuid_fn)dlsym(RTLD_DEFAULT, "getuid");
    if (!fn) { LOGD("getuid: dlsym returned null"); return; }
    Dl_info info = {};
    if (dladdr((void*)fn, &info) && info.dli_fname)
        LOGD("getuid resolved from: %s", info.dli_fname);
}

// ── Verify fakeroot_set_prop is reachable ─────────────────────────────────

static bool verify_prop_api() {
    void* fn = dlsym(RTLD_DEFAULT, "fakeroot_set_prop");
    if (fn) {
        LOGI("fakeroot_set_prop reachable via RTLD_DEFAULT");
        return true;
    }
    LOGW("fakeroot_set_prop NOT found — prop hooks disabled");
    return false;
}

// ══════════════════════════════════════════════════════════════════════════════
// Public API
// ══════════════════════════════════════════════════════════════════════════════

void install_su_interceptor(const char* su_path) {
    LOGI("=== Su Interceptor Install (LD_PRELOAD variant) ===");
    LOGI("Fake su script: %s", su_path ? su_path : "<unknown>");

    log_getuid_source();

    bool uid_ok    = verify_uid_hook();
    bool su_ok     = verify_su_access();
    bool maps_ok   = verify_maps_filtering();
    bool prop_ok   = verify_prop_api();

    g_interceptor_active = true;

    LOGI("=== Intercept status: uid=%s su=%s maps=%s prop=%s ===",
         uid_ok  ? "OK" : "FAIL",
         su_ok   ? "OK" : "FAIL",
         maps_ok ? "OK" : "FAIL",
         prop_ok ? "OK" : "FAIL");
}

void uninstall_su_interceptor() {
    if (!g_interceptor_active) return;
    g_interceptor_active = false;
    LOGI("Su interceptor removed");
}

bool is_su_interceptor_active() {
    return g_interceptor_active;
}
