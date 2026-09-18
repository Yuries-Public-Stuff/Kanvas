#include "kd_swapchain_policy.h"
#include <stdio.h>

#define CHECK(condition) do { if (!(condition)) { \
    fprintf(stderr, "check failed at line %d: %s\n", __LINE__, #condition); \
    return 1; \
} } while (0)

int main(void) {
    const kd_surface_limits limits = {2, 3, KD_EXTENT_UNDEFINED, KD_EXTENT_UNDEFINED, 64, 64, 1920, 1080};
    const kd_surface_format formats[] = {{KD_FORMAT_BGRA8_UNORM, 0}, {KD_FORMAT_BGRA8_SRGB, 0}};
    const uint32_t modes[] = {KD_PRESENT_MODE_FIFO, KD_PRESENT_MODE_MAILBOX};
    kd_swapchain_choice result;
    CHECK(kd_choose_swapchain(&limits, formats, 2, modes, 2, 2560, 720, &result));
    CHECK(result.surface_format.format == KD_FORMAT_BGRA8_SRGB);
    CHECK(result.present_mode == KD_PRESENT_MODE_MAILBOX);
    CHECK(result.image_count == 3 && result.width == 1920 && result.height == 720);

    const uint32_t fifo[] = {KD_PRESENT_MODE_FIFO};
    CHECK(kd_choose_swapchain(&limits, formats, 2, fifo, 1, 800, 600, &result));
    CHECK(result.present_mode == KD_PRESENT_MODE_FIFO);
    CHECK(!kd_choose_swapchain(&limits, formats, 2, fifo, 1, 0, 600, &result));
    CHECK(result.width == 0 && result.height == 0);
    CHECK(!kd_choose_swapchain(NULL, formats, 2, fifo, 1, 800, 600, &result));

    const kd_surface_limits fixed = {2, 2, 1024, 768, 64, 64, 1920, 1080};
    CHECK(kd_choose_swapchain(&fixed, formats, 2, fifo, 1, 0, 0, &result));
    CHECK(result.width == 1024 && result.height == 768 && result.image_count == 2);

    const kd_surface_format undefined[] = {{KD_FORMAT_UNDEFINED, 0}};
    CHECK(kd_choose_swapchain(&fixed, undefined, 1, fifo, 1, 0, 0, &result));
    CHECK(result.surface_format.format == KD_FORMAT_BGRA8_SRGB);

    const kd_surface_limits bad = {4, 2, 1024, 768, 64, 64, 1920, 1080};
    CHECK(!kd_choose_swapchain(&bad, formats, 2, fifo, 1, 800, 600, &result));
    puts("swapchain selection: all checks passed");
    return 0;
}
