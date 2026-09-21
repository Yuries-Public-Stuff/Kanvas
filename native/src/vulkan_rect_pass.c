#include "vulkan_rect_pass.h"
#include <string.h>

void kd_vk_rect_pass_destroy(VkDevice device, kd_vk_rect_pass *pass) {
    if (!pass || !device) return;
    if (pass->framebuffer) vkDestroyFramebuffer(device, pass->framebuffer, NULL);
    if (pass->image_view) vkDestroyImageView(device, pass->image_view, NULL);
    if (pass->render_pass) vkDestroyRenderPass(device, pass->render_pass, NULL);
    memset(pass, 0, sizeof(*pass));
}

int kd_vk_record_rects(VkDevice device, VkCommandBuffer command, VkImage image,
                       VkFormat format, VkExtent2D extent, const kd_draw_rect *rects,
                       uint32_t count, kd_vk_rect_pass *out) {
    if (!device || !command || !image || !out || (count && !rects)) return 0;
    memset(out, 0, sizeof(*out));
    if (!count) return 1;

    VkAttachmentDescription attachment = {0};
    attachment.format = format;
    attachment.samples = VK_SAMPLE_COUNT_1_BIT;
    attachment.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
    attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachment.initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    attachment.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkAttachmentReference reference = {0};
    reference.attachment = 0;
    reference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    VkSubpassDescription subpass = {0};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &reference;
    VkRenderPassCreateInfo render_info = {0};
    render_info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    render_info.attachmentCount = 1;
    render_info.pAttachments = &attachment;
    render_info.subpassCount = 1;
    render_info.pSubpasses = &subpass;
    if (vkCreateRenderPass(device, &render_info, NULL, &out->render_pass) != VK_SUCCESS) goto failed;

    VkImageViewCreateInfo view_info = {0};
    view_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    view_info.image = image;
    view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
    view_info.format = format;
    view_info.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    view_info.subresourceRange.levelCount = 1;
    view_info.subresourceRange.layerCount = 1;
    if (vkCreateImageView(device, &view_info, NULL, &out->image_view) != VK_SUCCESS) goto failed;

    VkFramebufferCreateInfo framebuffer_info = {0};
    framebuffer_info.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    framebuffer_info.renderPass = out->render_pass;
    framebuffer_info.attachmentCount = 1;
    framebuffer_info.pAttachments = &out->image_view;
    framebuffer_info.width = extent.width;
    framebuffer_info.height = extent.height;
    framebuffer_info.layers = 1;
    if (vkCreateFramebuffer(device, &framebuffer_info, NULL, &out->framebuffer) != VK_SUCCESS) goto failed;

    VkRenderPassBeginInfo begin = {0};
    begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    begin.renderPass = out->render_pass;
    begin.framebuffer = out->framebuffer;
    begin.renderArea.extent = extent;
    vkCmdBeginRenderPass(command, &begin, VK_SUBPASS_CONTENTS_INLINE);
    for (uint32_t i = 0; i < count; ++i) {
        const kd_draw_rect *rect = &rects[i];
        VkClearAttachment clear = {0};
        clear.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        clear.colorAttachment = 0;
        clear.clearValue.color.float32[0] = rect->color.red;
        clear.clearValue.color.float32[1] = rect->color.green;
        clear.clearValue.color.float32[2] = rect->color.blue;
        clear.clearValue.color.float32[3] = rect->color.alpha;
        VkClearRect area = {0};
        area.rect.offset.x = (int32_t)rect->x;
        area.rect.offset.y = (int32_t)rect->y;
        area.rect.extent.width = rect->width;
        area.rect.extent.height = rect->height;
        area.layerCount = 1;
        vkCmdClearAttachments(command, 1, &clear, 1, &area);
    }
    vkCmdEndRenderPass(command);
    return 1;

failed:
    kd_vk_rect_pass_destroy(device, out);
    return 0;
}
