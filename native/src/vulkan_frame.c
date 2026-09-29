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

typedef struct kd_vk_texture {
    uint32_t id;
    uint32_t width;
    uint32_t height;
    uint32_t *pixels;
    struct kd_vk_texture *next;
} kd_vk_texture;

struct kd_vk_frame {
    VkInstance instance;
    VkSurfaceKHR surface;
    VkPhysicalDevice physical_device;
    VkDevice device;
    VkQueue queue;
    VkSwapchainKHR swapchain;
    VkImage *images;
    uint32_t image_count;
    VkCommandPool pool;
    VkCommandBuffer command;
    VkSemaphore acquired;
    VkSemaphore finished;
    VkFence fence;
    VkFormat format;
    VkBuffer upload_buffer;
    VkDeviceMemory upload_memory;
    void *upload_map;
    VkDeviceSize upload_size;
    uint32_t width;
    uint32_t height;
    kd_vk_texture *textures;
};

static kd_vk_texture *kd_vk_find_texture(
    kd_vk_frame *frame,
    uint32_t id
) {
    for (kd_vk_texture *entry = frame ? frame->textures : NULL;
         entry;
         entry = entry->next) {
        if (entry->id == id) return entry;
    }
    return NULL;
}

static void kd_vk_destroy_textures(kd_vk_frame *frame) {
    if (!frame) return;
    kd_vk_texture *entry = frame->textures;
    while (entry) {
        kd_vk_texture *next = entry->next;
        free(entry->pixels);
        free(entry);
        entry = next;
    }
    frame->textures = NULL;
}

void kd_vk_frame_destroy(kd_vk_frame *frame) {
    if (!frame) return;
    if (frame->device) {
        vkDeviceWaitIdle(frame->device);
        if (frame->upload_map && frame->upload_memory) {
            vkUnmapMemory(frame->device, frame->upload_memory);
            frame->upload_map = NULL;
        }
        if (frame->upload_buffer) vkDestroyBuffer(frame->device, frame->upload_buffer, NULL);
        if (frame->upload_memory) vkFreeMemory(frame->device, frame->upload_memory, NULL);
        if (frame->fence) vkDestroyFence(frame->device, frame->fence, NULL);
        if (frame->finished) vkDestroySemaphore(frame->device, frame->finished, NULL);
        if (frame->acquired) vkDestroySemaphore(frame->device, frame->acquired, NULL);
        if (frame->pool) vkDestroyCommandPool(frame->device, frame->pool, NULL);
        if (frame->swapchain) vkDestroySwapchainKHR(frame->device, frame->swapchain, NULL);
        vkDestroyDevice(frame->device, NULL);
    }
    kd_vk_destroy_textures(frame);
    free(frame->images);
    if (frame->surface) vkDestroySurfaceKHR(frame->instance, frame->surface, NULL);
    if (frame->instance) vkDestroyInstance(frame->instance, NULL);
    free(frame);
}

#if defined(_WIN32) || defined(__linux__)
static int has_extension(VkPhysicalDevice device) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, NULL, &count, NULL) != VK_SUCCESS || !count) return 0;
    VkExtensionProperties *extensions = calloc(count, sizeof(*extensions));
    if (!extensions) return 0;
    VkResult result = vkEnumerateDeviceExtensionProperties(device, NULL, &count, extensions);
    int found = 0;
    if (result == VK_SUCCESS) {
        for (uint32_t i = 0; i < count; ++i)
            found |= strcmp(extensions[i].extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME) == 0;
    }
    free(extensions);
    return found;
}

