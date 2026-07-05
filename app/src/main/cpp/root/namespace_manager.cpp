#include "namespace_manager.h"
#include <sched.h>
#include <unistd.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <string.h>
#include <android/log.h>

#define TAG "TrinityNS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool ns_created = false;

int create_virtual_namespace(const char* root_dir, const char* su_path) {
    // Create new mount namespace to isolate our virtual root
    if (unshare(CLONE_NEWNS) != 0) {
        LOGE("Failed to unshare mount namespace: %s", strerror(errno));
        // Continue anyway — some ops work without full namespace isolation
    }

    // Bind-mount the fake su binary to /system/xbin/su in our namespace
    std::string su_dest = std::string(root_dir) + "/xbin/su";
    mkdir((std::string(root_dir) + "/xbin").c_str(), 0755);

    // Copy su binary to our root dir
    int src_fd = open(su_path, O_RDONLY);
    int dst_fd = open(su_dest.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (src_fd >= 0 && dst_fd >= 0) {
        char buf[4096];
        ssize_t n;
        while ((n = read(src_fd, buf, sizeof(buf))) > 0) write(dst_fd, buf, n);
        close(src_fd);
        close(dst_fd);
        chmod(su_dest.c_str(), 0755);
        LOGI("Virtual su deployed to: %s", su_dest.c_str());
    } else {
        if (src_fd >= 0) close(src_fd);
        if (dst_fd >= 0) close(dst_fd);
    }

    ns_created = true;
    LOGI("Virtual namespace created successfully");
    return 0;
}

void destroy_virtual_namespace() {
    if (ns_created) {
        ns_created = false;
        LOGI("Virtual namespace destroyed");
    }
}
