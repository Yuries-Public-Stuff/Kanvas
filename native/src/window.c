#include "kotlin_display.h"
#include "window_internal.h"
#include <stdlib.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <imm.h>
#elif defined(__linux__)
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <X11/Xutil.h>
#include <X11/keysym.h>
#include <limits.h>

// X11 horizontal scroll buttons.
#define KD_X11_BUTTON_SCROLL_LEFT 6u
#define KD_X11_BUTTON_SCROLL_RIGHT 7u
#endif

void kd_window_enqueue_pointer(kd_window *window, kd_pointer_kind kind, int32_t x, int32_t y) {
    if (!window) return;

    if (window->count > 0) {
        const uint32_t last_index =
            (window->head + window->count - 1u) % KD_EVENT_CAPACITY;
        kd_pointer_event *last = &window->events[last_index];

        if (kind == KD_POINTER_MOVE && last->kind == KD_POINTER_MOVE) {
            last->x = x;
            last->y = y;
            return;
        }

        if (kind == KD_POINTER_SCROLL && last->kind == KD_POINTER_SCROLL) {
            int64_t accumulated = (int64_t)last->y + y;
            if (accumulated > INT32_MAX) accumulated = INT32_MAX;
            if (accumulated < INT32_MIN) accumulated = INT32_MIN;
            last->y = (int32_t)accumulated;
            return;
        }

        if (kind == KD_POINTER_SCROLL_HORIZONTAL &&
            last->kind == KD_POINTER_SCROLL_HORIZONTAL) {
            int64_t accumulated = (int64_t)last->x + x;
            if (accumulated > INT32_MAX) accumulated = INT32_MAX;
            if (accumulated < INT32_MIN) accumulated = INT32_MIN;
            last->x = (int32_t)accumulated;
            return;
        }
    }

    if (window->count == KD_EVENT_CAPACITY) {
        window->overflow = 1;
        return;
    }
    const uint32_t index = (window->head + window->count) % KD_EVENT_CAPACITY;
    window->events[index] = (kd_pointer_event){kind, x, y};
    ++window->count;
}

kd_status kd_window_next_pointer(kd_window *window, kd_pointer_event *out, int *has_event) {
    if (!window || !out || !has_event) return KD_INVALID_ARGUMENT;
    *has_event = 0;
#if !defined(_WIN32) && !defined(__linux__) && !defined(__APPLE__)
    return KD_UNSUPPORTED_PLATFORM;
#else
    if (window->overflow) {
        window->overflow = 0;
        return KD_INPUT_OVERFLOW;
    }
    if (!window->count) return KD_OK;
    *out = window->events[window->head];
    window->head = (window->head + 1u) % KD_EVENT_CAPACITY;
    --window->count;
    *has_event = 1;
    return KD_OK;
#endif
}

#if defined(_WIN32)
static void kd_enable_windows_dpi_awareness(void) {
    static int initialized;
    if (initialized) return;
    initialized = 1;

    HMODULE user32 = GetModuleHandleA("user32.dll");
    if (user32) {
        typedef BOOL (WINAPI *set_context_fn)(HANDLE);
        set_context_fn set_context = (set_context_fn)GetProcAddress(
            user32,
            "SetProcessDpiAwarenessContext"
        );
        if (set_context) {
            HANDLE per_monitor_v2 = (HANDLE)(intptr_t)-4;
            if (set_context(per_monitor_v2)) return;
        }
    }

    SetProcessDPIAware();
}

static int32_t kd_windows_modifiers(void) {
    int32_t modifiers = 0;
    if (GetKeyState(VK_SHIFT) & 0x8000) modifiers |= 1;
    if (GetKeyState(VK_CONTROL) & 0x8000) modifiers |= 2;
    if (GetKeyState(VK_MENU) & 0x8000) modifiers |= 4;
    if (GetKeyState(VK_LWIN) & 0x8000 || GetKeyState(VK_RWIN) & 0x8000) modifiers |= 8;
    return modifiers;
}

