#include "kotlin_display.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    kd_draw_command commands[2] = {0};
    commands[0].kind = KD_DRAW_CLEAR;
    commands[0].color = (kd_draw_color){0.08f, 0.11f, 0.15f, 1.f};
    commands[1].kind = KD_DRAW_RECT;
    commands[1].x = 10.f;
    commands[1].y = 10.f;
    commands[1].width = 30.f;
    commands[1].height = 20.f;
    commands[1].color = (kd_draw_color){0.18f, 0.49f, 0.66f, 1.f};
    assert(kd_abi_version() == KD_ABI_VERSION);
    assert(kd_gl_frame_create(NULL, NULL) == KD_INVALID_ARGUMENT);
    assert(kd_d3d9_frame_create(NULL, NULL) == KD_INVALID_ARGUMENT);
    assert(kd_gl_frame_present_commands(NULL, commands, 2) == KD_INVALID_ARGUMENT);
    assert(kd_d3d9_frame_present_commands(NULL, commands, 2) == KD_INVALID_ARGUMENT);
    kd_gl_frame_destroy(NULL);
    kd_d3d9_frame_destroy(NULL);
#if defined(_WIN32)
    kd_window *window = NULL;
    kd_status status = kd_window_create(320, 240, "Backend smoke", &window);
    if (status == KD_OK) {
        kd_gl_frame *gl = NULL;
        status = kd_gl_frame_create(window, &gl);
        if (status == KD_OK) {
            assert(gl != NULL);
            assert(kd_gl_frame_present_commands(gl, commands, 2) == KD_OK);
            commands[1].color.alpha = 0.5f;
            assert(kd_gl_frame_present_commands(gl, commands, 2) == KD_OK);
            commands[1].color.alpha = 1.f;

            const uint32_t texture_pixels[4] = {
                0xffff0000u, 0xff00ff00u,
                0xff0000ffu, 0xffffffffu
            };
            assert(kd_gl_frame_upload_texture(
                gl, 1, 2, 2, texture_pixels
            ) == KD_OK);
            kd_draw_command textured[2] = {0};
            textured[0].kind = KD_DRAW_CLEAR;
            textured[0].color = (kd_draw_color){0, 0, 0, 1};
            textured[1].kind =
                KD_DRAW_ENCODE_RESOURCE(KD_DRAW_IMAGE, 1);
            textured[1].x = 20.f;
            textured[1].y = 20.f;
            textured[1].width = 64.f;
            textured[1].height = 64.f;
            textured[1].color =
                (kd_draw_color){1, 1, 1, 1};
            textured[1].u0 = 0.f;
            textured[1].v0 = 0.f;
            textured[1].u1 = 1.f;
            textured[1].v1 = 1.f;
            assert(kd_gl_frame_present_commands(
                gl, textured, 2
            ) == KD_OK);

            textured[1].kind =
                KD_DRAW_ENCODE_RESOURCE(KD_DRAW_GLYPH, 1);
            textured[1].color =
                (kd_draw_color){0.8f, 0.4f, 1.f, 0.75f};
            assert(kd_gl_frame_present_commands(
                gl, textured, 2
            ) == KD_OK);
            kd_gl_frame_release_texture(gl, 1);
            kd_gl_frame_destroy(gl);
        } else {
            assert(gl == NULL && status == KD_GRAPHICS_ERROR);
        }
        kd_window_destroy(window);
    }
    window = NULL;
    status = kd_window_create(320, 240, "D3D9 smoke", &window);
    if (status == KD_OK) {
        kd_d3d9_frame *d3d = NULL;
        status = kd_d3d9_frame_create(window, &d3d);
        if (status == KD_OK) {
            assert(d3d != NULL);
            assert(kd_d3d9_frame_present_commands(d3d, commands, 2) == KD_OK);
            commands[1].color.alpha = 0.5f;
            assert(kd_d3d9_frame_present_commands(d3d, commands, 2) == KD_OK);
            commands[1].color.alpha = 1.f;

            const uint32_t texture_pixels[4] = {
                0xffff0000u, 0xff00ff00u,
                0xff0000ffu, 0xffffffffu
            };
            assert(kd_d3d9_frame_upload_texture(
                d3d, 1, 2, 2, texture_pixels
            ) == KD_OK);
            kd_draw_command textured[2] = {0};
            textured[0].kind = KD_DRAW_CLEAR;
            textured[0].color = (kd_draw_color){0, 0, 0, 1};
            textured[1].kind =
                KD_DRAW_ENCODE_RESOURCE(KD_DRAW_IMAGE, 1);
            textured[1].x = 20.f;
            textured[1].y = 20.f;
            textured[1].width = 64.f;
            textured[1].height = 64.f;
            textured[1].color =
                (kd_draw_color){0, 0, 1, 1};
            assert(kd_d3d9_frame_present_commands(
                d3d, textured, 2
            ) == KD_OK);

            textured[1].kind =
                KD_DRAW_ENCODE_RESOURCE(KD_DRAW_GLYPH, 1);
            textured[1].color =
                (kd_draw_color){0.8f, 0.4f, 1.f, 0.75f};
            assert(kd_d3d9_frame_present_commands(
                d3d, textured, 2
            ) == KD_OK);
            kd_d3d9_frame_release_texture(d3d, 1);
            kd_d3d9_frame_destroy(d3d);
        } else {
            assert(d3d == NULL && status == KD_GRAPHICS_ERROR);
        }
        kd_window_destroy(window);
    }
#endif
    puts("Graphics backends ABI and available device smoke passed.");
    return 0;
}