static int choose_swapchain(VkPhysicalDevice device, VkSurfaceKHR surface, uint32_t width,
                            uint32_t height, kd_swapchain_choice *out, VkSurfaceCapabilitiesKHR *caps) {
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, caps) != VK_SUCCESS ||
        !(caps->supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) ||
        !caps->supportedCompositeAlpha) return 0;
    uint32_t nf = 0, nm = 0;
    if (vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, &nf, NULL) != VK_SUCCESS || !nf ||
        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, &nm, NULL) != VK_SUCCESS || !nm) return 0;
    VkSurfaceFormatKHR *vf = calloc(nf, sizeof(*vf));
    VkPresentModeKHR *vm = calloc(nm, sizeof(*vm));
    kd_surface_format *formats = calloc(nf, sizeof(*formats));
    uint32_t *modes = calloc(nm, sizeof(*modes));
    if (!vf || !vm || !formats || !modes) {
        free(vf); free(vm); free(formats); free(modes);
        return 0;
    }
    int ok = vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, &nf, vf) == VK_SUCCESS &&
             vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, &nm, vm) == VK_SUCCESS;
    if (ok) {
        for (uint32_t i = 0; i < nf; ++i) {
            formats[i].format = (uint32_t)vf[i].format;
            formats[i].color_space = (uint32_t)vf[i].colorSpace;
        }
        for (uint32_t i = 0; i < nm; ++i) modes[i] = (uint32_t)vm[i];
        kd_surface_limits limits = {
            caps->minImageCount, caps->maxImageCount, caps->currentExtent.width, caps->currentExtent.height,
            caps->minImageExtent.width, caps->minImageExtent.height,
            caps->maxImageExtent.width, caps->maxImageExtent.height
        };
        ok = kd_choose_swapchain(&limits, formats, nf, modes, nm, width, height, out);
    }
    free(vf); free(vm); free(formats); free(modes);
    return ok;
}
static uint32_t kd_vk_find_memory_type(
    VkPhysicalDevice physical_device,
    uint32_t type_bits,
    VkMemoryPropertyFlags required
) {
    VkPhysicalDeviceMemoryProperties properties;
    vkGetPhysicalDeviceMemoryProperties(physical_device, &properties);
    for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        if ((type_bits & (1u << i)) &&
            (properties.memoryTypes[i].propertyFlags & required) == required) {
            return i;
        }
    }
    return UINT32_MAX;
}

static int kd_vk_supported_upload_format(VkFormat format) {
    return format == VK_FORMAT_B8G8R8A8_UNORM ||
        format == VK_FORMAT_B8G8R8A8_SRGB ||
        format == VK_FORMAT_R8G8B8A8_UNORM ||
        format == VK_FORMAT_R8G8B8A8_SRGB;
}

static unsigned char kd_vk_channel(float value) {
    if (value <= 0.f) return 0;
    if (value >= 1.f) return 255;
    return (unsigned char)(value * 255.f + .5f);
}

static uint32_t kd_vk_pack(VkFormat format, kd_draw_color color) {
    const uint32_t r = kd_vk_channel(color.red);
    const uint32_t g = kd_vk_channel(color.green);
    const uint32_t b = kd_vk_channel(color.blue);
    const uint32_t a = kd_vk_channel(color.alpha);
    if (format == VK_FORMAT_B8G8R8A8_UNORM ||
        format == VK_FORMAT_B8G8R8A8_SRGB) {
        return b | (g << 8) | (r << 16) | (a << 24);
    }
    return r | (g << 8) | (b << 16) | (a << 24);
}

static uint32_t kd_vk_blend(
    VkFormat format,
    uint32_t destination,
    kd_draw_color source
) {
    const uint32_t sa = kd_vk_channel(source.alpha);
    if (sa == 0) return destination;
    if (sa == 255) return kd_vk_pack(format, source);

    const int bgra =
        format == VK_FORMAT_B8G8R8A8_UNORM ||
        format == VK_FORMAT_B8G8R8A8_SRGB;

    const uint32_t d0 = destination & 0xffu;
    const uint32_t dg = (destination >> 8) & 0xffu;
    const uint32_t d2 = (destination >> 16) & 0xffu;
    const uint32_t da = (destination >> 24) & 0xffu;
    const uint32_t dr = bgra ? d2 : d0;
    const uint32_t db = bgra ? d0 : d2;
    const uint32_t sr = kd_vk_channel(source.red);
    const uint32_t sg = kd_vk_channel(source.green);
    const uint32_t sb = kd_vk_channel(source.blue);
    const uint32_t inv = 255u - sa;

    const uint32_t out_r = (sr * sa + dr * inv + 127u) / 255u;
    const uint32_t out_g = (sg * sa + dg * inv + 127u) / 255u;
    const uint32_t out_b = (sb * sa + db * inv + 127u) / 255u;
    const uint32_t out_a = sa + (da * inv + 127u) / 255u;

    if (bgra) {
        return out_b | (out_g << 8) | (out_r << 16) | (out_a << 24);
    }
    return out_r | (out_g << 8) | (out_b << 16) | (out_a << 24);
}

