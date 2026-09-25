#include "kotlin_display.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    kd_draw_command commands[2] = {0};
    commands[0].kind = KD_DRAW_CLEAR;
    commands[0].color = (kd_draw_color){0.08f, 0.11f, 0.15f, 1.0f};
    commands[1].kind = KD_DRAW_RECT;
    commands[1].x = 10.f;
    commands[1].y = 10.f;
    commands[1].width = 20.f;
    commands[1].height = 20.f;
    commands[1].color = (kd_draw_color){0.18f, 0.49f, 0.66f, 1.0f};
    assert(kd_gdi_frame_present_commands(NULL, commands, 2) == KD_INVALID_ARGUMENT);
#if defined(_WIN32)
    kd_window *window = NULL;
    kd_status created = kd_window_create(240, 160, "GDI frame smoke", &window);
    if (created == KD_WINDOW_UNAVAILABLE || created == KD_UNSUPPORTED_PLATFORM) {
        puts("No interactive Windows desktop; GDI argument checks passed.");
        return 0;
    }
    assert(created == KD_OK && window != NULL);
    assert(kd_gdi_frame_present_commands(window, commands, 2) == KD_OK);
    commands[1].color.alpha = 0.5f;
    assert(kd_gdi_frame_present_commands(window, commands, 2) == KD_OK);
    commands[1].color.alpha = 1.f;
    commands[1].kind = KD_DRAW_CLEAR;
    assert(kd_gdi_frame_present_commands(window, commands, 2) == KD_INVALID_ARGUMENT);
    kd_window_destroy(window);
#endif
    puts("GDI draw command smoke passed.");
    return 0;
}
