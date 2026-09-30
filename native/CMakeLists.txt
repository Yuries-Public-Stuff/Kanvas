cmake_minimum_required(VERSION 3.21)
project(kanvas_native LANGUAGES C)

if(APPLE)
    enable_language(OBJC)
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

option(KD_ENABLE_VULKAN "Build the Vulkan backend when a loader is available" ON)
option(KD_REQUIRE_VULKAN "Fail configuration when a Vulkan loader is unavailable" OFF)
option(KD_REQUIRE_JNI "Fail configuration when the JVM GPU bridge cannot be built" OFF)
set(KD_HAS_VULKAN OFF)

if(KD_ENABLE_VULKAN)
    find_package(Vulkan QUIET)
    if(Vulkan_FOUND)
        set(KD_HAS_VULKAN ON)
        set(KD_VULKAN_INCLUDE_DIR "${Vulkan_INCLUDE_DIRS}")
        set(KD_VULKAN_LINK_TARGET Vulkan::Vulkan)
    elseif(NOT APPLE)
        find_library(KD_VULKAN_LIBRARY NAMES vulkan vulkan-1)
        if(NOT KD_VULKAN_LIBRARY AND UNIX)
            find_file(KD_VULKAN_RUNTIME_FILE NAMES libvulkan.so.1
                PATHS /usr/lib /lib /usr/local/lib
                PATH_SUFFIXES x86_64-linux-gnu aarch64-linux-gnu lib64
            )
            if(KD_VULKAN_RUNTIME_FILE)
                set(KD_VULKAN_LIBRARY "${KD_VULKAN_RUNTIME_FILE}")
            endif()
        endif()

        if(NOT KD_VULKAN_LIBRARY)
            if(KD_REQUIRE_VULKAN)
                message(FATAL_ERROR "Vulkan loader missing. Install the Vulkan SDK or loader library.")
            else()
                message(STATUS "Vulkan loader missing; building ABI-compatible Vulkan stubs.")
            endif()
        else()
            option(KD_FETCH_VULKAN_HEADERS "Fetch Vulkan headers if the SDK is missing" ON)
            if(NOT KD_FETCH_VULKAN_HEADERS)
                message(FATAL_ERROR "Vulkan SDK headers missing. Install them or enable KD_FETCH_VULKAN_HEADERS.")
            endif()

            include(FetchContent)
            FetchContent_Declare(kd_vulkan_headers
                URL "https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.4.309.tar.gz"
            )
            FetchContent_GetProperties(kd_vulkan_headers)
            if(NOT kd_vulkan_headers_POPULATED)
                FetchContent_Populate(kd_vulkan_headers)
            endif()
            set(KD_VULKAN_INCLUDE_DIR "${kd_vulkan_headers_SOURCE_DIR}/include")
            set(KD_HAS_VULKAN ON)
            set(KD_VULKAN_LINK_TARGET "${KD_VULKAN_LIBRARY}")
        endif()
    else()
        message(STATUS "Vulkan loader not found on macOS; building ABI-compatible Vulkan stubs.")
    endif()
else()
    message(STATUS "Vulkan backend disabled; building ABI-compatible Vulkan stubs.")
endif()

set(KD_NATIVE_SOURCES
    src/gdi_frame.c
    src/opengl_frame.c
    src/d3d9_frame.c
    src/frame_telemetry.c
    src/swapchain_policy.c
    src/draw_batch.c
    src/window.c
)

if(APPLE)
    list(APPEND KD_NATIVE_SOURCES
        src/window_macos.m
        src/metal_frame.m
    )
else()
    list(APPEND KD_NATIVE_SOURCES src/metal_stub.c)
endif()

if(KD_HAS_VULKAN)
    list(APPEND KD_NATIVE_SOURCES
        src/vulkan_probe.c
        src/vulkan_surface_probe.c
        src/vulkan_frame.c
        src/vulkan_rect_pass.c
    )
else()
    list(APPEND KD_NATIVE_SOURCES src/vulkan_stub.c)
endif()

