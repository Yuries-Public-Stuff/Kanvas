#include "kotlin_display.h"
#include <stdio.h>

int main(void) {
    kd_vulkan_surface_probe probe;
    if (kd_abi_version() != KD_ABI_VERSION) return 1;
    if (kd_probe_vulkan_surface(NULL, &probe) != KD_INVALID_ARGUMENT) return 2;

    kd_window *window = NULL;
    kd_status status = kd_window_create(320, 240, "Kotlin Display Test", &window);
    if (status == KD_WINDOW_UNAVAILABLE || status == KD_UNSUPPORTED_PLATFORM) {
        puts("SKIP: no desktop window available");
        return 0;
    }
    if (status != KD_OK || window == NULL) return 3;

    status = kd_probe_vulkan_surface(window, &probe);
    printf("surface=%u devices=%u presentable=%u swapchains=%u format=%u mode=%u images=%u size=%ux%u status=%d vk=%d\n",
           probe.surface_created, probe.physical_device_count,
           probe.presentable_device_count, probe.swapchain_device_count,
           probe.chosen_format, probe.chosen_present_mode,
           probe.chosen_image_count, probe.chosen_width, probe.chosen_height,
           (int)status, probe.native_result);
    kd_window_destroy(window);

    if (probe.abi_version != KD_ABI_VERSION) return 4;
    if (probe.swapchain_device_count > probe.presentable_device_count) return 5;
    if (status == KD_OK && (!probe.surface_created || !probe.swapchain_device_count ||
        !probe.chosen_image_count || !probe.chosen_width || !probe.chosen_height)) return 6;
    if (status == KD_OK || status == KD_NO_GRAPHICS_DEVICE ||
        status == KD_SURFACE_UNAVAILABLE || status == KD_VULKAN_UNAVAILABLE) return 0;
    return 7;
}
