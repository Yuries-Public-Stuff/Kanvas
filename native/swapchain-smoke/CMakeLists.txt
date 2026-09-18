cmake_minimum_required(VERSION 3.21)
project(kotlin_display_swapchain_smoke LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_executable(kd_swapchain_smoke ../src/swapchain_policy.c ../tests/swapchain_policy_smoke.c)
target_include_directories(kd_swapchain_smoke PRIVATE ../include)

include(CTest)
add_test(NAME swapchain_policy_smoke COMMAND kd_swapchain_smoke)
