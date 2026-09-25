#include "kotlin_display.h"
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

typedef struct kd_gdi_cache {
    HWND window;
    HDC buffer;
    HBITMAP bitmap;
    HGDIOBJ previous;
    uint32_t *pixels;
    uint32_t width, height;
    kd_draw_rect *rects;
    uint32_t capacity;
} kd_gdi_cache;

static kd_gdi_cache cache;
static int cache_registered;

static void kd_gdi_cache_destroy(void) {
    if (cache.buffer && cache.previous) SelectObject(cache.buffer, cache.previous);
    if (cache.bitmap) DeleteObject(cache.bitmap);
    if (cache.buffer) DeleteDC(cache.buffer);
    free(cache.rects);
    cache = (kd_gdi_cache){0};
}

static unsigned char kd_channel(float value) {
    if (value <= 0.f) return 0;
    if (value >= 1.f) return 255;
    return (unsigned char)(value * 255.f + .5f);
}

static uint32_t kd_pack(kd_draw_color color) {
    const uint32_t r = kd_channel(color.red);
    const uint32_t g = kd_channel(color.green);
    const uint32_t b = kd_channel(color.blue);
    const uint32_t a = kd_channel(color.alpha);
    return b | (g << 8) | (r << 16) | (a << 24);
}

static uint32_t kd_blend(uint32_t destination, kd_draw_color source) {
    const uint32_t sa = kd_channel(source.alpha);
    if (sa == 0) return destination;
    if (sa == 255) return kd_pack(source);

    const uint32_t inv = 255u - sa;
    const uint32_t sr = kd_channel(source.red);
    const uint32_t sg = kd_channel(source.green);
    const uint32_t sb = kd_channel(source.blue);

    const uint32_t db = destination & 0xffu;
    const uint32_t dg = (destination >> 8) & 0xffu;
    const uint32_t dr = (destination >> 16) & 0xffu;
    const uint32_t da = (destination >> 24) & 0xffu;

    const uint32_t out_r = (sr * sa + dr * inv + 127u) / 255u;
    const uint32_t out_g = (sg * sa + dg * inv + 127u) / 255u;
    const uint32_t out_b = (sb * sa + db * inv + 127u) / 255u;
    const uint32_t out_a = sa + (da * inv + 127u) / 255u;

    return out_b | (out_g << 8) | (out_r << 16) | (out_a << 24);
}

static int kd_gdi_cache_prepare(
    HWND window,
    HDC target,
    uint32_t width,
    uint32_t height,
    uint32_t count
) {
    if (!cache_registered) {
        if (atexit(kd_gdi_cache_destroy) != 0) return 0;
        cache_registered = 1;
    }

    if (count > cache.capacity) {
        kd_draw_rect *resized = (kd_draw_rect *)realloc(
            cache.rects,
            (size_t)count * sizeof(*resized)
        );
        if (!resized) return 0;
        cache.rects = resized;
        cache.capacity = count;
    }

    if (cache.window == window &&
        cache.width == width &&
        cache.height == height &&
        cache.bitmap &&
        cache.pixels) {
        return 1;
    }

    if (cache.buffer && cache.previous) SelectObject(cache.buffer, cache.previous);
    if (cache.bitmap) DeleteObject(cache.bitmap);
    if (cache.buffer) DeleteDC(cache.buffer);

    cache.buffer = NULL;
    cache.bitmap = NULL;
    cache.previous = NULL;
    cache.pixels = NULL;
    cache.window = NULL;

    cache.buffer = CreateCompatibleDC(target);
    if (!cache.buffer) return 0;

    BITMAPINFO info;
    memset(&info, 0, sizeof(info));
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = (LONG)width;
    info.bmiHeader.biHeight = -(LONG)height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;

    void *bits = NULL;
    cache.bitmap = CreateDIBSection(
        target,
        &info,
        DIB_RGB_COLORS,
        &bits,
        NULL,
        0
    );
    if (!cache.bitmap || !bits) {
        if (cache.bitmap) DeleteObject(cache.bitmap);
        DeleteDC(cache.buffer);
        cache.bitmap = NULL;
        cache.buffer = NULL;
        return 0;
    }

    cache.previous = SelectObject(cache.buffer, cache.bitmap);
    if (!cache.previous || cache.previous == HGDI_ERROR) {
        DeleteObject(cache.bitmap);
        DeleteDC(cache.buffer);
        cache.bitmap = NULL;
        cache.buffer = NULL;
        cache.previous = NULL;
        return 0;
    }

    cache.pixels = (uint32_t *)bits;
    cache.window = window;
    cache.width = width;
    cache.height = height;
    return 1;
}

