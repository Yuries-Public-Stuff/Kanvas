#include "kd_draw_batch.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>

int main(void) {
    assert(
        KD_DRAW_BASE_KIND(
            KD_DRAW_ENCODE_RESOURCE(KD_DRAW_IMAGE, 0x123456u)
        ) == KD_DRAW_IMAGE
    );
    assert(
        KD_DRAW_RESOURCE_ID(
            KD_DRAW_ENCODE_RESOURCE(KD_DRAW_IMAGE, 0x123456u)
        ) == 0x123456u
    );

    kd_draw_command commands[3] = {0};
    commands[0].kind = KD_DRAW_RETAIN;

    commands[1].kind =
        KD_DRAW_ENCODE_RESOURCE(KD_DRAW_IMAGE, 7);
    commands[1].x = -25.f;
    commands[1].y = -10.f;
    commands[1].width = 100.f;
    commands[1].height = 50.f;
    commands[1].color =
        (kd_draw_color){1.f, 1.f, 1.f, 1.f};
    commands[1].u0 = 0.f;
    commands[1].v0 = 0.f;
    commands[1].u1 = 1.f;
    commands[1].v1 = 1.f;

    commands[2].kind =
        KD_DRAW_ENCODE_RESOURCE(KD_DRAW_GLYPH, 8);
    commands[2].x = 10.f;
    commands[2].y = 10.f;
    commands[2].width = 20.f;
    commands[2].height = 30.f;
    commands[2].color =
        (kd_draw_color){0.7f, 0.2f, 0.9f, 0.5f};
    commands[2].u0 = 0.25f;
    commands[2].v0 = 0.5f;
    commands[2].u1 = 0.5f;
    commands[2].v1 = 0.75f;

    kd_draw_rect rects[2] = {0};
    kd_draw_batch batch = {0};

    assert(
        kd_prepare_draw_batch(
            80, 60,
            commands, 3,
            rects, 2,
            &batch
        ) == KD_DRAW_OK
    );

    assert(batch.clear_enabled == 0);
    assert(batch.rect_count == 2);

    assert(rects[0].kind == KD_DRAW_IMAGE);
    assert(rects[0].resource_id == 7);
    assert(rects[0].x == 0);
    assert(rects[0].y == 0);
    assert(rects[0].width == 75);
    assert(rects[0].height == 40);
    assert(fabsf(rects[0].u0 - 0.25f) < 0.001f);
    assert(fabsf(rects[0].v0 - 0.20f) < 0.001f);
    assert(fabsf(rects[0].u1 - 1.f) < 0.001f);
    assert(fabsf(rects[0].v1 - 1.f) < 0.001f);

    assert(rects[1].kind == KD_DRAW_GLYPH);
    assert(rects[1].resource_id == 8);
    assert(rects[1].color.alpha == 0.5f);
    assert(rects[1].u0 == 0.25f);
    assert(rects[1].v1 == 0.75f);

    commands[1].kind = KD_DRAW_IMAGE;
    assert(
        kd_prepare_draw_batch(
            80, 60,
            commands, 3,
            rects, 2,
            &batch
        ) == KD_DRAW_INVALID
    );

    commands[1].kind =
        KD_DRAW_ENCODE_RESOURCE(KD_DRAW_IMAGE, 7);
    commands[1].u0 = NAN;
    assert(
        kd_prepare_draw_batch(
            80, 60,
            commands, 3,
            rects, 2,
            &batch
        ) == KD_DRAW_INVALID
    );

    puts("texture protocol: all checks passed");
    return 0;
}
