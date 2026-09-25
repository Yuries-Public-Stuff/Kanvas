#include "kotlin_display.h"
#include <stdio.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#elif defined(__linux__)
#include <X11/Xlib.h>
#endif

int main(void) {
    kd_pointer_event event = {0};
    int pending = -1;
    if (kd_window_next_pointer(NULL, &event, &pending) != KD_INVALID_ARGUMENT) return 1;
    kd_window *window = NULL;
    kd_status status = kd_window_create(320, 240, "Kotlin Pointer Smoke", &window);
    if (status == KD_WINDOW_UNAVAILABLE || status == KD_UNSUPPORTED_PLATFORM) {
        puts("SKIP: no display; pointer argument checks passed");
        return 0;
    }
    if (status != KD_OK || window == NULL) return 2;
    /*
     * Window creation can legitimately queue focus/hover events on desktop
     * platforms. Drain those before injecting the deterministic click.
     */
    for (;;) {
        kd_status drained = kd_window_next_pointer(window, &event, &pending);
        if (drained == KD_INPUT_OVERFLOW) continue;
        if (drained != KD_OK) return 3;
        if (!pending) break;
    }

    kd_native_window_handles handles = {0};
    if (kd_window_get_native_handles(window, &handles) != KD_OK) return 4;
#if defined(_WIN32)
    SendMessageA((HWND)handles.window, WM_LBUTTONDOWN, 0, MAKELPARAM(12, 34));
    SendMessageA((HWND)handles.window, WM_LBUTTONUP, 0, MAKELPARAM(12, 34));
#elif defined(__linux__)
    Display *display = (Display *)handles.display;
    XEvent native = {0};
    native.xbutton.display = display;
    native.xbutton.window = (Window)handles.window;
    native.xbutton.button = Button1;
    native.xbutton.x = 12;
    native.xbutton.y = 34;
    native.xbutton.type = ButtonPress;
    if (!XSendEvent(display, (Window)handles.window, False, ButtonPressMask, &native)) return 5;
    native.xbutton.type = ButtonRelease;
    if (!XSendEvent(display, (Window)handles.window, False, ButtonReleaseMask, &native)) return 6;
    XSync(display, False);
    int closed = 0;
    if (kd_window_poll(window, &closed) != KD_OK || closed) return 7;
#elif defined(__APPLE__)
    /* AppKit synthetic mouse injection is covered by the Objective-C window backend.
       This C smoke keeps argument/creation/empty-queue coverage deterministic. */
    kd_window_destroy(window);
    puts("pointer queue macOS creation/empty queue: PASS");
    return 0;
#endif
    int saw_down = 0;
    int saw_up = 0;
    for (;;) {
        kd_status next = kd_window_next_pointer(window, &event, &pending);
        if (next == KD_INPUT_OVERFLOW) continue;
        if (next != KD_OK) return 8;
        if (!pending) break;

        if (!saw_down &&
            event.kind == KD_POINTER_DOWN &&
            event.x == 12 &&
            event.y == 34) {
            saw_down = 1;
            continue;
        }
        if (saw_down &&
            event.kind == KD_POINTER_UP &&
            event.x == 12 &&
            event.y == 34) {
            saw_up = 1;
        }
    }
    if (!saw_down) return 9;
    if (!saw_up) return 10;
    kd_window_destroy(window);
    puts("pointer queue: PASS");
    return 0;
}
