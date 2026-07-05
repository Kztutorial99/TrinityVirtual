/**
 * prop_hook.cpp — TrinityVirtual Active Property Hook Layer
 *
 * Bridges JNI ↔ libfakeroot_preload.so config API.
 * prop_hook.cpp calls fakeroot_set_prop / fakeroot_add_hidden_pattern
 * via dlsym(RTLD_DEFAULT) — no link-time dep on fakeroot_preload.
 *
 * nativeApplyIntegrityBypass():
 *   Returns true ONLY when fakeroot_set_prop is actually reachable.
 *   Returns false if preload lib is not loaded yet (Kotlin can retry/warn).
 */

#include <jni.h>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <android/log.h>
#include <string>
#include <map>

#define TAG "TrinityProp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef void (*fn_set_prop_t)    (const char*, const char*);
typedef void (*fn_add_pattern_t) (const char*);
typedef void (*fn_reset_props_t) (void);

static fn_set_prop_t get_set_prop() {
    static const fn_set_prop_t fn =
        (fn_set_prop_t)dlsym(RTLD_DEFAULT, "fakeroot_set_prop");
    return fn;
}
static fn_add_pattern_t get_add_pattern() {
    static const fn_add_pattern_t fn =
        (fn_add_pattern_t)dlsym(RTLD_DEFAULT, "fakeroot_add_hidden_pattern");
    return fn;
}
static fn_reset_props_t get_reset_props() {
    static const fn_reset_props_t fn =
        (fn_reset_props_t)dlsym(RTLD_DEFAULT, "fakeroot_reset_props");
    return fn;
}

static const char* SUSPICIOUS_PATTERNS[] = {
    "qemu", "goldfish", "ranchu", "android_x86",
    "magisk", "zygisk", "xposed", "edxposed",
    "substrate", "frida", "gadget",
    "trinity", "virtualapp", "vmos",
    "bluestacks", "nox", "memu", "ldplayer",
    nullptr
};

static const std::pair<const char*, const char*> SAMSUNG_S23_PROFILE[] = {
    { "ro.product.manufacturer",     "samsung"                                              },
    { "ro.product.brand",            "samsung"                                              },
    { "ro.product.model",            "SM-S918B"                                             },
    { "ro.product.device",           "dm3q"                                                 },
    { "ro.product.name",             "dm3qxxx"                                              },
    { "ro.product.board",            "kalama"                                               },
    { "ro.build.fingerprint",
      "samsung/dm3qxxx/dm3q:13/TP1A.220624.014/S918BXXS3BWF1:user/release-keys"           },
    { "ro.vendor.build.fingerprint",
      "samsung/dm3qxxx/dm3q:13/TP1A.220624.014/S918BXXS3BWF1:user/release-keys"           },
    { "ro.build.version.release",    "13"                                                   },
    { "ro.build.version.sdk",        "33"                                                   },
    { "ro.build.id",                 "TP1A.220624.014"                                      },
    { "ro.build.type",               "user"                                                 },
    { "ro.build.tags",               "release-keys"                                         },
    { "ro.build.description",
      "dm3qxxx-user 13 TP1A.220624.014 S918BXXS3BWF1 release-keys"                        },
    { "ro.serialno",                 "R5CW301234X"                                          },
    { "ro.hardware",                 "qcom"                                                 },
    { "ro.hardware.chipname",        "SM8550"                                               },
    { "ro.product.cpu.abi",          "arm64-v8a"                                            },
    { "ro.product.cpu.abilist",      "arm64-v8a,armeabi-v7a,armeabi"                        },
    { "ro.boot.verifiedbootstate",   "green"                                                },
    { "ro.boot.veritymode",          "enforcing"                                            },
    { "ro.secure",                   "1"                                                    },
    { "ro.debuggable",               "0"                                                    },
    { nullptr, nullptr }
};

static void sanitize_value_impl(std::string& val) {
    for (int i = 0; SUSPICIOUS_PATTERNS[i]; i++) {
        const char* pat = SUSPICIOUS_PATTERNS[i];
        size_t pos;
        std::string lower = val;
        for (char& c : lower) c = (char)tolower((unsigned char)c);
        std::string lower_pat = pat;
        for (char& c : lower_pat) c = (char)tolower((unsigned char)c);
        while ((pos = lower.find(lower_pat)) != std::string::npos) {
            val.erase(pos, strlen(pat));
            lower.erase(pos, strlen(pat));
        }
    }
}

static std::map<std::string, std::string> g_local_overrides;

