#include "kotlin_display.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    kd_window *window = NULL;
    int closed = 0;
    uint32_t width = 0, height = 0;
    assert(kd_window_create(0, 200, "invalid", &window) == KD_INVALID_ARGUMENT);
    assert(window == NULL);
    assert(kd_window_poll(NULL, &closed) == KD_INVALID_ARGUMENT);
    assert(kd_window_get_size(NULL, &width, &height) == KD_INVALID_ARGUMENT);
    kd_window_destroy(NULL);

    kd_status status = kd_window_create(320, 240, "Kotlin Display Smoke Test", &window);
    if (status == KD_WINDOW_UNAVAILABLE || status == KD_UNSUPPORTED_PLATFORM) {
        puts("No desktop display or window platform; argument checks passed.");
        return 0;
    }
    assert(status == KD_OK && window != NULL);
    kd_native_window_handles handles = {0};
    assert(kd_window_get_native_handles(window, &handles) == KD_OK);
    assert(handles.platform != KD_WINDOW_PLATFORM_UNKNOWN && handles.window != 0);
    assert(kd_window_get_size(window, &width, &height) == KD_OK);
    assert(width > 0 && height > 0);
    assert(kd_window_poll(window, &closed) == KD_OK);
    kd_window_destroy(window);
    puts("Native window create/poll/handles/destroy passed.");
    return 0;
}
