#include "kotlin_display.h"
#include <stdio.h>

int main(void) {
    kd_window *window = NULL;
    kd_status status = kd_window_create(800, 600, "Kotlin Display Vulkan Probe", &window);
    if (status != KD_OK) {
        fprintf(stderr, "Window creation failed: %d\n", (int)status);
        return 1;
    }

    kd_vulkan_surface_probe probe = {0};
    status = kd_probe_vulkan_surface(window, &probe);
    printf("ABI: %u\n", probe.abi_version);
    printf("Vulkan status: %d (native: %d)\n", (int)status, probe.native_result);
    printf("GPUs: %u, presentable: %u, swapchain-ready: %u\n",
           probe.physical_device_count, probe.presentable_device_count,
           probe.swapchain_device_count);
    if (status == KD_OK) {
        printf("Format: %u, color space: %u, present mode: %u\n",
               probe.chosen_format, probe.chosen_color_space, probe.chosen_present_mode);
        printf("Images: %u, size: %ux%u\n",
               probe.chosen_image_count, probe.chosen_width, probe.chosen_height);
    }
    kd_window_destroy(window);
    return status == KD_OK ? 0 : 1;
}
