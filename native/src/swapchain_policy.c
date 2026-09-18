#include "kd_swapchain_policy.h"
#include <string.h>

static uint32_t clamp_size(uint32_t value, uint32_t low, uint32_t high) {
    return value < low ? low : value > high ? high : value;
}

int kd_choose_swapchain(const kd_surface_limits *limits, const kd_surface_format *formats,
    uint32_t format_count, const uint32_t *modes, uint32_t mode_count,
    uint32_t width, uint32_t height, kd_swapchain_choice *out) {
    if (!out) return 0;
    memset(out, 0, sizeof(*out));
    if (!limits || !formats || !modes || !format_count || !mode_count ||
        limits->min_width > limits->max_width || limits->min_height > limits->max_height ||
        !limits->min_images ||
        (limits->max_images && limits->min_images > limits->max_images)) return 0;

    kd_swapchain_choice choice = {0};
    choice.surface_format = formats[0];
    if (format_count == 1 && formats[0].format == KD_FORMAT_UNDEFINED) {
        choice.surface_format.format = KD_FORMAT_BGRA8_SRGB;
        choice.surface_format.color_space = KD_COLOR_SPACE_SRGB_NONLINEAR;
    } else {
        for (uint32_t i = 0; i < format_count; i++) {
            if (formats[i].format == KD_FORMAT_BGRA8_SRGB &&
                formats[i].color_space == KD_COLOR_SPACE_SRGB_NONLINEAR) {
                choice.surface_format = formats[i];
                break;
            }
        }
    }

    int fifo = 0, mailbox = 0;
    for (uint32_t i = 0; i < mode_count; i++) {
        fifo |= modes[i] == KD_PRESENT_MODE_FIFO;
        mailbox |= modes[i] == KD_PRESENT_MODE_MAILBOX;
    }
    if (!fifo || limits->min_images == UINT32_MAX) return 0;
    choice.present_mode = mailbox ? KD_PRESENT_MODE_MAILBOX : KD_PRESENT_MODE_FIFO;
    choice.image_count = limits->min_images + 1;
    if (limits->max_images && choice.image_count > limits->max_images)
        choice.image_count = limits->max_images;

    if (limits->current_width == KD_EXTENT_UNDEFINED &&
        limits->current_height == KD_EXTENT_UNDEFINED) {
        if (!width || !height) return 0;
        choice.width = clamp_size(width, limits->min_width, limits->max_width);
        choice.height = clamp_size(height, limits->min_height, limits->max_height);
    } else {
        if (limits->current_width == KD_EXTENT_UNDEFINED ||
            limits->current_height == KD_EXTENT_UNDEFINED) return 0;
        choice.width = limits->current_width;
        choice.height = limits->current_height;
    }
    if (!choice.width || !choice.height) return 0;
    *out = choice;
    return 1;
}