static void kd_gdi_clear(uint32_t width, uint32_t height, kd_draw_color color) {
    const uint32_t packed = kd_pack(color);
    const size_t total = (size_t)width * (size_t)height;
    for (size_t i = 0; i < total; ++i) cache.pixels[i] = packed;
}

static void kd_gdi_rect(uint32_t width, const kd_draw_rect *rect) {
    if (!rect || !rect->width || !rect->height) return;
    for (uint32_t row = 0; row < rect->height; ++row) {
        uint32_t *pixel = cache.pixels +
            (size_t)(rect->y + row) * width +
            rect->x;
        for (uint32_t column = 0; column < rect->width; ++column) {
            pixel[column] = rect->kind == KD_DRAW_CLEAR_RECT
                ? 0u
                : kd_blend(pixel[column], rect->color);
        }
    }
}
#endif

kd_status kd_gdi_frame_present_commands(
    kd_window *window,
    const kd_draw_command *commands,
    uint32_t count
) {
    if (!window || !commands || !count || count > KD_DRAW_MAX_COMMANDS) {
        return KD_INVALID_ARGUMENT;
    }
#if !defined(_WIN32)
    return KD_UNSUPPORTED_PLATFORM;
#else
    kd_native_window_handles handles = {0};
    kd_status status = kd_window_get_native_handles(window, &handles);
    if (status != KD_OK) return status;
    if (handles.platform != KD_WINDOW_PLATFORM_WIN32 || !handles.window) {
        return KD_UNSUPPORTED_PLATFORM;
    }

    uint32_t width = 0;
    uint32_t height = 0;
    status = kd_window_get_size(window, &width, &height);
    if (status != KD_OK) return status;
    if (!width || !height) return KD_SWAPCHAIN_OUT_OF_DATE;

    HWND hwnd = (HWND)(uintptr_t)handles.window;
    HDC target = GetDC(hwnd);
    if (!target) return KD_WINDOW_UNAVAILABLE;

    if (!kd_gdi_cache_prepare(hwnd, target, width, height, count)) {
        ReleaseDC(hwnd, target);
        return KD_OUT_OF_MEMORY;
    }

    kd_draw_batch batch = {0};
    kd_draw_result prepared = kd_prepare_draw_batch(
        width,
        height,
        commands,
        count,
        cache.rects,
        cache.capacity,
        &batch
    );
    if (prepared != KD_DRAW_OK) {
        ReleaseDC(hwnd, target);
        return prepared == KD_DRAW_UNSUPPORTED
            ? KD_DRAW_UNSUPPORTED_OPERATION
            : KD_INVALID_ARGUMENT;
    }

    if (batch.clear_enabled) {
        kd_gdi_clear(width, height, batch.clear);
    }
    for (uint32_t i = 0; i < batch.rect_count; ++i) {
        kd_gdi_rect(width, &cache.rects[i]);
    }

    int ok = BitBlt(
        target,
        0,
        0,
        (int)width,
        (int)height,
        cache.buffer,
        0,
        0,
        SRCCOPY
    ) != 0;

    ReleaseDC(hwnd, target);
    return ok ? KD_OK : KD_WINDOW_UNAVAILABLE;
#endif
}
