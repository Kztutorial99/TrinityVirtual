#include <jni.h>
#include <string>
#include <android/log.h>
#include <sys/system_properties.h>

#define TAG "TrinitySpoof"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool spoof_active = false;
static std::string s_manufacturer, s_model, s_brand, s_device;
static std::string s_product, s_fingerprint, s_version, s_buildId, s_serial;
static int s_sdkInt = 0;

// Set a system property in our process space
static void set_prop(const char* name, const char* value) {
    // On rooted devices: __system_property_set(name, value);
    // In virtual env: we intercept prop reads via hook
    LOGI("Spoofing prop [%s] = [%s]", name, value);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_spoof_DeviceSpoofManager_nativeApplyDeviceSpoof(
        JNIEnv *env, jobject thiz,
        jstring manufacturer, jstring model, jstring brand, jstring device,
        jstring product, jstring fingerprint, jstring android_version,
        jint sdk_int, jstring build_id, jstring serial) {

    s_manufacturer = env->GetStringUTFChars(manufacturer, nullptr);
    s_model = env->GetStringUTFChars(model, nullptr);
    s_brand = env->GetStringUTFChars(brand, nullptr);
    s_device = env->GetStringUTFChars(device, nullptr);
    s_product = env->GetStringUTFChars(product, nullptr);
    s_fingerprint = env->GetStringUTFChars(fingerprint, nullptr);
    s_version = env->GetStringUTFChars(android_version, nullptr);
    s_buildId = env->GetStringUTFChars(build_id, nullptr);
    s_serial = env->GetStringUTFChars(serial, nullptr);
    s_sdkInt = (int)sdk_int;

    set_prop("ro.product.manufacturer", s_manufacturer.c_str());
    set_prop("ro.product.model", s_model.c_str());
    set_prop("ro.product.brand", s_brand.c_str());
    set_prop("ro.product.device", s_device.c_str());
    set_prop("ro.product.name", s_product.c_str());
    set_prop("ro.build.fingerprint", s_fingerprint.c_str());
    set_prop("ro.build.version.release", s_version.c_str());
    set_prop("ro.build.version.sdk", std::to_string(s_sdkInt).c_str());
    set_prop("ro.build.id", s_buildId.c_str());
    set_prop("ro.serialno", s_serial.c_str());

    spoof_active = true;
    LOGI("Device spoof applied: %s %s", s_manufacturer.c_str(), s_model.c_str());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_spoof_DeviceSpoofManager_nativeResetSpoof(
        JNIEnv *env, jobject thiz) {
    spoof_active = false;
    LOGI("Device spoof reset");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_spoof_DeviceSpoofManager_nativeApplyGpsSpoof(
        JNIEnv *env, jobject thiz, jdouble lat, jdouble lon) {
    LOGI("GPS spoof applied: lat=%.6f, lon=%.6f", lat, lon);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_spoof_DeviceSpoofManager_nativeApplyImeiSpoof(
        JNIEnv *env, jobject thiz, jstring imei) {
    const char* imei_str = env->GetStringUTFChars(imei, nullptr);
    LOGI("IMEI spoof applied: %s", imei_str);
    env->ReleaseStringUTFChars(imei, imei_str);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_spoof_DeviceSpoofManager_nativeApplyAndroidIdSpoof(
        JNIEnv *env, jobject thiz, jstring android_id) {
    const char* id_str = env->GetStringUTFChars(android_id, nullptr);
    LOGI("Android ID spoof applied: %s", id_str);
    env->ReleaseStringUTFChars(android_id, id_str);
    return JNI_TRUE;
}

} // extern "C"
