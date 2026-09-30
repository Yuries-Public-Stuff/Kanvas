#include "kotlin_display.h"
#include <string.h>

uint32_t kd_abi_version(void) {
    return KD_ABI_VERSION;
}

kd_status kd_probe_vulkan(kd_vulkan_probe *out) {
    if (!out) return KD_INVALID_ARGUMENT;
    memset(out, 0, sizeof(*out));
    out->abi_version = KD_ABI_VERSION;
    return KD_VULKAN_UNAVAILABLE;
}

kd_status kd_probe_vulkan_surface(kd_window *window, kd_vulkan_surface_probe *out) {
    if (!window || !out) return KD_INVALID_ARGUMENT;
    memset(out, 0, sizeof(*out));
    out->abi_version = KD_ABI_VERSION;
    return KD_VULKAN_UNAVAILABLE;
}

kd_status kd_vk_frame_create(kd_window *window, kd_vk_frame **out) {
    if (!window || !out) return KD_INVALID_ARGUMENT;
    *out = NULL;
    return KD_VULKAN_UNAVAILABLE;
}

kd_status kd_vk_frame_present(kd_vk_frame *frame, float red, float green, float blue, float alpha) {
    (void)red; (void)green; (void)blue; (void)alpha;
    if (!frame) return KD_INVALID_ARGUMENT;
    return KD_VULKAN_UNAVAILABLE;
}

kd_status kd_vk_frame_present_commands(kd_vk_frame *frame, const kd_draw_command *commands, uint32_t count) {
    if (!frame || !commands || !count || count > KD_DRAW_MAX_COMMANDS) return KD_INVALID_ARGUMENT;
    return KD_VULKAN_UNAVAILABLE;
}

kd_status kd_vk_frame_upload_texture(
    kd_vk_frame *frame,
    uint32_t texture_id,
    uint32_t width,
    uint32_t height,
    const uint32_t *argb
) {
    (void)texture_id;
    (void)width;
    (void)height;
    (void)argb;
    if (!frame) return KD_INVALID_ARGUMENT;
    return KD_VULKAN_UNAVAILABLE;
}

void kd_vk_frame_release_texture(
    kd_vk_frame *frame,
    uint32_t texture_id
) {
    (void)frame;
    (void)texture_id;
}

void kd_vk_frame_destroy(kd_vk_frame *frame) {
    (void)frame;
}
