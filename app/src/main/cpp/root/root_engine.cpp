/**
 * root_engine.cpp — TrinityVirtual JNI bridge for virtual root
 *
 * Delegates to the LD_PRELOAD injection engine instead of the former
 * mount-namespace approach (unshare(CLONE_NEWNS) blocked by SELinux
 * on Android 12+ untrusted_app domain).
 *
 * The second argument to nativeStartRootNamespace is now the absolute path to
 * libfakeroot_preload.so (from nativeLibraryDir), NOT a fake-su binary path.
 * The fake-su shell script is deployed by setup_ld_preload_injection() itself.
 */

#include <jni.h>
#include <string>
#include <stdlib.h>
#include <unistd.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <android/log.h>
#include "namespace_manager.h"
#include "su_interceptor.h"

#define TAG "TrinityRoot"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool g_root_active  = false;
static char g_su_path[512] = {};   // path to the deployed fake-su script

extern "C" {

// ─────────────────────────────────────────────────────────────────────────────
// nativeStartRootNamespace
//
// Kotlin: nativeStartRootNamespace(rootDir: String, preloadLibPath: String): Int
//
//   rootDir        — app private dir for the virtual root env (files/root_engine)
//   preloadLibPath — absolute path to libfakeroot_preload.so (nativeLibraryDir)
//
// Returns 0 on success, non-zero on error.
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_trinityvirtual_engine_RootEngine_nativeStartRootNamespace(
        JNIEnv* env, jobject /*thiz*/,
        jstring root_dir_j, jstring preload_lib_j) {

    const char* dir = env->GetStringUTFChars(root_dir_j,    nullptr);
    const char* lib = env->GetStringUTFChars(preload_lib_j, nullptr);

    LOGI("Starting LD_PRELOAD virtual root");
    LOGI("  rootDir    : %s", dir);
    LOGI("  preloadLib : %s", lib);

    // Build expected fake-su path for interceptor diagnostics
    std::string su = std::string(dir) + "/su";
    __builtin_strncpy(g_su_path, su.c_str(), sizeof(g_su_path) - 1);

    int rc = setup_ld_preload_injection(dir, lib);

    if (rc == 0) {
        g_root_active = true;
        install_su_interceptor(g_su_path);
        LOGI("Virtual root (LD_PRELOAD) active — no mount namespace used");
    } else {
        LOGE("LD_PRELOAD injection failed: rc=%d", rc);
    }

    env->ReleaseStringUTFChars(root_dir_j,    dir);
    env->ReleaseStringUTFChars(preload_lib_j, lib);
    return (jint)rc;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeStopRootNamespace
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_trinityvirtual_engine_RootEngine_nativeStopRootNamespace(
        JNIEnv* /*env*/, jobject /*thiz*/) {

    if (g_root_active) {
        uninstall_su_interceptor();
        teardown_ld_preload_injection();
        g_root_active  = false;
        g_su_path[0]   = '\0';
        LOGI("Virtual root stopped");
    }
    return 0;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeGetEffectiveUid
// After injection, geteuid() is hooked inside this process to return 0.
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_trinityvirtual_engine_RootEngine_nativeGetEffectiveUid(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return (jint)geteuid();
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeExecuteRoot
// Runs a shell command. popen() inherits the process environment, so LD_PRELOAD
// is already set and the child process loads libfakeroot_preload.so automatically.
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_trinityvirtual_engine_RootEngine_nativeExecuteRoot(
        JNIEnv* env, jobject /*thiz*/, jstring command_j) {

    const char* cmd = env->GetStringUTFChars(command_j, nullptr);
    std::string result;

    if (g_root_active) {
        FILE* pipe = popen(cmd, "r");
        if (pipe) {
            char buf[256];
            while (fgets(buf, sizeof(buf), pipe)) result += buf;
            int exit_code = pclose(pipe);
            if (exit_code != 0 && result.empty()) {
                char errbuf[64];
                snprintf(errbuf, sizeof(errbuf), "Warning: command exited with code %d", exit_code);
                result = errbuf;
            }
        } else {
            // popen() returns NULL when the shell cannot be launched.
            // Common cause: LD_PRELOAD library has unresolved DT_NEEDED entries
            // (e.g. libc++_shared.so not found in default linker namespace).
            int err = errno;
            char errbuf[160];
            snprintf(errbuf, sizeof(errbuf),
                     "Error: popen gagal (errno=%d: %s) — cek logcat untuk CANNOT LINK EXECUTABLE",
                     err, strerror(err));
            result = errbuf;
            LOGE("popen failed: errno=%d (%s) | cmd: %s", err, strerror(err), cmd);
        }
    } else {
        result = "Error: Virtual root not active";
        LOGE("nativeExecuteRoot called while root not active");
    }

    env->ReleaseStringUTFChars(command_j, cmd);
    return env->NewStringUTF(result.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeIsInjectionActive
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_engine_RootEngine_nativeIsInjectionActive(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return (jboolean)(is_injection_active() ? JNI_TRUE : JNI_FALSE);
}

} // extern "C"
