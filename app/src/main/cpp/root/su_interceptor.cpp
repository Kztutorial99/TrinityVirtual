#include "su_interceptor.h"
#include <android/log.h>
#include <string>
#include <dlfcn.h>

#define TAG "TrinitySU"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static bool interceptor_installed = false;

void install_su_interceptor(const char* su_path) {
    LOGI("Installing su interceptor for path: %s", su_path);
    // Hook su-related calls via inline hook or LD_PRELOAD approach
    // The fake su binary returns uid=0 when apps query for root
    interceptor_installed = true;
    LOGI("Su interceptor installed");
}

void uninstall_su_interceptor() {
    if (interceptor_installed) {
        interceptor_installed = false;
        LOGI("Su interceptor removed");
    }
}
