#ifndef KD_DRAW_BATCH_H
#define KD_DRAW_BATCH_H

#include <stdint.h>

#define KD_DRAW_MAX_COMMANDS 65536u
#define KD_DRAW_KIND_MASK 0xffu
#define KD_DRAW_RESOURCE_SHIFT 8u
#define KD_DRAW_RESOURCE_MAX 0x00ffffffu

#define KD_DRAW_BASE_KIND(value) ((uint32_t)(value) & KD_DRAW_KIND_MASK)
#define KD_DRAW_RESOURCE_ID(value) ((uint32_t)(value) >> KD_DRAW_RESOURCE_SHIFT)
#define KD_DRAW_ENCODE_RESOURCE(kind, resource) \
    ((uint32_t)(kind) | ((uint32_t)(resource) << KD_DRAW_RESOURCE_SHIFT))

typedef enum kd_draw_kind {
    KD_DRAW_CLEAR = 1,
    KD_DRAW_RECT = 2,
    KD_DRAW_CLEAR_RECT = 3,
    KD_DRAW_RETAIN = 4,
    KD_DRAW_IMAGE = 5,
    KD_DRAW_GLYPH = 6
} kd_draw_kind;

typedef struct kd_draw_color {
    float red, green, blue, alpha;
} kd_draw_color;

typedef struct kd_draw_command {
    uint32_t kind;
    float x, y, width, height;
    kd_draw_color color;
    float u0, v0, u1, v1;
} kd_draw_command;

typedef struct kd_draw_rect {
    uint32_t kind;
    uint32_t resource_id;
    uint32_t x, y, width, height;
    kd_draw_color color;
    float u0, v0, u1, v1;
} kd_draw_rect;

typedef struct kd_draw_batch {
    kd_draw_color clear;
    uint32_t rect_count;
    uint32_t clear_enabled;
} kd_draw_batch;

typedef enum kd_draw_result {
    KD_DRAW_OK = 0,
    KD_DRAW_INVALID = 1,
    KD_DRAW_UNSUPPORTED = 2,
    KD_DRAW_CAPACITY = 3
} kd_draw_result;

/* Output rectangles are copied into caller-owned storage. */
kd_draw_result kd_prepare_draw_batch(
    uint32_t viewport_width, uint32_t viewport_height,
    const kd_draw_command *commands, uint32_t count,
    kd_draw_rect *rects, uint32_t capacity, kd_draw_batch *out
);

#endif
