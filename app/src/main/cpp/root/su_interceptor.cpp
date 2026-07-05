/**
 * su_interceptor.cpp — In-process root-check diagnostic & hook verifier
 *
 * Works in tandem with the LD_PRELOAD engine:
 *   • LD_PRELOAD  → covers NEW child processes (popen, exec, Runtime.exec)
 *   • dlopen(RTLD_GLOBAL) → covers guest native libs loaded into host process
 *   • This file → verifies hooks are live and provides the install/uninstall
 *     API called by root_engine.cpp
 *
 * After setup_ld_preload_injection() calls dlopen(libfakeroot_preload, RTLD_GLOBAL),
 * any subsequent dlopen of a guest .so resolves getuid/geteuid/access from our
 * preloaded symbols automatically — no explicit GOT patching needed.
 */

#include "su_interceptor.h"
#include <dlfcn.h>
#include <unistd.h>
#include <android/log.h>
#include <string>

#define TAG "TrinitySU"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

static bool g_interceptor_active = false;

// ── Diagnostic: verify our override symbols are the ones resolving ─────────

static void verify_symbol_hooks() {
    // After dlopen(RTLD_GLOBAL) of libfakeroot_preload, geteuid() in this
    // process should return 0.  Log the result for diagnostic purposes.
    uid_t uid = geteuid();
    LOGI("Post-inject geteuid() = %d (expected 0 for virtual root)", (int)uid);
    if (uid != 0) {
        LOGW("geteuid hook not yet visible — dlopen may have failed; "
             "child-process injection via LD_PRELOAD is still active");
    }

    // Verify access() override on a known su path
    int acc = access("/system/bin/su", F_OK);
    LOGI("Post-inject access('/system/bin/su') = %d (expected 0)", acc);

    // Identify which library is providing getuid to confirm injection
    typedef uid_t (*getuid_fn_t)(void);
    getuid_fn_t fn = (getuid_fn_t)dlsym(RTLD_DEFAULT, "getuid");
    if (fn) {
        Dl_info info = {};
        if (dladdr((void*)fn, &info) && info.dli_fname) {
            LOGD("getuid resolved from: %s", info.dli_fname);
        }
    }
}

// ── Public API ─────────────────────────────────────────────────────────────

/**
 * Called by root_engine.cpp after setup_ld_preload_injection() succeeds.
 * Verifies the injection and marks the interceptor as active.
 *
 * @param su_path  Path to the fake-su script deployed in root_dir (for logging).
 */
void install_su_interceptor(const char* su_path) {
    LOGI("Su interceptor install — LD_PRELOAD variant (no GOT patching needed)");
    LOGI("Fake su script location: %s", su_path ? su_path : "<unknown>");

    verify_symbol_hooks();

    g_interceptor_active = true;
    LOGI("Su interceptor active — root-check calls will be redirected");
}

void uninstall_su_interceptor() {
    if (!g_interceptor_active) return;
    g_interceptor_active = false;
    LOGI("Su interceptor removed");
}

bool is_su_interceptor_active() {
    return g_interceptor_active;
}
