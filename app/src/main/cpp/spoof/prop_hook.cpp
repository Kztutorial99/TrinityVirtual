/**
 * prop_hook.cpp — TrinityVirtual Active Property Hook Layer
 *
 * This file bridges the JNI world and libfakeroot_preload.so:
 *  • JNI calls from Kotlin/Java configure property overrides
 *  • Calls are forwarded to fakeroot_set_prop() / fakeroot_add_hidden_pattern()
 *    which are exported by libfakeroot_preload (loaded RTLD_GLOBAL)
 *
 * Additionally provides:
 *  • nativeApplyIntegrityBypass() — loads a comprehensive Samsung device profile
 *  • nativeCheckPropIntegrity()   — verifies hooks are responding correctly
 *  • nativeSanitizeString()       — strips suspicious patterns from a string
 */

#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <sys/system_properties.h>
#include <android/log.h>
#include <string>
#include <map>

#define TAG "TrinityProp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Types for exported symbols in libfakeroot_preload ─────────────────────
typedef void (*fn_set_prop_t)    (const char*, const char*);
typedef void (*fn_add_pattern_t) (const char*);
typedef void (*fn_reset_props_t) (void);

// ── Resolve fakeroot API (preload lib is RTLD_GLOBAL so RTLD_DEFAULT works) ─
static fn_set_prop_t get_set_prop() {
    static const fn_set_prop_t fn =
        (fn_set_prop_t)dlsym(RTLD_DEFAULT, "fakeroot_set_prop");
    if (!fn) LOGE("fakeroot_set_prop not found — preload lib not loaded?");
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

// ── Suspicious keyword list ────────────────────────────────────────────────
static const char* SUSPICIOUS_PATTERNS[] = {
    "qemu", "goldfish", "ranchu", "android_x86",
    "magisk", "zygisk", "xposed", "edxposed",
    "substrate", "frida", "gadget",
    "trinity", "virtualapp", "vmos",
    "bluestacks", "nox", "memu", "ldplayer",
    nullptr
};

// ── Samsung Galaxy S23 Ultra full device profile ───────────────────────────
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
    { "persist.sys.usb.config",      "adb"                                                  },
    { nullptr, nullptr }
};

// ── Internal helpers ───────────────────────────────────────────────────────

static void apply_profile(const std::pair<const char*, const char*>* profile) {
    fn_set_prop_t set_prop = get_set_prop();
    if (!set_prop) { LOGE("Cannot apply profile — fakeroot_set_prop unavailable"); return; }
    for (int i = 0; profile[i].first != nullptr; i++) {
        set_prop(profile[i].first, profile[i].second);
    }
}

// Remove suspicious substrings from value in-place
static void sanitize_value(std::string& val) {
    for (int i = 0; SUSPICIOUS_PATTERNS[i]; i++) {
        const char* pat = SUSPICIOUS_PATTERNS[i];
        size_t pos;
        std::string lower_val = val;
        for (char& c : lower_val) c = tolower(c);
        while ((pos = lower_val.find(pat)) != std::string::npos) {
            val.erase(pos, strlen(pat));
            lower_val.erase(pos, strlen(pat));
        }
    }
}

// ── Local prop override store (used by add/get/clear legacy API) ──────────
static std::map<std::string, std::string> g_local_overrides;

// ══════════════════════════════════════════════════════════════════════════════
// JNI — nativeApplyIntegrityBypass
// ══════════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_spoof_PropHookManager_nativeApplyIntegrityBypass(
        JNIEnv* /*env*/, jobject /*thiz*/) {

    LOGI("Applying integrity bypass — Samsung S23 Ultra profile");

    // 1. Apply device property profile
    apply_profile(SAMSUNG_S23_PROFILE);

    // 2. Register all suspicious patterns for filtering
    fn_add_pattern_t add_pattern = get_add_pattern();
    if (add_pattern) {
        for (int i = 0; SUSPICIOUS_PATTERNS[i]; i++)
            add_pattern(SUSPICIOUS_PATTERNS[i]);
    }

    LOGI("Integrity bypass applied: %zu props, patterns registered",
         sizeof(SAMSUNG_S23_PROFILE)/sizeof(SAMSUNG_S23_PROFILE[0]) - 1);
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

    fn_set_prop_t set_prop = get_set_prop();
    if (set_prop) set_prop(key, val);

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
    fn_add_pattern_t add = get_add_pattern();
    if (add) add(pat);
    env->ReleaseStringUTFChars(pattern_j, pat);
}

// ══════════════════════════════════════════════════════════════════════════════
// JNI — nativeCheckPropIntegrity
// Reads key props via __system_property_get and verifies they return
// our spoofed values (not qemu/emulator defaults).
// Returns true if all integrity checks pass.
// ══════════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jboolean JNICALL
Java_com_trinityvirtual_spoof_PropHookManager_nativeCheckPropIntegrity(
        JNIEnv* /*env*/, jobject /*thiz*/) {

    const char* check_keys[] = {
        "ro.product.brand",
        "ro.build.fingerprint",
        "ro.hardware",
        nullptr
    };

    bool all_ok = true;
    for (int i = 0; check_keys[i]; i++) {
        char val[PROP_VALUE_MAX] = {};
        __system_property_get(check_keys[i], val);
        bool suspicious = false;
        for (int j = 0; SUSPICIOUS_PATTERNS[j]; j++) {
            if (strcasestr(val, SUSPICIOUS_PATTERNS[j])) {
                suspicious = true;
                LOGE("INTEGRITY FAIL: [%s] = [%s] contains pattern [%s]",
                     check_keys[i], val, SUSPICIOUS_PATTERNS[j]);
                all_ok = false;
                break;
            }
        }
        if (!suspicious)
            LOGI("INTEGRITY OK: [%s] = [%s]", check_keys[i], val);
    }
    return all_ok ? JNI_TRUE : JNI_FALSE;
}

// ══════════════════════════════════════════════════════════════════════════════
// JNI — nativeSanitizeString
// Strips all suspicious patterns from an arbitrary string.
// Useful for sanitizing output before it reaches anti-cheat checks.
// ══════════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jstring JNICALL
Java_com_trinityvirtual_spoof_PropHookManager_nativeSanitizeString(
        JNIEnv* env, jobject /*thiz*/, jstring input_j) {

    const char* raw = env->GetStringUTFChars(input_j, nullptr);
    std::string val(raw);
    env->ReleaseStringUTFChars(input_j, raw);
    sanitize_value(val);
    return env->NewStringUTF(val.c_str());
}

// ══════════════════════════════════════════════════════════════════════════════
// Legacy C API — keep existing callers working
// ══════════════════════════════════════════════════════════════════════════════

void add_prop_override(const char* key, const char* value) {
    fn_set_prop_t set_prop = get_set_prop();
    if (set_prop) set_prop(key, value);
    if (key && value) g_local_overrides[key] = value;
    LOGI("Legacy add_prop_override: [%s]=[%s]", key, value);
}

const char* get_prop_override(const char* key) {
    auto it = g_local_overrides.find(key ? key : "");
    return (it != g_local_overrides.end()) ? it->second.c_str() : nullptr;
}

void clear_prop_overrides() {
    fn_reset_props_t reset = get_reset_props();
    if (reset) reset();
    g_local_overrides.clear();
}
