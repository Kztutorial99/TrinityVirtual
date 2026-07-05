/**
 * namespace_manager.cpp — TrinityVirtual LD_PRELOAD Injection Engine
 *
 * Replaces the former mount-namespace approach (unshare + bind-mount) which
 * is blocked by SELinux on modern Android (untrusted_app domain, Android 12+).
 *
 * Strategy — zero kernel namespace changes required:
 *  1. Set LD_PRELOAD to libfakeroot_preload.so in the current process env.
 *     All child processes (popen, exec, Runtime.exec, ProcessBuilder) inherit
 *     this variable and will load our override library automatically.
 *  2. dlopen(preload_lib, RTLD_NOW | RTLD_GLOBAL) injects the library into
 *     the host process itself.  Any guest native lib loaded afterwards via
 *     dlopen/System.loadLibrary will prefer our global symbols (getuid, etc.).
 *  3. Deploy a fake-su shell script to the app's private directory and
 *     prepend that directory to PATH, so \`su\` resolves without bind-mount.
 *
 * NO unshare(), NO mount(), NO CLONE_NEWNS — fully SELinux-safe.
 */

#include "namespace_manager.h"
#include <unistd.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>

#define TAG "TrinityNS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static bool        g_injected       = false;
static void*       g_preload_handle = nullptr;
static std::string g_root_dir;

// ── Fake su shell script ───────────────────────────────────────────────────

static const char SU_SCRIPT[] =
    "#!/system/bin/sh\n"
    "# TrinityVirtual fake su (LD_PRELOAD variant — no mount namespace)\n"
    "if [ \"$1\" = \"-v\" ] || [ \"$1\" = \"--version\" ]; then\n"
    "  echo 'su v4.3.3-trinity'\n"
    "  exit 0\n"
    "fi\n"
    "if [ \"$1\" = \"-c\" ] && [ -n \"$2\" ]; then\n"
    "  exec sh -c \"$2\"\n"
    "elif [ $# -gt 0 ]; then\n"
    "  exec sh -c \"$*\"\n"
    "else\n"
    "  exec sh\n"
    "fi\n";

static bool write_fake_su(const std::string& path) {
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (fd < 0) {
        LOGE("Cannot create fake su at %s: %s", path.c_str(), strerror(errno));
        return false;
    }
    ssize_t written = write(fd, SU_SCRIPT, sizeof(SU_SCRIPT) - 1);
    close(fd);
    chmod(path.c_str(), 0755);
    LOGI("Fake su script deployed (%zd bytes) → %s", written, path.c_str());
    return written > 0;
}

// ── PATH manipulation ──────────────────────────────────────────────────────

static void prepend_to_path(const std::string& dir) {
    const char* cur = getenv("PATH");
    std::string new_path = dir + ":" +
        (cur ? cur : "/system/bin:/system/xbin:/sbin");
    setenv("PATH", new_path.c_str(), 1 /*overwrite*/);
    LOGI("PATH updated: %s", new_path.c_str());
}

static void remove_from_path(const std::string& dir) {
    const char* cur = getenv("PATH");
    if (!cur) return;
    std::string path(cur);
    std::string prefix = dir + ":";
    auto pos = path.find(prefix);
    if (pos != std::string::npos) {
        path.erase(pos, prefix.size());
        setenv("PATH", path.c_str(), 1);
    }
}

// ── Public API ─────────────────────────────────────────────────────────────

/**
 * Activate LD_PRELOAD injection.
 *
 * @param root_dir        App-private directory for the virtual root env.
 * @param preload_lib_path Absolute path to libfakeroot_preload.so.
 * @return 0 on success, negative on fatal error.
 */
int setup_ld_preload_injection(const char* root_dir,
                               const char* preload_lib_path) {
    if (!root_dir || !preload_lib_path) {
        LOGE("setup_ld_preload_injection: null arguments");
        return -1;
    }

    g_root_dir = root_dir;
    LOGI("LD_PRELOAD injection init — dir: %s  lib: %s",
         root_dir, preload_lib_path);

    // ── Step 1: Verify the preload library is readable ───────────────────
    if (::access(preload_lib_path, R_OK) != 0) {
        LOGE("Preload library missing or unreadable: %s (%s)",
             preload_lib_path, strerror(errno));
        return -2;
    }

    // ── Step 2: Set LD_PRELOAD for all child processes ───────────────────
    //   popen(), system(), exec*(), Runtime.exec() all inherit this env var.
    setenv("LD_PRELOAD", preload_lib_path, 1 /*overwrite*/);
    LOGI("LD_PRELOAD set → %s", preload_lib_path);

    // ── Step 3: Inject into the host process via dlopen(RTLD_GLOBAL) ─────
    //   Symbols exported by the preload lib become the preferred resolution
    //   for any subsequent dlopen of a guest native library.
    dlerror(); // clear
    g_preload_handle = dlopen(preload_lib_path, RTLD_NOW | RTLD_GLOBAL);
    if (!g_preload_handle) {
        LOGE("dlopen(%s) failed: %s  — child-process injection still active",
             preload_lib_path, dlerror());
        // Non-fatal: LD_PRELOAD covers new child processes regardless.
    } else {
        LOGI("Preload lib injected into host process (RTLD_GLOBAL)");
    }

    // ── Step 4: Deploy fake su shell script ──────────────────────────────
    mkdir(root_dir, 0755);
    std::string su_path = std::string(root_dir) + "/su";
    write_fake_su(su_path);

    // ── Step 5: Prepend root_dir to PATH ─────────────────────────────────
    //   `su` in PATH resolves before any system su (/system/bin, /system/xbin)
    prepend_to_path(root_dir);

    g_injected = true;
    LOGI("LD_PRELOAD injection active — virtual root ready (no namespace)");
    return 0;
}

void teardown_ld_preload_injection() {
    if (!g_injected) return;

    remove_from_path(g_root_dir);
    unsetenv("LD_PRELOAD");

    if (g_preload_handle) {
        dlclose(g_preload_handle);
        g_preload_handle = nullptr;
    }

    g_injected = false;
    g_root_dir.clear();
    LOGI("LD_PRELOAD injection removed");
}

bool is_injection_active() {
    return g_injected;
}
