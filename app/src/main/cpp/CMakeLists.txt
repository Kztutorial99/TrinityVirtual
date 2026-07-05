cmake_minimum_required(VERSION 3.22.1)
project("trinity_virtual")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# Trinity Root Engine
add_library(trinity_root SHARED
    root/root_engine.cpp
    root/namespace_manager.cpp
    root/su_interceptor.cpp
    common/utils.cpp
)

# Trinity Spoof Engine
add_library(trinity_spoof SHARED
    spoof/device_spoof.cpp
    spoof/gps_spoof.cpp
    spoof/prop_hook.cpp
    common/utils.cpp
)

# Trinity Module Loader
add_library(trinity_module SHARED
    module/module_loader.cpp
    module/hook_manager.cpp
    common/utils.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(trinity_root ${log-lib} ${android-lib})
target_link_libraries(trinity_spoof ${log-lib} ${android-lib})
target_link_libraries(trinity_module ${log-lib} ${android-lib})
