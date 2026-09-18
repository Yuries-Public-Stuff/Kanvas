#include "kotlin_display.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

int main(void) {
    kd_window *window = NULL;
    kd_status status = kd_window_create(320, 240, "Quit Smoke", &window);
    if (status != KD_OK || !window) return 1;

    int closed = 0;
    PostQuitMessage(0);
    status = kd_window_poll(window, &closed);
    kd_window_destroy(window);
    return status == KD_OK && closed ? 0 : 2;
}
#else
int main(void) { return 0; }
#endif
