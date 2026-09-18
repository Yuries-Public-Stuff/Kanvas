#include "kotlin_display.h"
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

uint32_t kd_abi_version(void) {
    return KD_ABI_VERSION;
}

kd_status kd_probe_vulkan(kd_vulkan_probe *out) {
    if (out == NULL) return KD_INVALID_ARGUMENT;
    memset(out, 0, sizeof(*out));
    out->abi_version = KD_ABI_VERSION;
    out->instance_api_version = VK_API_VERSION_1_0;

    // Vulkan 1.0 may lack this function.
    PFN_vkEnumerateInstanceVersion version_fn =
        (PFN_vkEnumerateInstanceVersion)vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    if (version_fn != NULL) {
        uint32_t version = VK_API_VERSION_1_0;
        VkResult version_result = version_fn(&version);
        if (version_result == VK_SUCCESS) out->instance_api_version = version;
    }

    VkApplicationInfo app = {0};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "Kotlin Display Probe";
    app.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    app.pEngineName = "Kotlin Display";
    app.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    app.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo create_info = {0};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance(&create_info, NULL, &instance);
    out->native_result = (int32_t)result;
    if (result != VK_SUCCESS) {
        return result == VK_ERROR_INCOMPATIBLE_DRIVER ? KD_VULKAN_UNAVAILABLE : KD_VULKAN_ERROR;
    }

    kd_status status = KD_OK;
    uint32_t count = 0;
    VkPhysicalDevice *devices = NULL;
    result = vkEnumeratePhysicalDevices(instance, &count, NULL);
    if (result != VK_SUCCESS) {
        status = KD_VULKAN_ERROR;
        goto cleanup;
    }
    out->physical_device_count = count;
    if (count == 0) {
        status = KD_NO_GRAPHICS_DEVICE;
        goto cleanup;
    }

    devices = (VkPhysicalDevice *)calloc(count, sizeof(*devices));
    if (devices == NULL) {
        status = KD_OUT_OF_MEMORY;
        goto cleanup;
    }
    result = vkEnumeratePhysicalDevices(instance, &count, devices);
    if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
        status = KD_VULKAN_ERROR;
        goto cleanup;
    }
    out->physical_device_count = count;
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t queue_count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &queue_count, NULL);
        if (queue_count == 0) continue;
        VkQueueFamilyProperties *queues =
            (VkQueueFamilyProperties *)calloc(queue_count, sizeof(*queues));
        if (queues == NULL) {
            status = KD_OUT_OF_MEMORY;
            goto cleanup;
        }
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &queue_count, queues);
        for (uint32_t j = 0; j < queue_count; ++j) {
            if (queues[j].queueCount > 0 && (queues[j].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                ++out->graphics_device_count;
                break;
            }
        }
        free(queues);
    }
    if (out->graphics_device_count == 0) status = KD_NO_GRAPHICS_DEVICE;

cleanup:
    out->native_result = status == KD_VULKAN_ERROR ? (int32_t)result : 0;
    free(devices);
    vkDestroyInstance(instance, NULL);
    return status;
}
