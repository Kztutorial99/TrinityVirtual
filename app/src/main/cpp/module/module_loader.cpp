#include <jni.h>
#include <string>
#include <android/log.h>
#include <dlfcn.h>

#define TAG "TrinityModule"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_module_ModuleManager_nativeLoadModule(
        JNIEnv *env, jobject thiz, jstring module_path) {
    const char *path = env->GetStringUTFChars(module_path, nullptr);
    LOGI("Loading module from: %s", path);
    std::string lib_path = std::string(path) + "/lib/arm64-v8a/libmodule.so";
    void *handle = dlopen(lib_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    bool success = (handle != nullptr);
    if (!success) LOGE("Module load failed: %s", dlerror());
    env->ReleaseStringUTFChars(module_path, path);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_module_ModuleManager_nativeUnloadModule(
        JNIEnv *env, jobject thiz, jstring module_path) {
    const char *path = env->GetStringUTFChars(module_path, nullptr);
    LOGI("Unloading module: %s", path);
    env->ReleaseStringUTFChars(module_path, path);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_module_ModuleManager_nativeInstallHook(
        JNIEnv *env, jobject thiz, jstring target, jstring hook) {
    return JNI_TRUE;
}

} // extern "C"