static uint32_t kd_vk_blend_premul_argb(
    VkFormat format,
    uint32_t destination,
    uint32_t source
) {
    const uint32_t sa = (source >> 24) & 0xffu;
    if (sa == 0) return destination;

    const uint32_t sr_p = (source >> 16) & 0xffu;
    const uint32_t sg_p = (source >> 8) & 0xffu;
    const uint32_t sb_p = source & 0xffu;

    kd_draw_color straight = {0};
    straight.alpha = (float)sa / 255.f;
    straight.red = (float)sr_p / (float)sa;
    straight.green = (float)sg_p / (float)sa;
    straight.blue = (float)sb_p / (float)sa;
    return kd_vk_blend(format, destination, straight);
}

static int kd_vk_create_upload(kd_vk_frame *frame) {
    if (!frame || !frame->device || !frame->physical_device ||
        !frame->width || !frame->height) return 0;

    frame->upload_size =
        (VkDeviceSize)frame->width *
        (VkDeviceSize)frame->height *
        4u;

    VkBufferCreateInfo buffer_info = {0};
    buffer_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    buffer_info.size = frame->upload_size;
    buffer_info.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(
        frame->device,
        &buffer_info,
        NULL,
        &frame->upload_buffer
    ) != VK_SUCCESS) {
        return 0;
    }

    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(
        frame->device,
        frame->upload_buffer,
        &requirements
    );

    uint32_t memory_type = kd_vk_find_memory_type(
        frame->physical_device,
        requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );
    if (memory_type == UINT32_MAX) return 0;

    VkMemoryAllocateInfo allocate = {0};
    allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate.allocationSize = requirements.size;
    allocate.memoryTypeIndex = memory_type;
    if (vkAllocateMemory(
        frame->device,
        &allocate,
        NULL,
        &frame->upload_memory
    ) != VK_SUCCESS) {
        return 0;
    }

    if (vkBindBufferMemory(
        frame->device,
        frame->upload_buffer,
        frame->upload_memory,
        0
    ) != VK_SUCCESS) {
        return 0;
    }

    if (vkMapMemory(
        frame->device,
        frame->upload_memory,
        0,
        frame->upload_size,
        0,
        &frame->upload_map
    ) != VK_SUCCESS) {
        frame->upload_map = NULL;
        return 0;
    }

    return 1;
}

static kd_status kd_vk_compose_pixels(
    kd_vk_frame *frame,
    const kd_draw_batch *batch,
    const kd_draw_rect *rects
) {
    uint32_t *pixels = (uint32_t *)frame->upload_map;
    const size_t pixel_count = (size_t)frame->width * frame->height;
    if (batch->clear_enabled) {
        const uint32_t clear = kd_vk_pack(frame->format, batch->clear);
        for (size_t i = 0; i < pixel_count; ++i) pixels[i] = clear;
    }

    for (uint32_t i = 0; i < batch->rect_count; ++i) {
        const kd_draw_rect *rect = &rects[i];
        kd_vk_texture *texture = NULL;
        if (rect->kind == KD_DRAW_IMAGE ||
            rect->kind == KD_DRAW_GLYPH) {
            texture = kd_vk_find_texture(frame, rect->resource_id);
            if (!texture || !texture->pixels ||
                !texture->width || !texture->height) {
                return KD_DRAW_UNSUPPORTED_OPERATION;
            }
        }

        for (uint32_t row = 0; row < rect->height; ++row) {
            uint32_t *pixel = pixels +
                (size_t)(rect->y + row) * frame->width +
                rect->x;

            for (uint32_t column = 0; column < rect->width; ++column) {
                if (rect->kind == KD_DRAW_CLEAR_RECT) {
                    pixel[column] = 0u;
                    continue;
                }

                if (rect->kind == KD_DRAW_IMAGE ||
                    rect->kind == KD_DRAW_GLYPH) {
                    float fx = ((float)column + 0.5f) /
                        (float)rect->width;
                    float fy = ((float)row + 0.5f) /
                        (float)rect->height;
                    float u = rect->u0 +
                        (rect->u1 - rect->u0) * fx;
                    float v = rect->v0 +
                        (rect->v1 - rect->v0) * fy;
                    if (u < 0.f) u = 0.f;
                    if (u > 1.f) u = 1.f;
                    if (v < 0.f) v = 0.f;
                    if (v > 1.f) v = 1.f;

                    uint32_t tx = (uint32_t)(
                        u * (float)texture->width
                    );
                    uint32_t ty = (uint32_t)(
                        v * (float)texture->height
                    );
                    if (tx >= texture->width) {
                        tx = texture->width - 1;
                    }
                    if (ty >= texture->height) {
                        ty = texture->height - 1;
                    }

                    uint32_t source =
                        texture->pixels[
                            (size_t)ty * texture->width + tx
                        ];

                    if (rect->kind == KD_DRAW_GLYPH) {
                        const uint32_t mask =
                            (source >> 24) & 0xffu;
                        kd_draw_color glyph = rect->color;
                        glyph.alpha *= (float)mask / 255.f;
                        pixel[column] = kd_vk_blend(
                            frame->format,
                            pixel[column],
                            glyph
                        );
                    } else {
                        // Skia snapshots use premultiplied ARGB.
                        pixel[column] =
                            kd_vk_blend_premul_argb(
                                frame->format,
                                pixel[column],
                                source
                            );
                    }
                    continue;
                }

                pixel[column] = kd_vk_blend(
                    frame->format,
                    pixel[column],
                    rect->color
                );
            }
        }
    }
    return KD_OK;
}

