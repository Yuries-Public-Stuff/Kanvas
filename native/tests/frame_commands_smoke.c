#include "kotlin_display.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    kd_draw_command clear = {0};
    clear.kind = KD_DRAW_CLEAR;
    clear.color = (kd_draw_color){0.1f, 0.2f, 0.3f, 1.0f};
    assert(kd_abi_version() == KD_ABI_VERSION);
    assert(kd_vk_frame_present_commands(NULL, &clear, 1) == KD_INVALID_ARGUMENT);
    assert(kd_vk_frame_present_commands(NULL, NULL, 0) == KD_INVALID_ARGUMENT);
    assert(kd_vk_frame_present(NULL, 0, 0, 0, 1) == KD_INVALID_ARGUMENT);
    assert(kd_gdi_frame_present_commands(NULL, &clear, 1) == KD_INVALID_ARGUMENT);
    assert(kd_gdi_frame_present_commands(NULL, NULL, 0) == KD_INVALID_ARGUMENT);
    puts("Vulkan and GDI command submission ABI argument checks passed.");
    return 0;
}