// ══════════════════════════════════════════════════════════════════════════════
// JNI — nativeApplyIntegrityBypass
// Returns TRUE only when fakeroot_set_prop is actually reachable.
// ══════════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_spoof_PropHookManager_nativeApplyIntegrityBypass(
        JNIEnv* /*env*/, jobject /*thiz*/) {

    fn_set_prop_t    set_prop    = get_set_prop();
    fn_add_pattern_t add_pattern = get_add_pattern();

    if (!set_prop) {
        LOGE("fakeroot_set_prop not found — preload lib not loaded yet, returning false");
        return JNI_FALSE;   // ← correct: don't claim success if hooks are inactive
    }

    // Apply Samsung S23 Ultra profile
    for (int i = 0; SAMSUNG_S23_PROFILE[i].first; i++)
        set_prop(SAMSUNG_S23_PROFILE[i].first, SAMSUNG_S23_PROFILE[i].second);

    // Register all suspicious patterns
    if (add_pattern)
        for (int i = 0; SUSPICIOUS_PATTERNS[i]; i++)
            add_pattern(SUSPICIOUS_PATTERNS[i]);

    LOGI("Integrity bypass applied via fakeroot_preload API");
    return JNI_TRUE;
}

// ══════════════════════════════════════════════════════════════════════════════
// JNI — nativeSetPropOverride
// ══════════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_trinityvirtual_spoof_PropHookManager_nativeSetPropOverride(
        JNIEnv* env, jobject /*thiz*/, jstring key_j, jstring val_j) {

    const char* key = env->GetStringUTFChars(key_j, nullptr);
    const char* val = env->GetStringUTFChars(val_j, nullptr);
    fn_set_prop_t fn = get_set_prop();
    if (fn) fn(key, val);
    g_local_overrides[key] = val;
    env->ReleaseStringUTFChars(key_j, key);
    env->ReleaseStringUTFChars(val_j, val);
}

// ══════════════════════════════════════════════════════════════════════════════
// JNI — nativeAddHiddenPattern
// ══════════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_trinityvirtual_spoof_PropHookManager_nativeAddHiddenPattern(
        JNIEnv* env, jobject /*thiz*/, jstring pattern_j) {

    const char* pat = env->GetStringUTFChars(pattern_j, nullptr);
    fn_add_pattern_t fn = get_add_pattern();
    if (fn) fn(pat);
    env->ReleaseStringUTFChars(pattern_j, pat);
}

// ══════════════════════════════════════════════════════════════════════════════
// JNI — nativeCheckPropIntegrity
// ══════════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_spoof_PropHookManager_nativeCheckPropIntegrity(
        JNIEnv* /*env*/, jobject /*thiz*/) {

    const char* keys[] = {
        "ro.product.brand", "ro.build.fingerprint", "ro.hardware", nullptr
    };

    bool all_ok = true;
    for (int i = 0; keys[i]; i++) {
        char val[PROP_VALUE_MAX] = {};
        __system_property_get(keys[i], val);
        bool bad = false;
        for (int j = 0; SUSPICIOUS_PATTERNS[j]; j++) {
            if (strcasestr(val, SUSPICIOUS_PATTERNS[j])) { bad = true; break; }
        }
        if (bad) {
            LOGE("INTEGRITY FAIL: [%s]=[%s]", keys[i], val);
            all_ok = false;
        } else {
            LOGI("INTEGRITY OK:   [%s]=[%s]", keys[i], val);
        }
    }
    return all_ok ? JNI_TRUE : JNI_FALSE;
}

// ══════════════════════════════════════════════════════════════════════════════
// JNI — nativeSanitizeString
// ══════════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jstring JNICALL
Java_com_trinityvirtual_spoof_PropHookManager_nativeSanitizeString(
        JNIEnv* env, jobject /*thiz*/, jstring input_j) {

    const char* raw = env->GetStringUTFChars(input_j, nullptr);
    std::string val(raw);
    env->ReleaseStringUTFChars(input_j, raw);
    sanitize_value_impl(val);
    return env->NewStringUTF(val.c_str());
}

// ══════════════════════════════════════════════════════════════════════════════
// Legacy C API
// ══════════════════════════════════════════════════════════════════════════════

void add_prop_override(const char* key, const char* value) {
    fn_set_prop_t fn = get_set_prop();
    if (fn) fn(key, value);
    if (key && value) g_local_overrides[key] = value;
}

const char* get_prop_override(const char* key) {
    auto it = g_local_overrides.find(key ? key : "");
    return (it != g_local_overrides.end()) ? it->second.c_str() : nullptr;
}

void clear_prop_overrides() {
    fn_reset_props_t fn = get_reset_props();
    if (fn) fn();
    g_local_overrides.clear();
}
