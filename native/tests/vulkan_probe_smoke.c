#include "kotlin_display.h"
#include <stdio.h>

int main(void) {
    kd_vulkan_probe probe;
    if (kd_abi_version() != KD_ABI_VERSION) return 2;
    if (kd_probe_vulkan(NULL) != KD_INVALID_ARGUMENT) return 3;
    kd_status status = kd_probe_vulkan(&probe);
    if (probe.abi_version != KD_ABI_VERSION) return 4;
    printf("Vulkan probe status=%d version=%u devices=%u graphics_devices=%u vk_result=%d\n",
           (int)status, probe.instance_api_version, probe.physical_device_count,
           probe.graphics_device_count, probe.native_result);
    /* A headless machine may legitimately have no graphics-capable Vulkan device. */
    return (status == KD_OK || status == KD_NO_GRAPHICS_DEVICE || status == KD_VULKAN_UNAVAILABLE) ? 0 : 1;
}
