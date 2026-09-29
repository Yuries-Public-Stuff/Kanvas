#ifndef KOTLIN_DISPLAY_H
#define KOTLIN_DISPLAY_H

#include <stdint.h>
#include "kd_draw_batch.h"

#if defined(_WIN32)
  #if defined(KD_BUILD_SHARED)
    #define KD_API __declspec(dllexport)
  #else
    #define KD_API __declspec(dllimport)
  #endif
#else
  #define KD_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KD_ABI_VERSION 16u

typedef enum kd_status {
    KD_OK = 0,
    KD_INVALID_ARGUMENT = 1,
    KD_VULKAN_UNAVAILABLE = 2,
    KD_VULKAN_ERROR = 3,
    KD_OUT_OF_MEMORY = 4,
    KD_NO_GRAPHICS_DEVICE = 5,
    KD_WINDOW_UNAVAILABLE = 6,
    KD_UNSUPPORTED_PLATFORM = 7,
    KD_SURFACE_UNAVAILABLE = 8,
    KD_SWAPCHAIN_OUT_OF_DATE = 9,
    KD_INPUT_OVERFLOW = 10,
    KD_DRAW_UNSUPPORTED_OPERATION = 11,
    KD_GRAPHICS_ERROR = 12
} kd_status;

typedef struct kd_vulkan_probe {
    uint32_t abi_version;
    uint32_t instance_api_version;
    uint32_t physical_device_count;
    uint32_t graphics_device_count;
    int32_t native_result;
} kd_vulkan_probe;

typedef struct kd_window kd_window;
typedef struct kd_vk_frame kd_vk_frame;
typedef struct kd_gl_frame kd_gl_frame;
typedef struct kd_d3d9_frame kd_d3d9_frame;
typedef struct kd_metal_frame kd_metal_frame;

typedef enum kd_window_platform {
    KD_WINDOW_PLATFORM_UNKNOWN = 0,
    KD_WINDOW_PLATFORM_WIN32 = 1,
    KD_WINDOW_PLATFORM_XLIB = 2,
    KD_WINDOW_PLATFORM_MACOS = 3
} kd_window_platform;

typedef struct kd_native_window_handles {
    kd_window_platform platform;
    void *display;
    uintptr_t window;
} kd_native_window_handles;

typedef enum kd_pointer_kind {
    KD_POINTER_DOWN = 1,
    KD_POINTER_UP = 2,
    KD_POINTER_MOVE = 3,
    KD_POINTER_SCROLL = 4,
    KD_KEY_DOWN = 5,
    KD_KEY_UP = 6,
    KD_TEXT_INPUT = 7,
    KD_POINTER_RIGHT_DOWN = 8,
    KD_POINTER_RIGHT_UP = 9,
    KD_POINTER_MIDDLE_DOWN = 10,
    KD_POINTER_MIDDLE_UP = 11,
    KD_WINDOW_FOCUS_GAINED = 12,
    KD_WINDOW_FOCUS_LOST = 13,
    KD_POINTER_SCROLL_HORIZONTAL = 14,
    KD_POINTER_ENTER = 15,
    KD_POINTER_EXIT = 16
} kd_pointer_kind;

typedef struct kd_pointer_event {
    kd_pointer_kind kind;
    int32_t x;
    int32_t y;
} kd_pointer_event;

typedef struct kd_vulkan_surface_probe {
    uint32_t abi_version;
    uint32_t surface_created;
    uint32_t physical_device_count;
    uint32_t presentable_device_count;
    int32_t native_result;
    uint32_t swapchain_device_count;
    uint32_t chosen_format;
    uint32_t chosen_color_space;
    uint32_t chosen_present_mode;
    uint32_t chosen_image_count;
    uint32_t chosen_width;
    uint32_t chosen_height;
} kd_vulkan_surface_probe;

KD_API uint32_t kd_abi_version(void);
KD_API kd_status kd_probe_vulkan(kd_vulkan_probe *out);

