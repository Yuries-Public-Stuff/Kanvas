#include "kotlin_display.h"
#include "kd_swapchain_policy.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#define VK_USE_PLATFORM_WIN32_KHR
#elif defined(__linux__)
#include <X11/Xlib.h>
#define VK_USE_PLATFORM_XLIB_KHR
#endif
#include <vulkan/vulkan.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32) || defined(__linux__)
/* Returns 1 if this GPU can create a compatible swapchain. */
static int inspect_swapchain(VkPhysicalDevice device, VkSurfaceKHR surface,
                             uint32_t width, uint32_t height,
                             kd_swapchain_choice *choice, VkResult *last) {
    uint32_t count = 0;
    *last = vkEnumerateDeviceExtensionProperties(device, NULL, &count, NULL);
    if (*last != VK_SUCCESS) return -1;
    if (!count) return 0;

    VkExtensionProperties *extensions = calloc(count, sizeof(*extensions));
    if (!extensions) return -2;
    *last = vkEnumerateDeviceExtensionProperties(device, NULL, &count, extensions);
    if (*last != VK_SUCCESS) { free(extensions); return -1; }
    int available = 0;
    for (uint32_t i = 0; i < count; ++i)
        available |= strcmp(extensions[i].extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME) == 0;
    free(extensions);
    if (!available) return 0;

    VkSurfaceCapabilitiesKHR caps;
    *last = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, &caps);
    if (*last != VK_SUCCESS) return -1;
    if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)) return 0;
    if (!caps.supportedCompositeAlpha) return 0;

    uint32_t format_count = 0, mode_count = 0;
    *last = vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, &format_count, NULL);
    if (*last != VK_SUCCESS) return -1;
    *last = vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, &mode_count, NULL);
    if (*last != VK_SUCCESS) return -1;
    if (!format_count || !mode_count) return 0;

    VkSurfaceFormatKHR *native_formats = calloc(format_count, sizeof(*native_formats));
    VkPresentModeKHR *native_modes = calloc(mode_count, sizeof(*native_modes));
    kd_surface_format *formats = calloc(format_count, sizeof(*formats));
    uint32_t *modes = calloc(mode_count, sizeof(*modes));
    if (!native_formats || !native_modes || !formats || !modes) {
        free(native_formats); free(native_modes); free(formats); free(modes);
        return -2;
    }

    *last = vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, &format_count, native_formats);
    if (*last != VK_SUCCESS) goto failed;
    *last = vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, &mode_count, native_modes);
    if (*last != VK_SUCCESS) goto failed;
    for (uint32_t i = 0; i < format_count; ++i) {
        formats[i].format = (uint32_t)native_formats[i].format;
        formats[i].color_space = (uint32_t)native_formats[i].colorSpace;
    }
    for (uint32_t i = 0; i < mode_count; ++i) modes[i] = (uint32_t)native_modes[i];

    kd_surface_limits limits = {
        caps.minImageCount, caps.maxImageCount,
        caps.currentExtent.width, caps.currentExtent.height,
        caps.minImageExtent.width, caps.minImageExtent.height,
        caps.maxImageExtent.width, caps.maxImageExtent.height
    };
    int selected = kd_choose_swapchain(&limits, formats, format_count,
                                       modes, mode_count, width, height, choice);
    free(native_formats); free(native_modes); free(formats); free(modes);
    return selected;

failed:
    free(native_formats); free(native_modes); free(formats); free(modes);
    return -1;
}
#endif

kd_status kd_probe_vulkan_surface(kd_window *window, kd_vulkan_surface_probe *out) {
    if (!window || !out) return KD_INVALID_ARGUMENT;
    memset(out, 0, sizeof(*out));
    out->abi_version = KD_ABI_VERSION;

    kd_native_window_handles handles;
    kd_status window_status = kd_window_get_native_handles(window, &handles);
    if (window_status != KD_OK) return window_status;

#if defined(_WIN32)
    if (handles.platform != KD_WINDOW_PLATFORM_WIN32) return KD_UNSUPPORTED_PLATFORM;
    const char *platform_extension = VK_KHR_WIN32_SURFACE_EXTENSION_NAME;
#elif defined(__linux__)
    if (handles.platform != KD_WINDOW_PLATFORM_XLIB) return KD_UNSUPPORTED_PLATFORM;
    const char *platform_extension = VK_KHR_XLIB_SURFACE_EXTENSION_NAME;
#else
    (void)handles;
    return KD_UNSUPPORTED_PLATFORM;
#endif

#if defined(_WIN32) || defined(__linux__)
    uint32_t width = 0, height = 0;
    window_status = kd_window_get_size(window, &width, &height);
    if (window_status != KD_OK) return window_status;
    if (!width || !height) return KD_SURFACE_UNAVAILABLE;

    uint32_t extension_count = 0;
    VkResult result = vkEnumerateInstanceExtensionProperties(NULL, &extension_count, NULL);
    out->native_result = (int32_t)result;
    if (result != VK_SUCCESS) return KD_VULKAN_ERROR;
    VkExtensionProperties *extensions = calloc(extension_count ? extension_count : 1, sizeof(*extensions));
    if (!extensions) return KD_OUT_OF_MEMORY;
    result = vkEnumerateInstanceExtensionProperties(NULL, &extension_count, extensions);
    if (result != VK_SUCCESS) {
        free(extensions);
        out->native_result = (int32_t)result;
        return KD_VULKAN_ERROR;
    }
    int has_surface = 0, has_platform = 0;
    for (uint32_t i = 0; i < extension_count; ++i) {
        has_surface |= strcmp(extensions[i].extensionName, VK_KHR_SURFACE_EXTENSION_NAME) == 0;
        has_platform |= strcmp(extensions[i].extensionName, platform_extension) == 0;
    }
    free(extensions);
    if (!has_surface || !has_platform) return KD_SURFACE_UNAVAILABLE;

    const char *enabled_extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, platform_extension};
    VkApplicationInfo application = {0};
    application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application.pApplicationName = "Kotlin Display Surface Probe";
    application.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo create = {0};
    create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create.pApplicationInfo = &application;
    create.enabledExtensionCount = 2;
    create.ppEnabledExtensionNames = enabled_extensions;

    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice *devices = NULL;
    kd_status status = KD_VULKAN_ERROR;
    result = vkCreateInstance(&create, NULL, &instance);
    out->native_result = (int32_t)result;
    if (result != VK_SUCCESS)
        return result == VK_ERROR_INCOMPATIBLE_DRIVER ? KD_VULKAN_UNAVAILABLE : KD_VULKAN_ERROR;

