#define _POSIX_C_SOURCE 199309L
#define main kd_first_frame_main
#include "../examples/first_frame.c"
#undef main

struct kd_window { int unused; };
struct kd_vk_frame { int unused; };

static struct kd_window window_stub;
static struct kd_vk_frame frame_stub;
static int scenario;
static int windows_destroyed;
static int frames_destroyed;
static int creates;
static int presents;

kd_status kd_window_create(uint32_t width, uint32_t height, const char *title, kd_window **out) {
    if (width != 960 || height != 540 || !title || !out) return KD_INVALID_ARGUMENT;
    *out = &window_stub;
    return KD_OK;
}

kd_status kd_window_poll(kd_window *window, int *closed) {
    if (window != &window_stub || !closed) return KD_INVALID_ARGUMENT;
    *closed = 0;
    return KD_OK;
}

kd_status kd_window_get_size(kd_window *window, uint32_t *width, uint32_t *height) {
    if (window != &window_stub || !width || !height) return KD_INVALID_ARGUMENT;
    *width = 960;
    *height = 540;
    return KD_OK;
}

kd_status kd_vk_frame_create(kd_window *window, kd_vk_frame **out) {
    if (window != &window_stub || !out) return KD_INVALID_ARGUMENT;
    ++creates;
    if (scenario == 1) return KD_VULKAN_ERROR;
    *out = &frame_stub;
    return KD_OK;
}

kd_status kd_vk_frame_present(kd_vk_frame *frame, float red, float green, float blue, float alpha) {
    if (frame != &frame_stub || red < 0 || green < 0 || blue < 0 || alpha != 1.0f)
        return KD_INVALID_ARGUMENT;
    ++presents;
    return KD_VULKAN_ERROR;
}

void kd_vk_frame_destroy(kd_vk_frame *frame) {
    if (frame == &frame_stub) ++frames_destroyed;
}

void kd_window_destroy(kd_window *window) {
    if (window == &window_stub) ++windows_destroyed;
}

int main(void) {
    scenario = 1;
    if (kd_first_frame_main() != 5) return 1;
    if (windows_destroyed != 1 || frames_destroyed || creates != 1 || presents) return 2;

    scenario = 2;
    windows_destroyed = frames_destroyed = creates = presents = 0;
    if (kd_first_frame_main() != 6) return 3;
    if (windows_destroyed != 1 || frames_destroyed != 1 || creates != 1 || presents != 1) return 4;
    return 0;
}
