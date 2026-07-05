cmake_minimum_required(VERSION 3.22.1)
project("trinity_virtual")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

find_library(log-lib    log)
find_library(android-lib android)

# ── libfakeroot_preload ───────────────────────────────────────────────────────
#
# Standalone injectable shared library loaded via:
#   LD_PRELOAD env var        → all child processes (popen/exec/Runtime.exec)
#   dlopen(RTLD_GLOBAL)       → host process (covers guest native libs)
#
# Hooks (Layer 1 — UID):
#   getuid / geteuid / getgid / getegid → 0
#
# Hooks (Layer 2 — su presence):
#   access / faccessat / stat / lstat / __xstat / __lxstat / open
#
# Hooks (Layer 3 — integrity bypass):
#   __system_property_get  → Samsung S23 profile + pattern filter
#   fopen("/proc/self/maps") → memfd with filtered content
#
# Exported config API (reachable via dlsym(RTLD_DEFAULT, ...)):
#   fakeroot_set_prop / fakeroot_add_hidden_pattern / fakeroot_reset_props
#
# -fvisibility=default  : all override symbols exported for RTLD_GLOBAL takeover
# Must NOT link trinity_root (circular dependency)
# ─────────────────────────────────────────────────────────────────────────────
add_library(fakeroot_preload SHARED
    root/ld_preload_lib.cpp
)
target_compile_options(fakeroot_preload PRIVATE
    -fvisibility=default
    -O2
)
target_link_libraries(fakeroot_preload
    ${log-lib}
)

# ── trinity_root ──────────────────────────────────────────────────────────────
add_library(trinity_root SHARED
    root/root_engine.cpp
    root/namespace_manager.cpp
    root/su_interceptor.cpp
    common/utils.cpp
)
target_link_libraries(trinity_root
    ${log-lib}
)

# ── trinity_spoof ─────────────────────────────────────────────────────────────
# prop_hook.cpp calls fakeroot_set_prop / fakeroot_add_hidden_pattern via
# dlsym(RTLD_DEFAULT) — no link-time dependency on fakeroot_preload needed.
add_library(trinity_spoof SHARED
    spoof/device_spoof.cpp
    spoof/gps_spoof.cpp
    spoof/prop_hook.cpp
    common/utils.cpp
)
target_link_libraries(trinity_spoof
    ${log-lib}
    ${android-lib}
)

# ── trinity_module ────────────────────────────────────────────────────────────
# Uses memfd_create (via syscall) for in-memory .so loading — no extra lib needed.
# AAssetManager is not used from JNI (bytes are passed as jbyteArray from Kotlin).
add_library(trinity_module SHARED
    module/module_loader.cpp
    module/hook_manager.cpp
    common/utils.cpp
)
target_link_libraries(trinity_module
    ${log-lib}
    ${android-lib}
)
