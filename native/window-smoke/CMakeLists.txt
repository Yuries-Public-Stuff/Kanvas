cmake_minimum_required(VERSION 3.21)
project(kotlin_display_window_smoke LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# No Vulkan SDK needed.
add_executable(kd_window_smoke ../src/window.c ../tests/window_smoke.c)
target_include_directories(kd_window_smoke PRIVATE ../include)
target_compile_definitions(kd_window_smoke PRIVATE KD_BUILD_SHARED)

add_executable(pointer_smoke ../src/window.c ../tests/pointer_smoke.c)
target_include_directories(pointer_smoke PRIVATE ../include)
target_compile_definitions(pointer_smoke PRIVATE KD_BUILD_SHARED)

add_executable(pointer_queue_smoke ../tests/pointer_queue_smoke.c)
target_include_directories(pointer_queue_smoke PRIVATE ../include)
target_compile_definitions(pointer_queue_smoke PRIVATE KD_BUILD_SHARED)

if(WIN32)
    target_link_libraries(kd_window_smoke PRIVATE user32)
    target_link_libraries(pointer_smoke PRIVATE user32)
    target_link_libraries(pointer_queue_smoke PRIVATE user32)
    add_executable(win32_quit_smoke ../src/window.c ../tests/win32_quit_smoke.c)
    target_include_directories(win32_quit_smoke PRIVATE ../include)
    target_compile_definitions(win32_quit_smoke PRIVATE KD_BUILD_SHARED)
    target_link_libraries(win32_quit_smoke PRIVATE user32)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(X11 REQUIRED)
    target_link_libraries(kd_window_smoke PRIVATE X11::X11)
    target_link_libraries(pointer_smoke PRIVATE X11::X11)
    target_link_libraries(pointer_queue_smoke PRIVATE X11::X11)
endif()

add_executable(first_frame_loop_smoke ../tests/first_frame_loop_smoke.c)
target_include_directories(first_frame_loop_smoke PRIVATE ../include)

add_executable(first_frame_close_smoke ../tests/first_frame_close_smoke.c)
target_include_directories(first_frame_close_smoke PRIVATE ../include)

add_executable(first_frame_failure_smoke ../tests/first_frame_failure_smoke.c)
target_include_directories(first_frame_failure_smoke PRIVATE ../include)

include(CTest)
add_test(NAME window_smoke COMMAND kd_window_smoke)
add_test(NAME pointer_smoke COMMAND pointer_smoke)
add_test(NAME pointer_queue_smoke COMMAND pointer_queue_smoke)
add_test(NAME first_frame_loop_smoke COMMAND first_frame_loop_smoke)
add_test(NAME first_frame_close_smoke COMMAND first_frame_close_smoke)
add_test(NAME first_frame_failure_smoke COMMAND first_frame_failure_smoke)
if(WIN32)
    add_test(NAME win32_quit_smoke COMMAND win32_quit_smoke)
endif()