static LRESULT CALLBACK kd_wndproc(HWND handle, UINT message, WPARAM wparam, LPARAM lparam) {
    kd_window *window = (kd_window *)GetWindowLongPtrA(handle, GWLP_USERDATA);
    if (message == WM_NCCREATE) {
        const CREATESTRUCTA *create = (const CREATESTRUCTA *)lparam;
        SetWindowLongPtrA(handle, GWLP_USERDATA, (LONG_PTR)create->lpCreateParams);
        return TRUE;
    }
    if (window && (message == WM_KEYDOWN || message == WM_SYSKEYDOWN)) {
        kd_window_enqueue_pointer(
            window,
            KD_KEY_DOWN,
            (int32_t)wparam,
            kd_windows_modifiers()
        );
        return 0;
    }
    if (window && (message == WM_KEYUP || message == WM_SYSKEYUP)) {
        kd_window_enqueue_pointer(
            window,
            KD_KEY_UP,
            (int32_t)wparam,
            kd_windows_modifiers()
        );
        return 0;
    }
    if (window && message == WM_CHAR) {
        kd_window_enqueue_pointer(
            window,
            KD_TEXT_INPUT,
            (int32_t)wparam,
            kd_windows_modifiers()
        );
        return 0;
    }
    if (window && message == WM_UNICHAR) {
        if (wparam == UNICODE_NOCHAR) return TRUE;
        kd_window_enqueue_pointer(
            window,
            KD_TEXT_INPUT,
            (int32_t)wparam,
            kd_windows_modifiers()
        );
        return 0;
    }
    if (window && message == WM_IME_COMPOSITION &&
        (lparam & GCS_RESULTSTR)) {
        HIMC context = ImmGetContext(handle);
        if (context) {
            LONG bytes = ImmGetCompositionStringW(
                context,
                GCS_RESULTSTR,
                NULL,
                0
            );
            if (bytes > 0) {
                size_t units = (size_t)bytes / sizeof(WCHAR);
                WCHAR *text = (WCHAR *)calloc(
                    units + 1u,
                    sizeof(WCHAR)
                );
                if (text) {
                    LONG copied = ImmGetCompositionStringW(
                        context,
                        GCS_RESULTSTR,
                        text,
                        (DWORD)bytes
                    );
                    if (copied > 0) {
                        size_t count = (size_t)copied / sizeof(WCHAR);
                        for (size_t i = 0; i < count; ++i) {
                            kd_window_enqueue_pointer(
                                window,
                                KD_TEXT_INPUT,
                                (int32_t)text[i],
                                kd_windows_modifiers()
                            );
                        }
                    }
                    free(text);
                }
            }
            ImmReleaseContext(handle, context);
        }
        return 0;
    }

    if (window && message == WM_SETFOCUS) {
        kd_window_enqueue_pointer(window, KD_WINDOW_FOCUS_GAINED, 0, 0);
        return 0;
    }
    if (window && message == WM_KILLFOCUS) {
        kd_window_enqueue_pointer(window, KD_WINDOW_FOCUS_LOST, 0, 0);
        return 0;
    }
    if (window && message == WM_MOUSEHWHEEL) {
        const int delta = GET_WHEEL_DELTA_WPARAM(wparam);
        kd_window_enqueue_pointer(
            window,
            KD_POINTER_SCROLL_HORIZONTAL,
            -delta,
            0
        );
        return 0;
    }
    if (window && message == WM_MOUSEMOVE) {
        int32_t x = (int32_t)(short)LOWORD(lparam);
        int32_t y = (int32_t)(short)HIWORD(lparam);
        if (!window->mouse_tracking) {
            TRACKMOUSEEVENT tracking = {0};
            tracking.cbSize = sizeof(tracking);
            tracking.dwFlags = TME_LEAVE;
            tracking.hwndTrack = handle;
            if (TrackMouseEvent(&tracking)) {
                window->mouse_tracking = 1;
            }
            kd_window_enqueue_pointer(
                window,
                KD_POINTER_ENTER,
                x,
                y
            );
        }
        kd_window_enqueue_pointer(
            window,
            KD_POINTER_MOVE,
            x,
            y
        );
        return 0;
    }
    if (window && message == WM_MOUSELEAVE) {
        window->mouse_tracking = 0;
        kd_window_enqueue_pointer(window, KD_POINTER_EXIT, 0, 0);
        return 0;
    }
    if (window && message == WM_MOUSEWHEEL) {
        const int delta = GET_WHEEL_DELTA_WPARAM(wparam);
        kd_window_enqueue_pointer(window, KD_POINTER_SCROLL, 0, -delta);
        return 0;
    }
    if (window && (message == WM_RBUTTONDOWN || message == WM_RBUTTONUP)) {
        kd_window_enqueue_pointer(
            window,
            message == WM_RBUTTONDOWN
                ? KD_POINTER_RIGHT_DOWN
                : KD_POINTER_RIGHT_UP,
            (int32_t)(short)LOWORD(lparam),
            (int32_t)(short)HIWORD(lparam)
        );
        if (message == WM_RBUTTONDOWN) SetCapture(handle);
        else if (GetCapture() == handle) ReleaseCapture();
        return 0;
    }
    if (window && (message == WM_MBUTTONDOWN || message == WM_MBUTTONUP)) {
        kd_window_enqueue_pointer(
            window,
            message == WM_MBUTTONDOWN
                ? KD_POINTER_MIDDLE_DOWN
                : KD_POINTER_MIDDLE_UP,
            (int32_t)(short)LOWORD(lparam),
            (int32_t)(short)HIWORD(lparam)
        );
        if (message == WM_MBUTTONDOWN) SetCapture(handle);
        else if (GetCapture() == handle) ReleaseCapture();
        return 0;
    }
    if (window && (message == WM_LBUTTONDOWN || message == WM_LBUTTONUP)) {
        kd_window_enqueue_pointer(window, message == WM_LBUTTONDOWN ? KD_POINTER_DOWN : KD_POINTER_UP,
                   (int32_t)(short)LOWORD(lparam), (int32_t)(short)HIWORD(lparam));
        if (message == WM_LBUTTONDOWN) SetCapture(handle);
        else if (GetCapture() == handle) ReleaseCapture();
        return 0;
    }
    if (window && message == WM_DPICHANGED) {
        const RECT *suggested = (const RECT *)lparam;
        if (suggested) {
            SetWindowPos(
                handle,
                NULL,
                suggested->left,
                suggested->top,
                suggested->right - suggested->left,
                suggested->bottom - suggested->top,
                SWP_NOZORDER | SWP_NOACTIVATE
            );
        }
        return 0;
    }
    if (message == WM_CLOSE) {
        DestroyWindow(handle);
        return 0;
    }
    if (message == WM_NCDESTROY && window != NULL) {
        window->closed = 1;
        window->handle = NULL;
        SetWindowLongPtrA(handle, GWLP_USERDATA, 0);
    }
    return DefWindowProcA(handle, message, wparam, lparam);
}