#if defined(_WIN32)
    VkWin32SurfaceCreateInfoKHR surface_info = {0};
    surface_info.sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR;
    surface_info.hinstance = (HINSTANCE)handles.display;
    surface_info.hwnd = (HWND)handles.window;
    result = vkCreateWin32SurfaceKHR(instance, &surface_info, NULL, &surface);
#else
    VkXlibSurfaceCreateInfoKHR surface_info = {0};
    surface_info.sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR;
    surface_info.dpy = (Display *)handles.display;
    surface_info.window = (Window)handles.window;
    result = vkCreateXlibSurfaceKHR(instance, &surface_info, NULL, &surface);
#endif
    out->native_result = (int32_t)result;
    if (result != VK_SUCCESS) { status = KD_SURFACE_UNAVAILABLE; goto cleanup; }
    out->surface_created = 1;

    uint32_t count = 0;
    result = vkEnumeratePhysicalDevices(instance, &count, NULL);
    if (result != VK_SUCCESS) goto cleanup;
    if (!count) { status = KD_NO_GRAPHICS_DEVICE; goto cleanup; }
    devices = calloc(count, sizeof(*devices));
    if (!devices) { status = KD_OUT_OF_MEMORY; goto cleanup; }
    result = vkEnumeratePhysicalDevices(instance, &count, devices);
    if (result != VK_SUCCESS) goto cleanup;
    out->physical_device_count = count;
    status = KD_NO_GRAPHICS_DEVICE;

    for (uint32_t i = 0; i < count; ++i) {
        uint32_t queue_count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &queue_count, NULL);
        if (!queue_count) continue;
        VkQueueFamilyProperties *queues = calloc(queue_count, sizeof(*queues));
        if (!queues) { status = KD_OUT_OF_MEMORY; goto cleanup; }
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &queue_count, queues);
        int presentable = 0;
        for (uint32_t j = 0; j < queue_count; ++j) {
            if (!queues[j].queueCount || !(queues[j].queueFlags & VK_QUEUE_GRAPHICS_BIT)) continue;
            VkBool32 supported = VK_FALSE;
            result = vkGetPhysicalDeviceSurfaceSupportKHR(devices[i], j, surface, &supported);
            if (result != VK_SUCCESS) { status = KD_VULKAN_ERROR; break; }
            if (supported) { presentable = 1; break; }
        }
        free(queues);
        if (status == KD_VULKAN_ERROR) goto cleanup;
        if (!presentable) continue;
        ++out->presentable_device_count;

        kd_swapchain_choice choice = {0};
        int available = inspect_swapchain(devices[i], surface, width, height, &choice, &result);
        if (available == -2) { status = KD_OUT_OF_MEMORY; goto cleanup; }
        if (available == -1) { status = KD_VULKAN_ERROR; goto cleanup; }
        if (!available) continue;
        if (!out->swapchain_device_count) {
            out->chosen_format = choice.surface_format.format;
            out->chosen_color_space = choice.surface_format.color_space;
            out->chosen_present_mode = choice.present_mode;
            out->chosen_image_count = choice.image_count;
            out->chosen_width = choice.width;
            out->chosen_height = choice.height;
        }
        ++out->swapchain_device_count;
    }
    if (out->swapchain_device_count) status = KD_OK;
    else if (out->presentable_device_count) status = KD_SURFACE_UNAVAILABLE;

cleanup:
    if (status == KD_VULKAN_ERROR) out->native_result = (int32_t)result;
    free(devices);
    if (surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surface, NULL);
    vkDestroyInstance(instance, NULL);
    return status;
#endif
}
