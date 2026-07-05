#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sched.h>
#include <android/log.h>
#include "namespace_manager.h"
#include "su_interceptor.h"

#define TAG "TrinityRoot"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool g_root_active = false;
static int g_root_ns_fd = -1;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_trinityvirtual_engine_RootEngine_nativeStartRootNamespace(
        JNIEnv *env, jobject thiz,
        jstring root_dir, jstring su_path) {

    const char *root_dir_str = env->GetStringUTFChars(root_dir, nullptr);
    const char *su_path_str = env->GetStringUTFChars(su_path, nullptr);

    LOGI("Starting virtual root namespace: %s", root_dir_str);

    // Create isolated mount namespace
    int result = create_virtual_namespace(root_dir_str, su_path_str);

    if (result == 0) {
        g_root_active = true;
        // Install su interceptor hooks
        install_su_interceptor(su_path_str);
        LOGI("Virtual root namespace active");
    } else {
        LOGE("Failed to create namespace: %d", result);
    }

    env->ReleaseStringUTFChars(root_dir, root_dir_str);
    env->ReleaseStringUTFChars(su_path, su_path_str);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_trinityvirtual_engine_RootEngine_nativeStopRootNamespace(
        JNIEnv *env, jobject thiz) {
    if (g_root_active) {
        uninstall_su_interceptor();
        destroy_virtual_namespace();
        g_root_active = false;
        if (g_root_ns_fd != -1) {
            close(g_root_ns_fd);
            g_root_ns_fd = -1;
        }
        LOGI("Virtual root namespace stopped");
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_trinityvirtual_engine_RootEngine_nativeGetEffectiveUid(
        JNIEnv *env, jobject thiz) {
    return (jint)geteuid();
}

JNIEXPORT jstring JNICALL
Java_com_trinityvirtual_engine_RootEngine_nativeExecuteRoot(
        JNIEnv *env, jobject thiz, jstring command) {
    const char *cmd = env->GetStringUTFChars(command, nullptr);
    std::string result;

    if (g_root_active) {
        FILE *pipe = popen(cmd, "r");
        if (pipe) {
            char buffer[256];
            while (fgets(buffer, sizeof(buffer), pipe)) {
                result += buffer;
            }
            pclose(pipe);
        }
    } else {
        result = "Error: Virtual root not active";
    }

    env->ReleaseStringUTFChars(command, cmd);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
