cmake_minimum_required(VERSION 3.22.1)
project("trinity_virtual")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

find_library(log-lib   log)
find_library(android-lib android)

# ── libfakeroot_preload ───────────────────────────────────────────────────────
#
# Standalone injectable shared library.
# Loaded into target processes via:
#   - LD_PRELOAD env var  (child processes: popen, exec, Runtime.exec)
#   - dlopen(RTLD_GLOBAL) (host process: covers guest native libs loaded later)
#
# Overrides: getuid, geteuid, getgid, getegid, access, faccessat, stat, lstat,
#            __xstat, __lxstat, open  — to fake root presence without namespaces.
#
# Must NOT link against trinity_root (avoid circular dependency).
# -fvisibility=default ensures all override symbols are exported.
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
add_library(trinity_module SHARED
    module/module_loader.cpp
    module/hook_manager.cpp
    common/utils.cpp
)
target_link_libraries(trinity_module
    ${log-lib}
    ${android-lib}
)