#endif

kd_status kd_vk_frame_create(kd_window *window, kd_vk_frame **out) {
    if (!window || !out) return KD_INVALID_ARGUMENT;
    *out = NULL;
#if !defined(_WIN32) && !defined(__linux__)
    return KD_UNSUPPORTED_PLATFORM;
#else
    kd_native_window_handles handles;
    uint32_t width = 0, height = 0;
    kd_status status = kd_window_get_native_handles(window, &handles);
    if (status != KD_OK) return status;
    status = kd_window_get_size(window, &width, &height);
    if (status != KD_OK) return status;
    if (!width || !height) return KD_SWAPCHAIN_OUT_OF_DATE;
#if defined(_WIN32)
    if (handles.platform != KD_WINDOW_PLATFORM_WIN32) return KD_UNSUPPORTED_PLATFORM;
    const char *platform_extension = VK_KHR_WIN32_SURFACE_EXTENSION_NAME;
#else
    if (handles.platform != KD_WINDOW_PLATFORM_XLIB) return KD_UNSUPPORTED_PLATFORM;
    const char *platform_extension = VK_KHR_XLIB_SURFACE_EXTENSION_NAME;
#endif
    kd_vk_frame *frame = calloc(1, sizeof(*frame));
    if (!frame) return KD_OUT_OF_MEMORY;
    status = KD_VULKAN_ERROR;
    const char *extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, platform_extension};
    VkApplicationInfo app = {0};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "Kotlin Display";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo instance_info = {0};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &app;
    instance_info.enabledExtensionCount = 2;
    instance_info.ppEnabledExtensionNames = extensions;
    VkResult result = vkCreateInstance(&instance_info, NULL, &frame->instance);
    if (result != VK_SUCCESS) {
        status = result == VK_ERROR_INCOMPATIBLE_DRIVER ? KD_VULKAN_UNAVAILABLE : KD_VULKAN_ERROR;
        goto fail;
    }
#if defined(_WIN32)
    VkWin32SurfaceCreateInfoKHR surface_info = {0};
    surface_info.sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR;
    surface_info.hinstance = (HINSTANCE)handles.display;
    surface_info.hwnd = (HWND)handles.window;
    result = vkCreateWin32SurfaceKHR(frame->instance, &surface_info, NULL, &frame->surface);
#else
    VkXlibSurfaceCreateInfoKHR surface_info = {0};
    surface_info.sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR;
    surface_info.dpy = (Display *)handles.display;
    surface_info.window = (Window)handles.window;
    result = vkCreateXlibSurfaceKHR(frame->instance, &surface_info, NULL, &frame->surface);