kd_status kd_window_create(uint32_t width, uint32_t height, const char *title, kd_window **out) {
    if (out == NULL || title == NULL || width == 0 || height == 0 || width > INT32_MAX || height > INT32_MAX)
        return KD_INVALID_ARGUMENT;
    *out = NULL;
    kd_enable_windows_dpi_awareness();
    kd_window *window = (kd_window *)calloc(1, sizeof(*window));
    if (window == NULL) return KD_OUT_OF_MEMORY;
    window->instance = GetModuleHandleA(NULL);
    const char *class_name = "KanvasNativeWindow";
    WNDCLASSA cls = {0};
    cls.lpfnWndProc = kd_wndproc;
    cls.hInstance = window->instance;
    cls.lpszClassName = class_name;
    cls.hCursor = LoadCursor(NULL, IDC_ARROW);
    if (!RegisterClassA(&cls) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        free(window);
        return KD_WINDOW_UNAVAILABLE;
    }
    DWORD style = WS_OVERLAPPEDWINDOW;
    RECT desired = {0, 0, (LONG)width, (LONG)height};
    if (!AdjustWindowRectEx(&desired, style, FALSE, 0)) {
        free(window);
        return KD_WINDOW_UNAVAILABLE;
    }

    window->handle = CreateWindowExA(
        0,
        class_name,
        title,
        style,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        desired.right - desired.left,
        desired.bottom - desired.top,
        NULL,
        NULL,
        window->instance,
        window
    );
    if (window->handle == NULL) {
        free(window);
        return KD_WINDOW_UNAVAILABLE;
    }
    ShowWindow(window->handle, SW_SHOW);
    UpdateWindow(window->handle);
    SetForegroundWindow(window->handle);
    SetFocus(window->handle);
    *out = window;
    return KD_OK;
}

