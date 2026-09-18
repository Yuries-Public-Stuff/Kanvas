#ifndef KD_SWAPCHAIN_POLICY_H
#define KD_SWAPCHAIN_POLICY_H

#include <stdint.h>

#define KD_FORMAT_UNDEFINED 0u
#define KD_FORMAT_BGRA8_UNORM 44u
#define KD_FORMAT_BGRA8_SRGB 50u
#define KD_COLOR_SPACE_SRGB_NONLINEAR 0u
#define KD_PRESENT_MODE_MAILBOX 1u
#define KD_PRESENT_MODE_FIFO 2u
#define KD_EXTENT_UNDEFINED UINT32_MAX

typedef struct kd_surface_format {
    uint32_t format;
    uint32_t color_space;
} kd_surface_format;

typedef struct kd_surface_limits {
    uint32_t min_images;
    uint32_t max_images;
    uint32_t current_width;
    uint32_t current_height;
    uint32_t min_width;
    uint32_t min_height;
    uint32_t max_width;
    uint32_t max_height;
} kd_surface_limits;

typedef struct kd_swapchain_choice {
    kd_surface_format surface_format;
    uint32_t present_mode;
    uint32_t image_count;
    uint32_t width;
    uint32_t height;
} kd_swapchain_choice;

/* Returns 1 on success, 0 if the surface cannot be used. */
int kd_choose_swapchain(
    const kd_surface_limits *limits,
    const kd_surface_format *formats,
    uint32_t format_count,
    const uint32_t *present_modes,
    uint32_t present_mode_count,
    uint32_t requested_width,
    uint32_t requested_height,
    kd_swapchain_choice *out
);

#endif
