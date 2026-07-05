/**
 * module_loader.cpp — TrinityVirtual Stealth Module Loader
 *
 * nativeLoadModuleFromMemory(byte[], name):
 *   1. Receive raw .so bytes from Kotlin AssetManager
 *   2. memfd_create() + write bytes
 *   3. dlopen("/proc/self/fd/<N>", RTLD_NOW | RTLD_GLOBAL)
 *   No filesystem trace — maps entry filtered by preload hook.
 *
 * nativeLoadModule(soPath):
 *   Takes the ABSOLUTE PATH to the .so file (not a module dir).
 *   Callers must resolve the full .so path before calling.
 *
 * nativeUnloadModule(name): dlclose by registry name.
 */

#include <jni.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <map>
#include <mutex>
#include <unistd.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "TrinityModule"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static std::mutex              g_mutex;
static std::map<std::string, void*> g_handles;  // name → dlopen handle

static int create_memfd(const char* name) {
    int fd = (int)syscall(__NR_memfd_create, name, 1 /*MFD_CLOEXEC*/);
    if (fd < 0) LOGE("memfd_create('%s') failed: errno=%d", name, errno);
    return fd;
}

static bool write_all(int fd, const uint8_t* buf, size_t len) {
    size_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, buf + written, len - written);
        if (n <= 0) { LOGE("write failed at offset %zu: errno=%d", written, errno); return false; }
        written += (size_t)n;
    }
    return true;
}

extern "C" {

// ══════════════════════════════════════════════════════════════════════════════
// nativeLoadModuleFromMemory — in-memory load, no filesystem trace
// ══════════════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_module_ModuleManager_nativeLoadModuleFromMemory(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray data_j, jstring name_j) {

    const char* name = env->GetStringUTFChars(name_j, nullptr);
    jsize       len  = env->GetArrayLength(data_j);
    jbyte*      buf  = env->GetByteArrayElements(data_j, nullptr);

    LOGI("Loading '%s' from memory (%d bytes)", name, (int)len);

    bool ok  = false;
    int  mfd = -1;

    if (len <= 0) { LOGE("Empty byte array for module '%s'", name); goto done; }

    mfd = create_memfd(name);
    if (mfd < 0) goto done;

    if (!write_all(mfd, (const uint8_t*)buf, (size_t)len)) {
        close(mfd); mfd = -1; goto done;
    }

    {
        char fd_path[64];
        snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", mfd);

        void* handle = dlopen(fd_path, RTLD_NOW | RTLD_GLOBAL);
        close(mfd); mfd = -1;

        if (!handle) {
            LOGE("dlopen('%s') for module '%s' failed: %s", fd_path, name, dlerror());
            goto done;
        }

        typedef void (*entry_t)(void);
        entry_t entry = (entry_t)dlsym(handle, "trinity_module_init");
        if (entry) {
            LOGI("Calling trinity_module_init for '%s'", name);
            entry();
        }

        { std::lock_guard<std::mutex> lk(g_mutex); g_handles[name] = handle; }
        LOGI("Module '%s' loaded in-memory ✓ (handle=%p)", name, handle);
        ok = true;
    }

done:
    if (mfd >= 0) close(mfd);
    env->ReleaseByteArrayElements(data_j, buf, JNI_ABORT);
    env->ReleaseStringUTFChars(name_j, name);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ══════════════════════════════════════════════════════════════════════════════
// nativeLoadModule — legacy/enable: caller passes ABSOLUTE .so PATH directly
// ══════════════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_module_ModuleManager_nativeLoadModule(
        JNIEnv* env, jobject /*thiz*/, jstring so_path_j) {

    const char* so_path = env->GetStringUTFChars(so_path_j, nullptr);
    LOGI("File-based load: %s", so_path);

    // dlopen the .so directly — path MUST be an absolute .so file path
    void* handle = dlopen(so_path, RTLD_NOW | RTLD_GLOBAL);
    bool ok = (handle != nullptr);
    if (!ok) {
        LOGE("dlopen('%s') failed: %s", so_path, dlerror());
    } else {
        // Register under the basename without extension as the module name
        const char* slash = strrchr(so_path, '/');
        std::string name  = slash ? (slash + 1) : so_path;
        // Strip "lib" prefix and ".so" suffix for a clean name
        if (name.size() > 3 && name.substr(0,3) == "lib") name = name.substr(3);
        auto dot = name.rfind(".so");
        if (dot != std::string::npos) name = name.substr(0, dot);

        std::lock_guard<std::mutex> lk(g_mutex);
        g_handles[name] = handle;
        LOGI("Module '%s' registered from file", name.c_str());
    }

    env->ReleaseStringUTFChars(so_path_j, so_path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ══════════════════════════════════════════════════════════════════════════════
// nativeUnloadModule
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
            LOGE("Module '%s' not in registry (already unloaded?)", name);
        }
    }
    env->ReleaseStringUTFChars(name_j, name);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ══════════════════════════════════════════════════════════════════════════════
// nativeInstallHook — stub (integrate Dobby/ShadowHook for real inline hooks)
// ══════════════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_module_ModuleManager_nativeInstallHook(
        JNIEnv* env, jobject /*thiz*/,
        jstring target_j, jstring hook_j) {
    const char* target = env->GetStringUTFChars(target_j, nullptr);
    const char* hook   = env->GetStringUTFChars(hook_j,   nullptr);
    LOGI("Hook stub: target=%s hook=%s", target, hook);
    env->ReleaseStringUTFChars(target_j, target);
    env->ReleaseStringUTFChars(hook_j,   hook);
    return JNI_FALSE;
}

} // extern "C"