kd_status kd_window_create_child(
    uintptr_t parent,
    uint32_t width,
    uint32_t height,
    kd_window **out
) {
    if (!out || !parent || width == 0 || height == 0 ||
        width > INT32_MAX || height > INT32_MAX) {
        return KD_INVALID_ARGUMENT;
    }
    *out = NULL;
    kd_enable_windows_dpi_awareness();

    kd_window *window = (kd_window *)calloc(1, sizeof(*window));
    if (!window) return KD_OUT_OF_MEMORY;
    window->instance = GetModuleHandleA(NULL);

    HWND parent_handle = (HWND)parent;
    LONG_PTR parent_style = GetWindowLongPtrA(parent_handle, GWL_STYLE);
    if ((parent_style & WS_CLIPCHILDREN) == 0) {
        SetWindowLongPtrA(
            parent_handle,
            GWL_STYLE,
            parent_style | WS_CLIPCHILDREN
        );
        SetWindowPos(
            parent_handle,
            NULL,
            0, 0, 0, 0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER |
            SWP_NOACTIVATE | SWP_FRAMECHANGED
        );
    }

    const char *class_name = "KanvasNativeWindow";
    WNDCLASSA cls = {0};
    cls.lpfnWndProc = kd_wndproc;
    cls.hInstance = window->instance;
    cls.lpszClassName = class_name;
    cls.hCursor = LoadCursor(NULL, IDC_ARROW);
    if (!RegisterClassA(&cls) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        free(window);
        return KD_WINDOW_UNAVAILABLE;
    }

    window->handle = CreateWindowExA(
        0,
        class_name,
        "",
        WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS | WS_CLIPCHILDREN,
        0,
        0,
        (int)width,
        (int)height,
        parent_handle,
        NULL,
        window->instance,
        window
    );
    if (!window->handle) {
        free(window);
        return KD_WINDOW_UNAVAILABLE;
    }

    ShowWindow(window->handle, SW_SHOW);
    SetWindowPos(
        window->handle,
        HWND_TOP,
        0,
        0,
        (int)width,
        (int)height,
        SWP_SHOWWINDOW | SWP_NOACTIVATE
    );
    *out = window;
    return KD_OK;
}

kd_status kd_window_resize(kd_window *window, uint32_t width, uint32_t height) {
    if (!window || !window->handle || window->closed ||
        width == 0 || height == 0 ||
        width > INT32_MAX || height > INT32_MAX) {
        return KD_INVALID_ARGUMENT;
    }
    int x = 0;
    int y = 0;
    HWND parent = GetParent(window->handle);
    if (parent) {
        RECT rect;
        if (GetWindowRect(window->handle, &rect)) {
            POINT point = {rect.left, rect.top};
            MapWindowPoints(HWND_DESKTOP, parent, &point, 1);
            x = point.x;
            y = point.y;
        }
    }

    return SetWindowPos(
        window->handle,
        HWND_TOP,
        x,
        y,
        (int)width,
        (int)height,
        SWP_SHOWWINDOW | SWP_NOACTIVATE
    ) ? KD_OK : KD_WINDOW_UNAVAILABLE;
}

kd_status kd_window_poll(kd_window *window, int *should_close) {
    if (window == NULL || should_close == NULL) return KD_INVALID_ARGUMENT;
    MSG message;
    HWND handle = window->handle;
    if (window->closed || handle == NULL) {
        *should_close = 1;
        return KD_OK;
    }

    /*
     * This window is created on the same thread that owns Compose/AWT.
     * Never use a NULL HWND here: that drains and dispatches AWT's entire
     * Win32 queue from inside the renderer and causes re-entrant paint,
     * focus and close handling. Only service Kanvas' own HWND.
     */
    while (PeekMessageA(&message, handle, 0, 0, PM_REMOVE)) {
        TranslateMessage(&message);
        DispatchMessageA(&message);
    }
    *should_close = window->closed;
    return KD_OK;
}