#endif
    if (result != VK_SUCCESS) { status = KD_SURFACE_UNAVAILABLE; goto fail; }

    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(frame->instance, &count, NULL) != VK_SUCCESS) goto fail;
    if (!count) { status = KD_NO_GRAPHICS_DEVICE; goto fail; }
    VkPhysicalDevice *devices = calloc(count, sizeof(*devices));
    if (!devices) { status = KD_OUT_OF_MEMORY; goto fail; }
    result = vkEnumeratePhysicalDevices(frame->instance, &count, devices);
    if (result != VK_SUCCESS) { free(devices); goto fail; }
    VkPhysicalDevice selected = VK_NULL_HANDLE;
    uint32_t queue_family = UINT32_MAX;
    kd_swapchain_choice choice = {0};
    VkSurfaceCapabilitiesKHR caps = {0};
    for (uint32_t i = 0; i < count && !selected; ++i) {
        if (!has_extension(devices[i])) continue;
        kd_swapchain_choice candidate;
        VkSurfaceCapabilitiesKHR candidate_caps;
        if (!choose_swapchain(devices[i], frame->surface, width, height, &candidate, &candidate_caps)) continue;
        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &families, NULL);
        if (!families) continue;
        VkQueueFamilyProperties *queues = calloc(families, sizeof(*queues));
        if (!queues) { status = KD_OUT_OF_MEMORY; break; }
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &families, queues);
        for (uint32_t j = 0; j < families; ++j) {
            VkBool32 present = VK_FALSE;
            if (!queues[j].queueCount || !(queues[j].queueFlags & VK_QUEUE_GRAPHICS_BIT)) continue;
            if (vkGetPhysicalDeviceSurfaceSupportKHR(devices[i], j, frame->surface, &present) == VK_SUCCESS && present) {
                selected = devices[i];
                queue_family = j;
                choice = candidate;
                caps = candidate_caps;
                break;
            }
        }
        free(queues);
    }
    free(devices);
    if (!selected) { if (status != KD_OUT_OF_MEMORY) status = KD_NO_GRAPHICS_DEVICE; goto fail; }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {0};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = queue_family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;
    const char *swapchain_extension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    VkDeviceCreateInfo device_info = {0};
    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = 1;
    device_info.ppEnabledExtensionNames = &swapchain_extension;
    frame->physical_device = selected;
    if (vkCreateDevice(selected, &device_info, NULL, &frame->device) != VK_SUCCESS) goto fail;
    vkGetDeviceQueue(frame->device, queue_family, 0, &frame->queue);
    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    const VkCompositeAlphaFlagBitsKHR alpha_options[] = {
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
    };
    for (uint32_t i = 0; i < 4; ++i) {
        if (caps.supportedCompositeAlpha & alpha_options[i]) { alpha = alpha_options[i]; break; }
    }
    VkSwapchainCreateInfoKHR swapchain_info = {0};
    swapchain_info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swapchain_info.surface = frame->surface;
    swapchain_info.minImageCount = choice.image_count;
    swapchain_info.imageFormat = (VkFormat)choice.surface_format.format;
    swapchain_info.imageColorSpace = (VkColorSpaceKHR)choice.surface_format.color_space;
    swapchain_info.imageExtent = (VkExtent2D){choice.width, choice.height};
    swapchain_info.imageArrayLayers = 1;
    swapchain_info.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    swapchain_info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    swapchain_info.preTransform = caps.currentTransform;
    swapchain_info.compositeAlpha = alpha;
    swapchain_info.presentMode = (VkPresentModeKHR)choice.present_mode;
    swapchain_info.clipped = VK_TRUE;
    result = vkCreateSwapchainKHR(frame->device, &swapchain_info, NULL, &frame->swapchain);
    if (result != VK_SUCCESS) { status = KD_SURFACE_UNAVAILABLE; goto fail; }
    if (vkGetSwapchainImagesKHR(frame->device, frame->swapchain, &frame->image_count, NULL) != VK_SUCCESS ||
        !frame->image_count) goto fail;
    frame->images = calloc(frame->image_count, sizeof(*frame->images));
    if (!frame->images) { status = KD_OUT_OF_MEMORY; goto fail; }
    if (vkGetSwapchainImagesKHR(frame->device, frame->swapchain, &frame->image_count, frame->images) != VK_SUCCESS) goto fail;
    VkCommandPoolCreateInfo pool_info = {0};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.queueFamilyIndex = queue_family;
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (vkCreateCommandPool(frame->device, &pool_info, NULL, &frame->pool) != VK_SUCCESS) goto fail;
    VkCommandBufferAllocateInfo command_info = {0};
    command_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    command_info.commandPool = frame->pool;
    command_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    command_info.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(frame->device, &command_info, &frame->command) != VK_SUCCESS) goto fail;
    VkSemaphoreCreateInfo semaphore_info = {0};
    semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    if (vkCreateSemaphore(frame->device, &semaphore_info, NULL, &frame->acquired) != VK_SUCCESS ||
        vkCreateSemaphore(frame->device, &semaphore_info, NULL, &frame->finished) != VK_SUCCESS) goto fail;
    VkFenceCreateInfo fence_info = {0};
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (vkCreateFence(frame->device, &fence_info, NULL, &frame->fence) != VK_SUCCESS) goto fail;
    frame->format = swapchain_info.imageFormat;
    frame->width = choice.width;
    frame->height = choice.height;
    if (!kd_vk_supported_upload_format(frame->format)) {
        status = KD_DRAW_UNSUPPORTED_OPERATION;
        goto fail;
    }
    if (!kd_vk_create_upload(frame)) {
        status = KD_OUT_OF_MEMORY;
        goto fail;
    }
    *out = frame;
    return KD_OK;