KD_API kd_status kd_window_create(uint32_t width, uint32_t height, const char *title, kd_window **out);
KD_API kd_status kd_window_create_child(uintptr_t parent, uint32_t width, uint32_t height, kd_window **out);
KD_API kd_status kd_window_resize(kd_window *window, uint32_t width, uint32_t height);
KD_API kd_status kd_window_poll(kd_window *window, int *should_close);
KD_API kd_status kd_window_next_pointer(kd_window *window, kd_pointer_event *out, int *has_event);
KD_API kd_status kd_window_get_native_handles(kd_window *window, kd_native_window_handles *out);
KD_API kd_status kd_window_get_size(kd_window *window, uint32_t *width, uint32_t *height);
KD_API void kd_window_destroy(kd_window *window);

KD_API kd_status kd_probe_vulkan_surface(kd_window *window, kd_vulkan_surface_probe *out);

KD_API kd_status kd_vk_frame_create(kd_window *window, kd_vk_frame **out);
KD_API kd_status kd_vk_frame_present(kd_vk_frame *frame, float red, float green, float blue, float alpha);
KD_API kd_status kd_vk_frame_present_commands(kd_vk_frame *frame, const kd_draw_command *commands, uint32_t count);
KD_API kd_status kd_vk_frame_upload_texture(kd_vk_frame *frame, uint32_t texture_id, uint32_t width, uint32_t height, const uint32_t *argb);
KD_API void kd_vk_frame_release_texture(kd_vk_frame *frame, uint32_t texture_id);
KD_API void kd_vk_frame_destroy(kd_vk_frame *frame);
KD_API kd_status kd_gdi_frame_present_commands(kd_window *window, const kd_draw_command *commands, uint32_t count);
KD_API kd_status kd_gl_frame_create(kd_window *window, kd_gl_frame **out);
KD_API kd_status kd_gl_frame_present_commands(kd_gl_frame *frame, const kd_draw_command *commands, uint32_t count);
KD_API kd_status kd_gl_frame_upload_texture(kd_gl_frame *frame, uint32_t texture_id, uint32_t width, uint32_t height, const uint32_t *argb);
KD_API void kd_gl_frame_release_texture(kd_gl_frame *frame, uint32_t texture_id);
KD_API void kd_gl_frame_destroy(kd_gl_frame *frame);
KD_API kd_status kd_d3d9_frame_create(kd_window *window, kd_d3d9_frame **out);
KD_API kd_status kd_d3d9_frame_present_commands(kd_d3d9_frame *frame, const kd_draw_command *commands, uint32_t count);
KD_API kd_status kd_d3d9_frame_upload_texture(kd_d3d9_frame *frame, uint32_t texture_id, uint32_t width, uint32_t height, const uint32_t *argb);
KD_API void kd_d3d9_frame_release_texture(kd_d3d9_frame *frame, uint32_t texture_id);
KD_API void kd_d3d9_frame_destroy(kd_d3d9_frame *frame);
KD_API kd_status kd_metal_frame_create(kd_window *window, kd_metal_frame **out);
KD_API kd_status kd_metal_frame_present_commands(kd_metal_frame *frame, const kd_draw_command *commands, uint32_t count);
KD_API kd_status kd_metal_frame_upload_texture(kd_metal_frame *frame, uint32_t texture_id, uint32_t width, uint32_t height, const uint32_t *argb);
KD_API void kd_metal_frame_release_texture(kd_metal_frame *frame, uint32_t texture_id);
KD_API void kd_metal_frame_destroy(kd_metal_frame *frame);

KD_API kd_status kd_telemetry_open(const char *engine);
KD_API kd_status kd_telemetry_record(uint64_t elapsed_ns, uint64_t interval_ns,
                                    uint64_t scene_ns, uint64_t render_ns,
                                    uint32_t command_count, uint32_t width, uint32_t height);
KD_API void kd_telemetry_close(void);

#ifdef __cplusplus
}
#endif

#endif