kd_status kd_window_get_native_handles(kd_window *window, kd_native_window_handles *out) {
    if (window == NULL || out == NULL) return KD_INVALID_ARGUMENT;
    if (window->closed || window->handle == NULL) return KD_WINDOW_UNAVAILABLE;
    out->platform = KD_WINDOW_PLATFORM_WIN32;
    out->display = (void *)window->instance;
    out->window = (uintptr_t)window->handle;
    return KD_OK;
}

kd_status kd_window_get_size(kd_window *window, uint32_t *width, uint32_t *height) {
    if (window == NULL || width == NULL || height == NULL) return KD_INVALID_ARGUMENT;
    if (window->closed || window->handle == NULL) return KD_WINDOW_UNAVAILABLE;
    RECT rect;
    if (!GetClientRect(window->handle, &rect)) return KD_WINDOW_UNAVAILABLE;
    *width = (uint32_t)(rect.right - rect.left);
    *height = (uint32_t)(rect.bottom - rect.top);
    return KD_OK;
}

void kd_window_destroy(kd_window *window) {
    if (window == NULL) return;
    if (window->handle != NULL) DestroyWindow(window->handle);
    free(window);
}

#elif defined(__linux__)
kd_status kd_window_create(uint32_t width, uint32_t height, const char *title, kd_window **out) {
    if (out == NULL || title == NULL || width == 0 || height == 0 || width > INT_MAX || height > INT_MAX)
        return KD_INVALID_ARGUMENT;
    *out = NULL;
    kd_window *window = (kd_window *)calloc(1, sizeof(*window));
    if (window == NULL) return KD_OUT_OF_MEMORY;
    window->display = XOpenDisplay(NULL);
    if (window->display == NULL) {
        free(window);
        return KD_WINDOW_UNAVAILABLE;
    }
    int screen = DefaultScreen(window->display);
    window->handle = XCreateSimpleWindow(window->display, RootWindow(window->display, screen),
                                          0, 0, width, height, 0, 0, BlackPixel(window->display, screen));
    if (window->handle == 0) {
        XCloseDisplay(window->display);
        free(window);
        return KD_WINDOW_UNAVAILABLE;
    }
    XStoreName(window->display, window->handle, title);
    XSelectInput(window->display, window->handle,
                 StructureNotifyMask | ExposureMask | ButtonPressMask | ButtonReleaseMask |
                 PointerMotionMask | KeyPressMask | KeyReleaseMask |
                 FocusChangeMask | EnterWindowMask | LeaveWindowMask);
    window->wm_delete = XInternAtom(window->display, "WM_DELETE_WINDOW", False);
    XSetWMProtocols(window->display, window->handle, &window->wm_delete, 1);
    XMapWindow(window->display, window->handle);
    XFlush(window->display);
    *out = window;
    return KD_OK;
}

kd_status kd_window_create_child(
    uintptr_t parent,
    uint32_t width,
    uint32_t height,
    kd_window **out
) {
    if (!out || !parent || width == 0 || height == 0 ||
        width > INT_MAX || height > INT_MAX) {
        return KD_INVALID_ARGUMENT;
    }
    *out = NULL;

    kd_window *window = (kd_window *)calloc(1, sizeof(*window));
    if (!window) return KD_OUT_OF_MEMORY;

    window->display = XOpenDisplay(NULL);
    if (!window->display) {
        free(window);
        return KD_WINDOW_UNAVAILABLE;
    }

    int screen = DefaultScreen(window->display);
    window->handle = XCreateSimpleWindow(
        window->display,
        (Window)parent,
        0,
        0,
        width,
        height,
        0,
        0,
        BlackPixel(window->display, screen)
    );
    if (!window->handle) {
        XCloseDisplay(window->display);
        free(window);
        return KD_WINDOW_UNAVAILABLE;
    }

    XSelectInput(
        window->display,
        window->handle,
        StructureNotifyMask |
        ExposureMask |
        ButtonPressMask |
        ButtonReleaseMask |
        PointerMotionMask |
        KeyPressMask |
        KeyReleaseMask |
        FocusChangeMask |
        EnterWindowMask |
        LeaveWindowMask
    );

    XMapRaised(window->display, window->handle);
    XFlush(window->display);

    *out = window;
    return KD_OK;
}