fail:
    kd_vk_frame_destroy(frame);
    return status;
#endif
}

kd_status kd_vk_frame_upload_texture(
    kd_vk_frame *frame,
    uint32_t texture_id,
    uint32_t width,
    uint32_t height,
    const uint32_t *argb
) {
    if (!frame || !texture_id || !width || !height || !argb) {
        return KD_INVALID_ARGUMENT;
    }
    if ((size_t)width > SIZE_MAX / (size_t)height ||
        (size_t)width * (size_t)height >
            SIZE_MAX / sizeof(uint32_t)) {
        return KD_INVALID_ARGUMENT;
    }

    const size_t pixel_count =
        (size_t)width * (size_t)height;
    uint32_t *copy =
        (uint32_t *)malloc(pixel_count * sizeof(uint32_t));
    if (!copy) return KD_OUT_OF_MEMORY;
    memcpy(
        copy,
        argb,
        pixel_count * sizeof(uint32_t)
    );

    kd_vk_texture *entry =
        kd_vk_find_texture(frame, texture_id);
    if (!entry) {
        entry = (kd_vk_texture *)calloc(1, sizeof(*entry));
        if (!entry) {
            free(copy);
            return KD_OUT_OF_MEMORY;
        }
        entry->id = texture_id;
        entry->next = frame->textures;
        frame->textures = entry;
    }

    free(entry->pixels);
    entry->pixels = copy;
    entry->width = width;
    entry->height = height;
    return KD_OK;
}

void kd_vk_frame_release_texture(
    kd_vk_frame *frame,
    uint32_t texture_id
) {
    if (!frame || !texture_id) return;
    kd_vk_texture **link = &frame->textures;
    while (*link) {
        kd_vk_texture *entry = *link;
        if (entry->id == texture_id) {
            *link = entry->next;
            free(entry->pixels);
            free(entry);
            return;
        }
        link = &entry->next;
    }
}

