#define _POSIX_C_SOURCE 199309L
#define main kd_first_frame_main
#include "../examples/first_frame.c"
#undef main

#include <stdlib.h>
#define CHECK(value) do { if (!(value)) abort(); } while (0)

struct kd_window { int unused; };
struct kd_vk_frame { int id; };

static struct kd_window test_window;
static struct kd_vk_frame test_frames[3];
static int polls;
static int creates;
static int destroys;
static int presents;

kd_status kd_window_create(uint32_t width, uint32_t height, const char *title, kd_window **out) {
    CHECK(width == 960 && height == 540 && title && out);
    *out = &test_window;
    return KD_OK;
}

kd_status kd_window_poll(kd_window *window, int *closed) {
    CHECK(window == &test_window && closed);
    ++polls;
    *closed = polls >= 6;
    return KD_OK;
}

kd_status kd_window_get_size(kd_window *window, uint32_t *width, uint32_t *height) {
    CHECK(window == &test_window && width && height);
    *width = polls == 3 ? 0u : polls == 4 ? 1280u : polls == 5 ? 1600u : 960u;
    *height = polls == 3 ? 0u : polls == 4 ? 720u : polls == 5 ? 900u : 540u;
    return KD_OK;
}

kd_status kd_vk_frame_create(kd_window *window, kd_vk_frame **out) {
    CHECK(window == &test_window && out && creates < 3);
    test_frames[creates].id = creates + 1;
    ++creates;
    *out = &test_frames[creates - 1];
    return KD_OK;
}

kd_status kd_vk_frame_present(kd_vk_frame *frame, float r, float g, float b, float a) {
    CHECK(frame && r >= 0 && g >= 0 && b >= 0 && a == 1.0f);
    ++presents;
    return presents == 2 ? KD_SWAPCHAIN_OUT_OF_DATE : KD_OK;
}

void kd_vk_frame_destroy(kd_vk_frame *frame) {
    if (frame) ++destroys;
}

void kd_window_destroy(kd_window *window) {
    CHECK(window == &test_window);
}

int main(void) {
    CHECK(kd_first_frame_main() == 0);
    CHECK(polls == 6);
    CHECK(creates == 3);
    CHECK(destroys == 3);
    CHECK(presents == 4);
    return 0;
}
