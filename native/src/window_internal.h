#ifndef KD_WINDOW_INTERNAL_H
#define KD_WINDOW_INTERNAL_H

#include "kotlin_display.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#elif defined(__linux__)
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#endif

#define KD_EVENT_CAPACITY 128u

struct kd_window {
#if defined(_WIN32)
    HINSTANCE instance;
    HWND handle;
    int mouse_tracking;
#elif defined(__linux__)
    Display *display;
    Window handle;
    Atom wm_delete;
#elif defined(__APPLE__)
    void *application;
    void *window;
    void *view;
    void *delegate;
    void *host;
    int attached_layer;
#else
    int unused;
#endif
    int closed;
    kd_pointer_event events[KD_EVENT_CAPACITY];
    uint32_t head;
    uint32_t count;
    int overflow;
};

void kd_window_enqueue_pointer(
    kd_window *window,
    kd_pointer_kind kind,
    int32_t x,
    int32_t y
);

#endif
