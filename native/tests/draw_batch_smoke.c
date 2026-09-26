#include "kd_draw_batch.h"
#include <math.h>
#include <stdio.h>
#include <string.h>

#define CHECK(expr) do { if (!(expr)) { fprintf(stderr, "FAILED line %d: %s\n", __LINE__, #expr); return 1; } } while (0)

int main(void) {
    const kd_draw_color blue = {0.1f, 0.2f, 0.8f, 1.0f};
    kd_draw_command commands[] = {
        {KD_DRAW_CLEAR, 0, 0, 0, 0, {0, 0, 0, 1}},
        {KD_DRAW_RECT, -3.5f, 2.25f, 12.75f, 8.5f, {0.1f, 0.2f, 0.8f, 1}},
        {KD_DRAW_RECT, 99.0f, 69.0f, 100.0f, 100.0f, {0.1f, 0.2f, 0.8f, 1}},
        {KD_DRAW_RECT, 150.0f, 0.0f, 2.0f, 2.0f, {0.1f, 0.2f, 0.8f, 1}}
    };
    kd_draw_rect rects[3];
    kd_draw_batch batch;
    CHECK(kd_prepare_draw_batch(100, 70, commands, 4, rects, 3, &batch) == KD_DRAW_OK);
    CHECK(batch.rect_count == 2 && batch.clear.alpha == 1.0f);
    CHECK(rects[0].x == 0 && rects[0].y == 2 && rects[0].width == 10 && rects[0].height == 9);
    CHECK(rects[1].x == 99 && rects[1].y == 69 && rects[1].width == 1 && rects[1].height == 1);
    CHECK(rects[0].color.blue == blue.blue);
    CHECK(kd_prepare_draw_batch(100, 70, commands, 4, rects, 1, &batch) == KD_DRAW_CAPACITY);
    CHECK(batch.rect_count == 0);
    CHECK(kd_prepare_draw_batch(100, 70, commands, 1, NULL, 0, &batch) == KD_DRAW_OK);
    CHECK(batch.rect_count == 0);

    kd_draw_command clear_rect_commands[] = {
        {KD_DRAW_CLEAR, 0, 0, 0, 0, {0, 0, 0, 1}},
        {KD_DRAW_CLEAR_RECT, 4.5f, 5.5f, 10.0f, 8.0f, {0, 0, 0, 0}}
    };
    kd_draw_rect clear_rects[1];
    CHECK(kd_prepare_draw_batch(
        100, 70, clear_rect_commands, 2, clear_rects, 1, &batch
    ) == KD_DRAW_OK);
    CHECK(batch.rect_count == 1);
    CHECK(clear_rects[0].kind == KD_DRAW_CLEAR_RECT);
    CHECK(clear_rects[0].x == 4 && clear_rects[0].y == 5);
    CHECK(clear_rects[0].width == 11 && clear_rects[0].height == 9);

    kd_draw_command retained_commands[] = {
        {KD_DRAW_RETAIN, 0, 0, 0, 0, {0, 0, 0, 0}},
        {KD_DRAW_RECT, 10.0f, 12.0f, 8.0f, 6.0f, {1, 0, 0, 1}}
    };
    kd_draw_rect retained_rects[1];
    CHECK(kd_prepare_draw_batch(
        100, 70, retained_commands, 2, retained_rects, 1, &batch
    ) == KD_DRAW_OK);
    CHECK(batch.clear_enabled == 0);
    CHECK(batch.rect_count == 1);
    CHECK(retained_rects[0].x == 10 && retained_rects[0].y == 12);
    CHECK(retained_rects[0].width == 8 && retained_rects[0].height == 6);

    kd_draw_command image_commands[] = {
        {KD_DRAW_RETAIN, 0, 0, 0, 0, {0, 0, 0, 0}},
        {
            .kind = KD_DRAW_ENCODE_RESOURCE(KD_DRAW_IMAGE, 17),
            .x = -10.0f,
            .y = 10.0f,
            .width = 40.0f,
            .height = 20.0f,
            .color = {1.0f, 1.0f, 1.0f, 1.0f},
            .u0 = 0.0f,
            .v0 = 0.0f,
            .u1 = 1.0f,
            .v1 = 1.0f
        }
    };
    kd_draw_rect image_rects[1];
    CHECK(kd_prepare_draw_batch(
        100, 70, image_commands, 2, image_rects, 1, &batch
    ) == KD_DRAW_OK);
    CHECK(batch.rect_count == 1);
    CHECK(image_rects[0].kind == KD_DRAW_IMAGE);
    CHECK(image_rects[0].resource_id == 17);
    CHECK(image_rects[0].x == 0);
    CHECK(image_rects[0].width == 30);
    CHECK(image_rects[0].u0 > 0.24f && image_rects[0].u0 < 0.26f);
    CHECK(image_rects[0].u1 == 1.0f);

    kd_draw_command glyph_commands[] = {
        {KD_DRAW_RETAIN, 0, 0, 0, 0, {0, 0, 0, 0}},
        {
            .kind = KD_DRAW_ENCODE_RESOURCE(KD_DRAW_GLYPH, 23),
            .x = 12.0f,
            .y = 14.0f,
            .width = 18.0f,
            .height = 22.0f,
            .color = {0.7f, 0.3f, 0.9f, 0.8f},
            .u0 = 0.25f,
            .v0 = 0.5f,
            .u1 = 0.5f,
            .v1 = 0.75f
        }
    };
    kd_draw_rect glyph_rects[1];
    CHECK(kd_prepare_draw_batch(
        100, 70, glyph_commands, 2, glyph_rects, 1, &batch
    ) == KD_DRAW_OK);
    CHECK(glyph_rects[0].kind == KD_DRAW_GLYPH);
    CHECK(glyph_rects[0].resource_id == 23);
    CHECK(glyph_rects[0].color.alpha == 0.8f);
    CHECK(glyph_rects[0].u0 == 0.25f);
    CHECK(glyph_rects[0].v1 == 0.75f);
    CHECK(kd_prepare_draw_batch(0, 70, commands, 1, rects, 3, &batch) == KD_DRAW_INVALID);
    CHECK(kd_prepare_draw_batch(100, 70, NULL, 1, rects, 3, &batch) == KD_DRAW_INVALID);
    CHECK(kd_prepare_draw_batch(100, 70, commands, 0, rects, 3, &batch) == KD_DRAW_INVALID);
    CHECK(kd_prepare_draw_batch(100, 70, commands, 4, NULL, 3, &batch) == KD_DRAW_INVALID);
    CHECK(kd_prepare_draw_batch(100, 70, commands, 4, rects, 3, NULL) == KD_DRAW_INVALID);
    commands[1].color.alpha = 0.5f;
    CHECK(kd_prepare_draw_batch(100, 70, commands, 4, rects, 3, &batch) == KD_DRAW_OK);
    CHECK(rects[0].color.alpha == 0.5f);
    commands[1].color.alpha = 1.0f;
    commands[1].width = -1.0f;
    CHECK(kd_prepare_draw_batch(100, 70, commands, 4, rects, 3, &batch) == KD_DRAW_INVALID);
    commands[1].width = NAN;
    CHECK(kd_prepare_draw_batch(100, 70, commands, 4, rects, 3, &batch) == KD_DRAW_INVALID);
    commands[1].width = 12.75f;
    commands[1].kind = KD_DRAW_CLEAR;
    CHECK(kd_prepare_draw_batch(100, 70, commands, 4, rects, 3, &batch) == KD_DRAW_INVALID);
    commands[1].kind = KD_DRAW_RECT;
    CHECK(kd_prepare_draw_batch(100, 70, commands, KD_DRAW_MAX_COMMANDS + 1u, rects, 3, &batch) == KD_DRAW_INVALID);
    puts("native draw batch: all checks passed");
    return 0;
}
