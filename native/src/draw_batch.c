#include "kd_draw_batch.h"
#include <math.h>
#include <string.h>

static int valid_color(kd_draw_color color) {
    const float channels[] = {
        color.red, color.green, color.blue, color.alpha
    };
    for (unsigned i = 0; i < 4; ++i) {
        if (!isfinite(channels[i]) ||
            channels[i] < 0.0f ||
            channels[i] > 1.0f) {
            return 0;
        }
    }
    return 1;
}

static uint32_t edge(double value, uint32_t bound, int round_up) {
    if (value <= 0.0) return 0;
    if (value >= (double)bound) return bound;
    uint32_t whole = (uint32_t)value;
    return whole + (round_up && (double)whole < value ? 1u : 0u);
}

kd_draw_result kd_prepare_draw_batch(
    uint32_t viewport_width,
    uint32_t viewport_height,
    const kd_draw_command *commands,
    uint32_t count,
    kd_draw_rect *rects,
    uint32_t capacity,
    kd_draw_batch *out
) {
    if (!out) return KD_DRAW_INVALID;
    memset(out, 0, sizeof(*out));

    if (!viewport_width || !viewport_height ||
        !commands || !count ||
        count > KD_DRAW_MAX_COMMANDS ||
        (capacity && !rects)) {
        return KD_DRAW_INVALID;
    }

    const uint32_t first_kind = KD_DRAW_BASE_KIND(commands[0].kind);
    if ((first_kind != KD_DRAW_CLEAR &&
         first_kind != KD_DRAW_RETAIN) ||
        !valid_color(commands[0].color)) {
        return KD_DRAW_INVALID;
    }

    kd_draw_batch batch = {0};
    batch.clear = commands[0].color;
    batch.clear_enabled = first_kind == KD_DRAW_CLEAR;

    for (uint32_t i = 1; i < count; ++i) {
        const kd_draw_command *cmd = &commands[i];
        const uint32_t kind = KD_DRAW_BASE_KIND(cmd->kind);
        const uint32_t resource_id = KD_DRAW_RESOURCE_ID(cmd->kind);

        if ((kind != KD_DRAW_RECT &&
             kind != KD_DRAW_CLEAR_RECT &&
             kind != KD_DRAW_IMAGE &&
             kind != KD_DRAW_GLYPH) ||
            !isfinite(cmd->x) ||
            !isfinite(cmd->y) ||
            !isfinite(cmd->width) ||
            !isfinite(cmd->height) ||
            cmd->width < 0.0f ||
            cmd->height < 0.0f) {
            return KD_DRAW_INVALID;
        }

        if (kind == KD_DRAW_IMAGE ||
            kind == KD_DRAW_GLYPH) {
            if (!resource_id ||
                !valid_color(cmd->color) ||
                !isfinite(cmd->u0) ||
                !isfinite(cmd->v0) ||
                !isfinite(cmd->u1) ||
                !isfinite(cmd->v1)) {
                return KD_DRAW_INVALID;
            }
        } else if (!valid_color(cmd->color)) {
            return KD_DRAW_INVALID;
        }

        double left = (double)cmd->x;
        double top = (double)cmd->y;
        double right = left + (double)cmd->width;
        double bottom = top + (double)cmd->height;

        if (right <= 0.0 ||
            bottom <= 0.0 ||
            left >= (double)viewport_width ||
            top >= (double)viewport_height ||
            right <= left ||
            bottom <= top) {
            continue;
        }

        uint32_t x0 = edge(left, viewport_width, 0);
        uint32_t y0 = edge(top, viewport_height, 0);
        uint32_t x1 = edge(right, viewport_width, 1);
        uint32_t y1 = edge(bottom, viewport_height, 1);
        if (x1 <= x0 || y1 <= y0) continue;
        if (batch.rect_count >= capacity) return KD_DRAW_CAPACITY;

        kd_draw_rect out_rect = {0};
        out_rect.kind = kind;
        out_rect.resource_id = resource_id;
        out_rect.x = x0;
        out_rect.y = y0;
        out_rect.width = x1 - x0;
        out_rect.height = y1 - y0;
        out_rect.color = cmd->color;

        if (kind == KD_DRAW_IMAGE ||
            kind == KD_DRAW_GLYPH) {
            const double full_width = right - left;
            const double full_height = bottom - top;
            const float u0 = cmd->u0;
            const float v0 = cmd->v0;
            const float u1 = cmd->u1;
            const float v1 = cmd->v1;

            const double left_fraction =
                ((double)x0 - left) / full_width;
            const double right_fraction =
                ((double)x1 - left) / full_width;
            const double top_fraction =
                ((double)y0 - top) / full_height;
            const double bottom_fraction =
                ((double)y1 - top) / full_height;

            out_rect.u0 =
                u0 + (u1 - u0) * (float)left_fraction;
            out_rect.u1 =
                u0 + (u1 - u0) * (float)right_fraction;
            out_rect.v0 =
                v0 + (v1 - v0) * (float)top_fraction;
            out_rect.v1 =
                v0 + (v1 - v0) * (float)bottom_fraction;
        }

        rects[batch.rect_count++] = out_rect;
    }

    *out = batch;
    return KD_DRAW_OK;
}