add_library(kanvas_native SHARED ${KD_NATIVE_SOURCES})
target_include_directories(kanvas_native
    PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
if(KD_HAS_VULKAN)
    target_include_directories(kanvas_native PRIVATE "${KD_VULKAN_INCLUDE_DIR}")
    target_link_libraries(kanvas_native PRIVATE "${KD_VULKAN_LINK_TARGET}")
endif()
target_compile_definitions(kanvas_native PRIVATE KD_BUILD_SHARED)

find_package(JNI QUIET COMPONENTS AWT JVM)
if(JNI_FOUND)
    target_sources(kanvas_native PRIVATE src/jni_display_bridge.c)
    target_include_directories(kanvas_native PRIVATE ${JNI_INCLUDE_DIRS})
    target_compile_definitions(kanvas_native PRIVATE KD_HAS_JNI=1)
    if((APPLE OR CMAKE_SYSTEM_NAME STREQUAL "Linux") AND JAVA_AWT_LIBRARY)
        target_link_libraries(kanvas_native PRIVATE "${JAVA_AWT_LIBRARY}")
    endif()
    message(STATUS "JNI bridge enabled")
else()
    if(KD_REQUIRE_JNI)
        message(FATAL_ERROR "JNI headers not found. Set JAVA_HOME to a JDK 17+ installation.")
    endif()
    message(STATUS "JNI headers not found; JVM GPU bridge disabled")
endif()

if(WIN32)
    target_link_libraries(kanvas_native PRIVATE user32 gdi32 opengl32 d3d9 imm32)
    add_executable(patch_bay_win32 examples/patch_bay_win32.c)
    target_link_libraries(patch_bay_win32 PRIVATE user32 gdi32)
    add_executable(model_benchmark_opengl examples/model_benchmark_opengl.c)
    target_link_libraries(model_benchmark_opengl PRIVATE kanvas_native user32 gdi32 opengl32 m)
    add_executable(model_benchmark_d3d9 examples/model_benchmark_d3d9.c)
    target_link_libraries(model_benchmark_d3d9 PRIVATE kanvas_native user32 d3d9 m)
    add_executable(model_benchmark_gdi examples/model_benchmark_gdi.c)
    target_link_libraries(model_benchmark_gdi PRIVATE kanvas_native user32 gdi32 m)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(X11 REQUIRED)
    target_link_libraries(kanvas_native PRIVATE X11::X11)
elseif(APPLE)
    find_library(COCOA_FRAMEWORK Cocoa REQUIRED)
    find_library(QUARTZCORE_FRAMEWORK QuartzCore REQUIRED)
    find_library(METAL_FRAMEWORK Metal REQUIRED)
    target_link_libraries(kanvas_native PRIVATE
        "${COCOA_FRAMEWORK}"
        "${QUARTZCORE_FRAMEWORK}"
        "${METAL_FRAMEWORK}"
    )
    set_source_files_properties(
        src/window_macos.m
        src/metal_frame.m
        PROPERTIES COMPILE_FLAGS "-fobjc-arc -fblocks"
    )
endif()

add_executable(surface_info examples/surface_info.c)
target_link_libraries(surface_info PRIVATE kanvas_native)

add_executable(first_frame examples/first_frame.c)
target_link_libraries(first_frame PRIVATE kanvas_native)

include(CTest)
if(BUILD_TESTING)
    add_executable(vulkan_probe_smoke tests/vulkan_probe_smoke.c)
    target_link_libraries(vulkan_probe_smoke PRIVATE kanvas_native)
    add_test(NAME vulkan_probe_smoke COMMAND vulkan_probe_smoke)

    add_executable(window_smoke tests/window_smoke.c)
    target_link_libraries(window_smoke PRIVATE kanvas_native)
    add_test(NAME window_smoke COMMAND window_smoke)

    add_executable(pointer_smoke tests/pointer_smoke.c)
    target_link_libraries(pointer_smoke PRIVATE kanvas_native)
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        target_link_libraries(pointer_smoke PRIVATE X11::X11)
    endif()
    add_test(NAME pointer_smoke COMMAND pointer_smoke)

    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_executable(x11_child_smoke tests/x11_child_smoke.c)
        target_link_libraries(x11_child_smoke PRIVATE kanvas_native X11::X11)
        add_test(NAME x11_child_smoke COMMAND x11_child_smoke)
    endif()

    add_executable(pointer_queue_smoke tests/pointer_queue_smoke.c)
    target_include_directories(pointer_queue_smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_compile_definitions(pointer_queue_smoke PRIVATE KD_BUILD_SHARED)
    if(WIN32)
        target_link_libraries(pointer_queue_smoke PRIVATE user32 imm32)
    elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        target_link_libraries(pointer_queue_smoke PRIVATE X11::X11)
    endif()
    add_test(NAME pointer_queue_smoke COMMAND pointer_queue_smoke)

    add_executable(surface_probe_smoke tests/surface_probe_smoke.c)
    target_link_libraries(surface_probe_smoke PRIVATE kanvas_native)
    add_test(NAME surface_probe_smoke COMMAND surface_probe_smoke)

    add_executable(swapchain_policy_smoke src/swapchain_policy.c tests/swapchain_policy_smoke.c)
    target_include_directories(swapchain_policy_smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
    add_test(NAME swapchain_policy_smoke COMMAND swapchain_policy_smoke)

    add_executable(draw_batch_smoke src/draw_batch.c tests/draw_batch_smoke.c)
    target_include_directories(draw_batch_smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
    add_test(NAME draw_batch_smoke COMMAND draw_batch_smoke)

    add_executable(texture_protocol_smoke src/draw_batch.c tests/texture_protocol_smoke.c)
    target_include_directories(texture_protocol_smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
    add_test(NAME texture_protocol_smoke COMMAND texture_protocol_smoke)

    add_executable(frame_commands_smoke tests/frame_commands_smoke.c)
    target_link_libraries(frame_commands_smoke PRIVATE kanvas_native)
    add_test(NAME frame_commands_smoke COMMAND frame_commands_smoke)

    add_executable(gdi_frame_smoke tests/gdi_frame_smoke.c)
    target_link_libraries(gdi_frame_smoke PRIVATE kanvas_native)
    add_test(NAME gdi_frame_smoke COMMAND gdi_frame_smoke)

    add_executable(graphics_backends_smoke tests/graphics_backends_smoke.c)
    target_link_libraries(graphics_backends_smoke PRIVATE kanvas_native)
    add_test(NAME graphics_backends_smoke COMMAND graphics_backends_smoke)

    add_executable(frame_telemetry_smoke tests/frame_telemetry_smoke.c)
    target_link_libraries(frame_telemetry_smoke PRIVATE kanvas_native)
    add_test(NAME frame_telemetry_smoke COMMAND frame_telemetry_smoke)

    add_executable(torus_mesh_smoke tests/torus_mesh_smoke.c)
    if(NOT WIN32)
        target_link_libraries(torus_mesh_smoke PRIVATE m)
    endif()
    add_test(NAME torus_mesh_smoke COMMAND torus_mesh_smoke)

    add_executable(model_telemetry_smoke tests/model_telemetry_smoke.c)
    if(NOT WIN32)
        target_link_libraries(model_telemetry_smoke PRIVATE m)
    endif()
    add_test(NAME model_telemetry_smoke COMMAND model_telemetry_smoke)
endif()