kd_status kd_window_resize(kd_window *window, uint32_t width, uint32_t height) {
    if (!window || width == 0 || height == 0) return KD_INVALID_ARGUMENT;
    if (window->closed || window->handle == 0) return KD_WINDOW_UNAVAILABLE;
    XResizeWindow(window->display, window->handle, width, height);
    XFlush(window->display);
    return KD_OK;
}

kd_status kd_window_poll(kd_window *window, int *should_close) {
    if (window == NULL || should_close == NULL) return KD_INVALID_ARGUMENT;
    while (!window->closed && XPending(window->display) > 0) {
        XEvent event;
        XNextEvent(window->display, &event);
        if (event.type == ClientMessage && (Atom)event.xclient.data.l[0] == window->wm_delete)
            window->closed = 1;
        else if (event.type == DestroyNotify) {
            window->closed = 1;
            window->handle = 0;
        } else if (event.type == FocusIn) {
            kd_window_enqueue_pointer(window, KD_WINDOW_FOCUS_GAINED, 0, 0);
        } else if (event.type == FocusOut) {
            kd_window_enqueue_pointer(window, KD_WINDOW_FOCUS_LOST, 0, 0);
        } else if (event.type == KeyPress || event.type == KeyRelease) {
            char text[16] = {0};
            KeySym symbol = NoSymbol;
            int length = XLookupString(
                &event.xkey,
                text,
                (int)sizeof(text),
                &symbol,
                NULL
            );

            int32_t key = 0;
            if (symbol >= XK_A && symbol <= XK_Z) key = (int32_t)symbol;
            else if (symbol >= XK_a && symbol <= XK_z) {
                key = (int32_t)(symbol - XK_a + 'A');
            } else if (symbol >= XK_0 && symbol <= XK_9) key = (int32_t)symbol;
            else {
                switch (symbol) {
                    case XK_Return: key = 10; break;
                    case XK_Escape: key = 27; break;
                    case XK_BackSpace: key = 8; break;
                    case XK_Tab: key = 9; break;
                    case XK_Left: key = 37; break;
                    case XK_Up: key = 38; break;
                    case XK_Right: key = 39; break;
                    case XK_Down: key = 40; break;
                    case XK_Delete: key = 127; break;
                    case XK_Home: key = 36; break;
                    case XK_End: key = 35; break;
                    case XK_Page_Up: key = 33; break;
                    case XK_Page_Down: key = 34; break;
                    default: key = 0; break;
                }
            }

            int32_t modifiers = 0;
            if (event.xkey.state & ShiftMask) modifiers |= 1;
            if (event.xkey.state & ControlMask) modifiers |= 2;
            if (event.xkey.state & Mod1Mask) modifiers |= 4;
            if (event.xkey.state & Mod4Mask) modifiers |= 8;

            kd_window_enqueue_pointer(
                window,
                event.type == KeyPress ? KD_KEY_DOWN : KD_KEY_UP,
                key,
                modifiers
            );

            if (event.type == KeyPress && length > 0) {
                for (int i = 0; i < length; ++i) {
                    unsigned char value = (unsigned char)text[i];
                    if (value >= 0x20 || value == '\n' || value == '\t') {
                        kd_window_enqueue_pointer(
                            window,
                            KD_TEXT_INPUT,
                            (int32_t)value,
                            modifiers
                        );
                    }
                }
            }
        } else if (event.type == EnterNotify) {
            kd_window_enqueue_pointer(
                window,
                KD_POINTER_ENTER,
                event.xcrossing.x,
                event.xcrossing.y
            );
        } else if (event.type == LeaveNotify) {
            kd_window_enqueue_pointer(
                window,
                KD_POINTER_EXIT,
                event.xcrossing.x,
                event.xcrossing.y
            );
        } else if (event.type == MotionNotify) {
            kd_window_enqueue_pointer(window, KD_POINTER_MOVE, event.xmotion.x, event.xmotion.y);
        } else if (event.type == ButtonPress &&
                   (event.xbutton.button == Button4 ||
                    event.xbutton.button == Button5 ||
                    event.xbutton.button == KD_X11_BUTTON_SCROLL_LEFT ||
                    event.xbutton.button == KD_X11_BUTTON_SCROLL_RIGHT)) {
            if (event.xbutton.button == KD_X11_BUTTON_SCROLL_LEFT || event.xbutton.button == KD_X11_BUTTON_SCROLL_RIGHT) {
                kd_window_enqueue_pointer(
                    window,
                    KD_POINTER_SCROLL_HORIZONTAL,
                    event.xbutton.button == KD_X11_BUTTON_SCROLL_LEFT ? -120 : 120,
                    0
                );
            } else {
                kd_window_enqueue_pointer(
                    window,
                    KD_POINTER_SCROLL,
                    0,
                    event.xbutton.button == Button4 ? -120 : 120
                );
            }
        } else if ((event.type == ButtonPress || event.type == ButtonRelease) &&
                   (event.xbutton.button == Button1 ||
                    event.xbutton.button == Button2 ||
                    event.xbutton.button == Button3)) {
            kd_pointer_kind kind = KD_POINTER_DOWN;
            if (event.xbutton.button == Button1) {
                kind = event.type == ButtonPress
                    ? KD_POINTER_DOWN
                    : KD_POINTER_UP;
            } else if (event.xbutton.button == Button2) {
                kind = event.type == ButtonPress
                    ? KD_POINTER_MIDDLE_DOWN
                    : KD_POINTER_MIDDLE_UP;
            } else {
                kind = event.type == ButtonPress
                    ? KD_POINTER_RIGHT_DOWN
                    : KD_POINTER_RIGHT_UP;
            }
            kd_window_enqueue_pointer(
                window,
                kind,
                event.xbutton.x,
                event.xbutton.y
            );
        }
    }
    *should_close = window->closed;
    return KD_OK;
}

