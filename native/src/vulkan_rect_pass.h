#ifndef KD_VULKAN_RECT_PASS_H
#define KD_VULKAN_RECT_PASS_H

#include "kd_draw_batch.h"
#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#ifndef VK_USE_PLATFORM_WIN32_KHR
#define VK_USE_PLATFORM_WIN32_KHR
#endif
#elif defined(__linux__)
#include <X11/Xlib.h>
#ifndef VK_USE_PLATFORM_XLIB_KHR
#define VK_USE_PLATFORM_XLIB_KHR
#endif
#endif
#include <vulkan/vulkan.h>

typedef struct kd_vk_rect_pass {
    VkRenderPass render_pass;
    VkImageView image_view;
    VkFramebuffer framebuffer;
} kd_vk_rect_pass;

int kd_vk_record_rects(VkDevice device, VkCommandBuffer command, VkImage image,
                       VkFormat format, VkExtent2D extent, const kd_draw_rect *rects,
                       uint32_t count, kd_vk_rect_pass *out);
void kd_vk_rect_pass_destroy(VkDevice device, kd_vk_rect_pass *pass);

#endif
