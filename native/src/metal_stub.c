#include "kotlin_display.h"

#if !defined(__APPLE__)

struct kd_metal_frame { int unused; };

kd_status kd_metal_frame_create(kd_window *window, kd_metal_frame **out) {
    if (!window || !out) return KD_INVALID_ARGUMENT;
    *out = 0;
    return KD_UNSUPPORTED_PLATFORM;
}

kd_status kd_metal_frame_present_commands(
    kd_metal_frame *frame,
    const kd_draw_command *commands,
    uint32_t count
) {
    if (!frame || !commands || !count) return KD_INVALID_ARGUMENT;
    return KD_UNSUPPORTED_PLATFORM;
}

kd_status kd_metal_frame_upload_texture(
    kd_metal_frame *frame,
    uint32_t texture_id,
    uint32_t width,
    uint32_t height,
    const uint32_t *argb
) {
    (void)frame;
    (void)texture_id;
    (void)width;
    (void)height;
    (void)argb;
    return KD_UNSUPPORTED_PLATFORM;
}

void kd_metal_frame_release_texture(
    kd_metal_frame *frame,
    uint32_t texture_id
) {
    (void)frame;
    (void)texture_id;
}

void kd_metal_frame_destroy(kd_metal_frame *frame) {
    (void)frame;
}

#endif
