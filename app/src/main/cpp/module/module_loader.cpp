/**
 * module_loader.cpp — TrinityVirtual Stealth Module Loader
 *
 * Loads native modules (.so) without writing to the filesystem:
 *
 *   nativeLoadModuleFromMemory(byte[], name):
 *     1. Receive raw .so bytes from the Kotlin AssetManager
 *     2. Create an anonymous in-memory file via memfd_create()
 *     3. Write bytes to the memfd
 *     4. dlopen("/proc/self/fd/<N>", RTLD_NOW | RTLD_GLOBAL)
 *     5. Close the fd — the mapping stays alive via the dlopen handle
 *
 * The .so never appears in the filesystem; the only trace is a
 * [memfd:name] entry in /proc/self/maps (filtered by our preload hook).
 *
 *   nativeLoadModule(path):     legacy file-based load (unchanged)
 *   nativeUnloadModule(path):   stub (dlclose by handle not yet tracked)
 */

#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <android/log.h>
#include <dlfcn.h>
#include <map>
#include <mutex>

#define TAG "TrinityModule"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ── Module handle registry ─────────────────────────────────────────────────

static std::mutex              g_mutex;
static std::map<std::string, void*> g_handles;  // name → dlopen handle

// ── memfd helper ───────────────────────────────────────────────────────────

static int create_memfd(const char* name) {
    // memfd_create via syscall — available Android 8+ (API 26+; minSdk=28)
    int fd = (int)syscall(__NR_memfd_create, name, 1 /*MFD_CLOEXEC*/);
    if (fd < 0) LOGE("memfd_create('%s') failed: errno=%d", name, errno);
    return fd;
}

// Write all bytes to fd, handling partial writes
static bool write_all(int fd, const uint8_t* buf, size_t len) {
    size_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, buf + written, len - written);
        if (n <= 0) { LOGE("write failed at offset %zu", written); return false; }
        written += (size_t)n;
    }
    return true;
}

extern "C" {

// ══════════════════════════════════════════════════════════════════════════════
// nativeLoadModuleFromMemory
// Called from ModuleManager.kt with raw bytes from AssetManager.
// ══════════════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_module_ModuleManager_nativeLoadModuleFromMemory(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray data_j, jstring name_j) {

    const char* name = env->GetStringUTFChars(name_j, nullptr);
    jsize       len  = env->GetArrayLength(data_j);
    jbyte*      buf  = env->GetByteArrayElements(data_j, nullptr);

    LOGI("Loading module '%s' from memory (%d bytes)", name, (int)len);

    bool ok = false;

    // Step 1: Create anonymous in-memory file
    int mfd = create_memfd(name);
    if (mfd < 0) goto done;

    // Step 2: Write .so content
    if (!write_all(mfd, (const uint8_t*)buf, (size_t)len)) {
        close(mfd);
        goto done;
    }

    // Step 3: dlopen via /proc/self/fd/<N>
    //  The /proc/self/fd/ path is readable as a regular SO path by the linker.
    {
        char fd_path[64];
        snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", mfd);

        void* handle = dlopen(fd_path, RTLD_NOW | RTLD_GLOBAL);
        close(mfd);   // fd no longer needed — mapping survives via handle

        if (!handle) {
            LOGE("dlopen memfd module '%s' failed: %s", name, dlerror());
            goto done;
        }

        // Step 4: Look for optional module entry point
        typedef void (*module_entry_t)(void);
        module_entry_t entry = (module_entry_t)dlsym(handle, "trinity_module_init");
        if (entry) {
            LOGI("Calling trinity_module_init for '%s'", name);
            entry();
        }

        // Register handle for later unload
        {
            std::lock_guard<std::mutex> lk(g_mutex);
            g_handles[name] = handle;
        }

        LOGI("Module '%s' loaded in-memory (handle=%p)", name, handle);
        ok = true;
    }

done:
    env->ReleaseByteArrayElements(data_j, buf, JNI_ABORT);
    env->ReleaseStringUTFChars(name_j, name);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ══════════════════════════════════════════════════════════════════════════════
// nativeUnloadModule — unloads by name if handle is tracked
// ══════════════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_module_ModuleManager_nativeUnloadModule(
        JNIEnv* env, jobject /*thiz*/, jstring name_j) {

    const char* name = env->GetStringUTFChars(name_j, nullptr);
    bool ok = false;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        auto it = g_handles.find(name);
        if (it != g_handles.end()) {
            dlclose(it->second);
            g_handles.erase(it);
            ok = true;
            LOGI("Module '%s' unloaded", name);
        } else {
            LOGE("Module '%s' not found in registry", name);
        }
    }
    env->ReleaseStringUTFChars(name_j, name);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ══════════════════════════════════════════════════════════════════════════════
// nativeLoadModule — legacy file-based load (retained for compatibility)
// ══════════════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_module_ModuleManager_nativeLoadModule(
        JNIEnv* env, jobject /*thiz*/, jstring module_path_j) {

    const char* path = env->GetStringUTFChars(module_path_j, nullptr);
    LOGI("Legacy file-based module load: %s", path);

    std::string lib_path = std::string(path) + "/lib/arm64-v8a/libmodule.so";
    void* handle = dlopen(lib_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    bool ok = (handle != nullptr);
    if (!ok) LOGE("dlopen('%s') failed: %s", lib_path.c_str(), dlerror());

    env->ReleaseStringUTFChars(module_path_j, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ══════════════════════════════════════════════════════════════════════════════
// nativeInstallHook — stub (integrate Dobby/ShadowHook for production)
// ══════════════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_module_ModuleManager_nativeInstallHook(
        JNIEnv* env, jobject /*thiz*/,
        jstring target_j, jstring hook_j) {
    const char* target = env->GetStringUTFChars(target_j, nullptr);
    const char* hook   = env->GetStringUTFChars(hook_j,   nullptr);
    LOGI("Hook stub: target=%s hook=%s  (integrate Dobby for real hooks)", target, hook);
    env->ReleaseStringUTFChars(target_j, target);
    env->ReleaseStringUTFChars(hook_j,   hook);
    return JNI_FALSE; // stub
}

} // extern "C"
