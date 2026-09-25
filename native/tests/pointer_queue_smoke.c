#include "../src/window.c"
#include <stdio.h>

#define NEXT(kindValue, xValue, yValue, code) do { \
    if (kd_window_next_pointer(&window, &event, &pending) != KD_OK || !pending || \
        event.kind != (kindValue) || event.x != (xValue) || event.y != (yValue)) return (code); \
} while (0)

int main(void) {
#if !defined(_WIN32) && !defined(__linux__) && !defined(__APPLE__)
    puts("SKIP: no pointer backend");
    return 0;
#else
    kd_window window = {0};
    kd_pointer_event event = {0};
    int pending = 0;

    kd_window_enqueue_pointer(&window, KD_POINTER_DOWN, 7, 11);
    kd_window_enqueue_pointer(&window, KD_POINTER_UP, 7, 11);
    kd_window_enqueue_pointer(&window, KD_POINTER_MOVE, 9, 13);
    kd_window_enqueue_pointer(&window, KD_POINTER_SCROLL, 0, -1);
    kd_window_enqueue_pointer(&window, KD_KEY_DOWN, 'A', 3);
    kd_window_enqueue_pointer(&window, KD_TEXT_INPUT, 'a', 3);
    kd_window_enqueue_pointer(&window, KD_KEY_UP, 'A', 3);
    kd_window_enqueue_pointer(&window, KD_POINTER_RIGHT_DOWN, 21, 22);
    kd_window_enqueue_pointer(&window, KD_POINTER_RIGHT_UP, 21, 22);
    kd_window_enqueue_pointer(&window, KD_POINTER_MIDDLE_DOWN, 31, 32);
    kd_window_enqueue_pointer(&window, KD_POINTER_MIDDLE_UP, 31, 32);
    kd_window_enqueue_pointer(&window, KD_WINDOW_FOCUS_GAINED, 0, 0);
    kd_window_enqueue_pointer(&window, KD_POINTER_SCROLL_HORIZONTAL, -1, 0);
    kd_window_enqueue_pointer(&window, KD_WINDOW_FOCUS_LOST, 0, 0);
    kd_window_enqueue_pointer(&window, KD_POINTER_ENTER, 41, 42);
    kd_window_enqueue_pointer(&window, KD_POINTER_EXIT, 41, 42);

    NEXT(KD_POINTER_DOWN, 7, 11, 1);
    NEXT(KD_POINTER_UP, 7, 11, 2);
    NEXT(KD_POINTER_MOVE, 9, 13, 3);
    NEXT(KD_POINTER_SCROLL, 0, -1, 4);
    NEXT(KD_KEY_DOWN, 'A', 3, 5);
    NEXT(KD_TEXT_INPUT, 'a', 3, 6);
    NEXT(KD_KEY_UP, 'A', 3, 7);
    NEXT(KD_POINTER_RIGHT_DOWN, 21, 22, 8);
    NEXT(KD_POINTER_RIGHT_UP, 21, 22, 9);
    NEXT(KD_POINTER_MIDDLE_DOWN, 31, 32, 10);
    NEXT(KD_POINTER_MIDDLE_UP, 31, 32, 11);
    NEXT(KD_WINDOW_FOCUS_GAINED, 0, 0, 12);
    NEXT(KD_POINTER_SCROLL_HORIZONTAL, -1, 0, 13);
    NEXT(KD_WINDOW_FOCUS_LOST, 0, 0, 14);
    NEXT(KD_POINTER_ENTER, 41, 42, 15);
    NEXT(KD_POINTER_EXIT, 41, 42, 16);

    if (kd_window_next_pointer(&window, &event, &pending) != KD_OK || pending) {
        return 19;
    }

    kd_window_enqueue_pointer(&window, KD_POINTER_MOVE, 50, 51);
    kd_window_enqueue_pointer(&window, KD_POINTER_MOVE, 60, 61);
    kd_window_enqueue_pointer(&window, KD_POINTER_SCROLL, 0, 2);
    kd_window_enqueue_pointer(&window, KD_POINTER_SCROLL, 0, 3);
    kd_window_enqueue_pointer(&window, KD_POINTER_SCROLL_HORIZONTAL, 4, 0);
    kd_window_enqueue_pointer(&window, KD_POINTER_SCROLL_HORIZONTAL, 5, 0);

    NEXT(KD_POINTER_MOVE, 60, 61, 20);
    NEXT(KD_POINTER_SCROLL, 0, 5, 21);
    NEXT(KD_POINTER_SCROLL_HORIZONTAL, 9, 0, 22);
    if (kd_window_next_pointer(&window, &event, &pending) != KD_OK || pending) {
        return 23;
    }

    for (uint32_t i = 0; i < KD_EVENT_CAPACITY; ++i) {
        kd_window_enqueue_pointer(
            &window,
            KD_POINTER_DOWN,
            (int32_t)i,
            19
        );
    }
    kd_window_enqueue_pointer(&window, KD_POINTER_DOWN, 999, 19);

    if (kd_window_next_pointer(&window, &event, &pending) != KD_INPUT_OVERFLOW ||
        pending) {
        return 24;
    }

    for (uint32_t i = 0; i < KD_EVENT_CAPACITY; ++i) {
        NEXT(KD_POINTER_DOWN, (int32_t)i, 19, 25);
    }

    if (kd_window_next_pointer(&window, &event, &pending) != KD_OK || pending) {
        return 26;
    }

    puts("pointer FIFO, coalescing, modifiers, buttons, hover, focus, scroll and overflow: PASS");
    return 0;
#endif
}
