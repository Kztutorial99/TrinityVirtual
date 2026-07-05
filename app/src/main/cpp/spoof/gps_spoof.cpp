#include <android/log.h>
#define TAG "TrinityGPS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static double g_lat = 0.0, g_lon = 0.0;
static bool g_gps_active = false;

void set_fake_gps(double lat, double lon) {
    g_lat = lat; g_lon = lon; g_gps_active = true;
    LOGI("GPS spoofed to %.6f, %.6f", lat, lon);
}

void reset_gps() { g_gps_active = false; }
bool is_gps_active() { return g_gps_active; }
double get_fake_lat() { return g_lat; }
double get_fake_lon() { return g_lon; }
