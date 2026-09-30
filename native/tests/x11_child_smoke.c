#include "kotlin_display.h"
#include <X11/Xlib.h>
#include <stdint.h>
#include <stdio.h>

int main(void) {
    Display *display = XOpenDisplay(NULL);
    if (!display) {
        fprintf(stderr, "XOpenDisplay failed\n");
        return 1;
    }

    int screen = DefaultScreen(display);
    Window parent = XCreateSimpleWindow(
        display,
        RootWindow(display, screen),
        0,
        0,
        640,
        480,
        0,
        0,
        BlackPixel(display, screen)
    );
    if (!parent) {
        XCloseDisplay(display);
        return 2;
    }

    XMapWindow(display, parent);
    XFlush(display);

    kd_window *child = NULL;
    kd_status status = kd_window_create_child(
        (uintptr_t)parent,
        320,
        240,
        &child
    );
    if (status != KD_OK || !child) {
        XDestroyWindow(display, parent);
        XCloseDisplay(display);
        return 3;
    }

    kd_native_window_handles handles = {0};
    status = kd_window_get_native_handles(child, &handles);
    if (status != KD_OK ||
        handles.platform != KD_WINDOW_PLATFORM_XLIB ||
        handles.window == 0) {
        kd_window_destroy(child);
        XDestroyWindow(display, parent);
        XCloseDisplay(display);
        return 4;
    }

    status = kd_window_resize(child, 400, 300);
    if (status != KD_OK) {
        kd_window_destroy(child);
        XDestroyWindow(display, parent);
        XCloseDisplay(display);
        return 5;
    }

    uint32_t width = 0;
    uint32_t height = 0;
    status = kd_window_get_size(child, &width, &height);
    if (status != KD_OK || width != 400 || height != 300) {
        kd_window_destroy(child);
        XDestroyWindow(display, parent);
        XCloseDisplay(display);
        return 6;
    }

    kd_window_destroy(child);
    XDestroyWindow(display, parent);
    XCloseDisplay(display);
    return 0;
}