kd_status kd_window_get_native_handles(kd_window *window, kd_native_window_handles *out) {
    if (window == NULL || out == NULL) return KD_INVALID_ARGUMENT;
    if (window->closed || window->handle == 0) return KD_WINDOW_UNAVAILABLE;
    out->platform = KD_WINDOW_PLATFORM_XLIB;
    out->display = (void *)window->display;
    out->window = (uintptr_t)window->handle;
    return KD_OK;
}

kd_status kd_window_get_size(kd_window *window, uint32_t *width, uint32_t *height) {
    if (window == NULL || width == NULL || height == NULL) return KD_INVALID_ARGUMENT;
    if (window->closed || window->handle == 0) return KD_WINDOW_UNAVAILABLE;
    XWindowAttributes attributes;
    if (!XGetWindowAttributes(window->display, window->handle, &attributes)) return KD_WINDOW_UNAVAILABLE;
    *width = (uint32_t)attributes.width;
    *height = (uint32_t)attributes.height;
    return KD_OK;
}

void kd_window_destroy(kd_window *window) {
    if (window == NULL) return;
    if (window->handle != 0) XDestroyWindow(window->display, window->handle);
    XCloseDisplay(window->display);
    free(window);
}

#elif !defined(__APPLE__)
kd_status kd_window_create(uint32_t width, uint32_t height, const char *title, kd_window **out) {
    (void)width; (void)height; (void)title;
    if (out == NULL) return KD_INVALID_ARGUMENT;
    *out = NULL;
    return KD_UNSUPPORTED_PLATFORM;
}
kd_status kd_window_create_child(
    uintptr_t parent,
    uint32_t width,
    uint32_t height,
    kd_window **out
) {
    (void)parent; (void)width; (void)height;
    if (out) *out = NULL;
    return KD_UNSUPPORTED_PLATFORM;
}
kd_status kd_window_resize(kd_window *window, uint32_t width, uint32_t height) {
    (void)window; (void)width; (void)height;
    return KD_UNSUPPORTED_PLATFORM;
}
kd_status kd_window_poll(kd_window *window, int *should_close) {
    (void)window; (void)should_close;
    return KD_UNSUPPORTED_PLATFORM;
}
kd_status kd_window_get_native_handles(kd_window *window, kd_native_window_handles *out) {
    (void)window; (void)out;
    return KD_UNSUPPORTED_PLATFORM;
}
kd_status kd_window_get_size(kd_window *window, uint32_t *width, uint32_t *height) {
    (void)window; (void)width; (void)height;
    return KD_UNSUPPORTED_PLATFORM;
}
void kd_window_destroy(kd_window *window) { (void)window; }
#endif
