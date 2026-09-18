#include "kotlin_display.h"
#include <stdio.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <time.h>
#endif

static void pause_frame(void) {
#ifdef _WIN32
    Sleep(16);
#else
    struct timespec delay = {0, 16000000L};
    nanosleep(&delay, NULL);
#endif
}

int main(void) {
    kd_window *window = NULL;
    kd_status status = kd_window_create(960, 540, "Kotlin Display | Vulkan First Frame", &window);
    if (status != KD_OK) {
        fprintf(stderr, "window: %d\n", (int)status);
        return 1;
    }

    kd_vk_frame *renderer = NULL;
    uint32_t rendered_width = 0, rendered_height = 0;
    int closed = 0;
    int exit_code = 0;

    while (!closed) {
        if (kd_window_poll(window, &closed) != KD_OK) { exit_code = 3; break; }
        if (closed) break;

        uint32_t width = 0, height = 0;
        if (kd_window_get_size(window, &width, &height) != KD_OK) { exit_code = 4; break; }
        if (!width || !height) {
            pause_frame();
            continue;
        }

        if (!renderer || width != rendered_width || height != rendered_height) {
            kd_vk_frame_destroy(renderer);
            renderer = NULL;
            status = kd_vk_frame_create(window, &renderer);
            if (status == KD_SWAPCHAIN_OUT_OF_DATE) {
                pause_frame();
                continue;
            }
            if (status != KD_OK) {
                fprintf(stderr, "Vulkan init: %d\n", (int)status);
                exit_code = 5;
                break;
            }
            rendered_width = width;
            rendered_height = height;
        }

        status = kd_vk_frame_present(renderer, 0.04f, 0.14f, 0.29f, 1.0f);
        if (status == KD_SWAPCHAIN_OUT_OF_DATE) {
            kd_vk_frame_destroy(renderer);
            renderer = NULL;
            rendered_width = rendered_height = 0;
            continue;
        }
        if (status != KD_OK) {
            fprintf(stderr, "present: %d\n", (int)status);
            exit_code = 6;
            break;
        }
        pause_frame();
    }

    kd_vk_frame_destroy(renderer);
    kd_window_destroy(window);
    return exit_code;
}
