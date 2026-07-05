#include <android/log.h>
#include <sys/system_properties.h>
#include <string>
#include <map>

#define TAG "TrinityProp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::map<std::string, std::string> g_prop_overrides;

void add_prop_override(const char* key, const char* value) {
    g_prop_overrides[key] = value;
    LOGI("Prop override: [%s] = [%s]", key, value);
}

const char* get_prop_override(const char* key) {
    auto it = g_prop_overrides.find(key);
    if (it != g_prop_overrides.end()) return it->second.c_str();
    return nullptr;
}

void clear_prop_overrides() { g_prop_overrides.clear(); }