kd_status kd_vk_frame_present_commands(
    kd_vk_frame *frame,
    const kd_draw_command *commands,
    uint32_t count
) {
    if (!frame || !commands || !count || count > KD_DRAW_MAX_COMMANDS) {
        return KD_INVALID_ARGUMENT;
    }
#if !defined(_WIN32) && !defined(__linux__)
    return KD_UNSUPPORTED_PLATFORM;
#else
    kd_draw_rect *rects = calloc(count, sizeof(*rects));
    if (!rects) return KD_OUT_OF_MEMORY;

    kd_draw_batch batch = {0};
    kd_draw_result prepared = kd_prepare_draw_batch(
        frame->width,
        frame->height,
        commands,
        count,
        rects,
        count,
        &batch
    );
    if (prepared != KD_DRAW_OK) {
        free(rects);
        return prepared == KD_DRAW_UNSUPPORTED
            ? KD_DRAW_UNSUPPORTED_OPERATION
            : KD_INVALID_ARGUMENT;
    }

    if (!frame->upload_map ||
        !kd_vk_supported_upload_format(frame->format)) {
        free(rects);
        return KD_DRAW_UNSUPPORTED_OPERATION;
    }

    kd_status composed =
        kd_vk_compose_pixels(frame, &batch, rects);
    if (composed != KD_OK) {
        free(rects);
        return composed;
    }

    if (vkWaitForFences(
        frame->device,
        1,
        &frame->fence,
        VK_TRUE,
        UINT64_MAX
    ) != VK_SUCCESS) {
        free(rects);
        return KD_VULKAN_ERROR;
    }

    uint32_t index = 0;
    VkResult result = vkAcquireNextImageKHR(
        frame->device,
        frame->swapchain,
        UINT64_MAX,
        frame->acquired,
        VK_NULL_HANDLE,
        &index
    );
    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        free(rects);
        return KD_SWAPCHAIN_OUT_OF_DATE;
    }
    if ((result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) ||
        index >= frame->image_count) {
        free(rects);
        return KD_VULKAN_ERROR;
    }
    const int needs_recreate = result == VK_SUBOPTIMAL_KHR;

    kd_status status = KD_VULKAN_ERROR;
    if (vkResetCommandBuffer(frame->command, 0) != VK_SUCCESS) goto cleanup;

    VkCommandBufferBeginInfo begin = {0};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(frame->command, &begin) != VK_SUCCESS) goto cleanup;

    VkImageMemoryBarrier barrier = {0};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = frame->images[index];
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        frame->command,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0,
        NULL,
        0,
        NULL,
        1,
        &barrier
    );

    VkBufferImageCopy copy = {0};
    copy.bufferOffset = 0;
    copy.bufferRowLength = 0;
    copy.bufferImageHeight = 0;
    copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copy.imageSubresource.mipLevel = 0;
    copy.imageSubresource.baseArrayLayer = 0;
    copy.imageSubresource.layerCount = 1;
    copy.imageExtent.width = frame->width;
    copy.imageExtent.height = frame->height;
    copy.imageExtent.depth = 1;

    vkCmdCopyBufferToImage(
        frame->command,
        frame->upload_buffer,
        frame->images[index],
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &copy
    );

    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = 0;
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    vkCmdPipelineBarrier(
        frame->command,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        0,
        0,
        NULL,
        0,
        NULL,
        1,
        &barrier
    );

    if (vkEndCommandBuffer(frame->command) != VK_SUCCESS) goto cleanup;

    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo submit = {0};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &frame->acquired;
    submit.pWaitDstStageMask = &wait_stage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &frame->command;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &frame->finished;

    if (vkResetFences(frame->device, 1, &frame->fence) != VK_SUCCESS) goto cleanup;
    if (vkQueueSubmit(frame->queue, 1, &submit, frame->fence) != VK_SUCCESS) goto cleanup;

    VkPresentInfoKHR present = {0};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &frame->finished;
    present.swapchainCount = 1;
    present.pSwapchains = &frame->swapchain;
    present.pImageIndices = &index;

    result = vkQueuePresentKHR(frame->queue, &present);
    if (result == VK_SUCCESS) {
        status = needs_recreate ? KD_SWAPCHAIN_OUT_OF_DATE : KD_OK;
    } else if (
        result == VK_ERROR_OUT_OF_DATE_KHR ||
        result == VK_SUBOPTIMAL_KHR
    ) {
        status = KD_SWAPCHAIN_OUT_OF_DATE;
    }

cleanup:
    if (status == KD_VULKAN_ERROR) vkDeviceWaitIdle(frame->device);
    free(rects);
    return status;
#endif
}

kd_status kd_vk_frame_present(kd_vk_frame *frame, float red, float green, float blue, float alpha) {
    kd_draw_command clear = {0};
    clear.kind = KD_DRAW_CLEAR;
    clear.color = (kd_draw_color){red, green, blue, alpha};
    return kd_vk_frame_present_commands(frame, &clear, 1);
}
