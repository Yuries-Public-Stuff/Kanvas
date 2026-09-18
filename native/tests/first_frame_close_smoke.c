#define _POSIX_C_SOURCE 199309L
#define main kd_first_frame_main
#include "../examples/first_frame.c"
#undef main

struct kd_window { int unused; };
struct kd_vk_frame { int unused; };

static struct kd_window window_stub;
static int polls;
static int size_reads;
static int creates;
static int presents;
static int destroyed;

kd_status kd_window_create(uint32_t width, uint32_t height, const char *title, kd_window **out) {
    if (width != 960 || height != 540 || !title || !out) return KD_INVALID_ARGUMENT;
    *out = &window_stub;
    return KD_OK;
}

kd_status kd_window_poll(kd_window *window, int *closed) {
    if (window != &window_stub || !closed) return KD_INVALID_ARGUMENT;
    *closed = ++polls >= 2;
    return KD_OK;
}

kd_status kd_window_get_size(kd_window *window, uint32_t *width, uint32_t *height) {
    if (window != &window_stub || !width || !height) return KD_INVALID_ARGUMENT;
    ++size_reads;
    *width = 0;
    *height = 0;
    return KD_OK;
}

kd_status kd_vk_frame_create(kd_window *window, kd_vk_frame **out) {
    (void)window; (void)out;
    ++creates;
    return KD_VULKAN_ERROR;
}

kd_status kd_vk_frame_present(kd_vk_frame *frame, float red, float green, float blue, float alpha) {
    (void)frame; (void)red; (void)green; (void)blue; (void)alpha;
    ++presents;
    return KD_VULKAN_ERROR;
}

void kd_vk_frame_destroy(kd_vk_frame *frame) { (void)frame; }

void kd_window_destroy(kd_window *window) {
    if (window == &window_stub) ++destroyed;
}

int main(void) {
    if (kd_first_frame_main() != 0) return 1;
    if (polls != 2 || size_reads != 1) return 2;
    if (creates || presents || destroyed != 1) return 3;
    return 0;
}
