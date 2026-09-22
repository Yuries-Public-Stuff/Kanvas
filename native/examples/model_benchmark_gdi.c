#include "kotlin_display.h"
#include "torus_mesh.h"
#include "model_benchmark_telemetry.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <math.h>
#include <float.h>
#include <stdio.h>
#include <string.h>

typedef struct kd_projected {
    float x, y, inv_z;
    float r, g, b;
} kd_projected;

typedef struct kd_software_frame {
    HDC memory_dc;
    HBITMAP bitmap;
    HGDIOBJ previous;
    uint32_t *pixels;
    float *depth;
    uint32_t width, height;
} kd_software_frame;

static double timer_seconds(void) {
    static double frequency;
    LARGE_INTEGER value;
    if (!frequency) {
        QueryPerformanceFrequency(&value);
        frequency = (double)value.QuadPart;
    }
    QueryPerformanceCounter(&value);
    return (double)value.QuadPart / frequency;
}

static void dispose_frame(kd_software_frame *frame) {
    if (frame->memory_dc && frame->previous) SelectObject(frame->memory_dc, frame->previous);
    if (frame->bitmap) DeleteObject(frame->bitmap);
    if (frame->memory_dc) DeleteDC(frame->memory_dc);
    free(frame->depth);
    *frame = (kd_software_frame){0};
}

static int ensure_frame(kd_software_frame *frame, HWND hwnd, uint32_t width, uint32_t height) {
    if (width == frame->width && height == frame->height && frame->pixels && frame->depth) return 1;
    dispose_frame(frame);
    if (!width || !height || width > 4096 || height > 2160) return 0;
    HDC target = GetDC(hwnd);
    if (!target) return 0;
    frame->memory_dc = CreateCompatibleDC(target);
    BITMAPINFO info = {0};
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = (LONG)width;
    info.bmiHeader.biHeight = -(LONG)height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    frame->bitmap = CreateDIBSection(target, &info, DIB_RGB_COLORS, (void **)&frame->pixels, NULL, 0);
    ReleaseDC(hwnd, target);
    if (!frame->memory_dc || !frame->bitmap || !frame->pixels) { dispose_frame(frame); return 0; }
    frame->previous = SelectObject(frame->memory_dc, frame->bitmap);
    if (!frame->previous || frame->previous == HGDI_ERROR) { dispose_frame(frame); return 0; }
    frame->depth = (float *)malloc((size_t)width * height * sizeof(float));
    if (!frame->depth) { dispose_frame(frame); return 0; }
    frame->width = width;
    frame->height = height;
    return 1;
}

static uint32_t encode_color(float r, float g, float b) {
    r = fminf(1.f, fmaxf(0.f, r));
    g = fminf(1.f, fmaxf(0.f, g));
    b = fminf(1.f, fmaxf(0.f, b));
    return ((uint32_t)(r * 255.f + .5f) << 16) |
           ((uint32_t)(g * 255.f + .5f) << 8) |
            (uint32_t)(b * 255.f + .5f);
}

static float edge(float ax, float ay, float bx, float by, float px, float py) {
    return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
}

static void draw_triangle(kd_software_frame *frame, const kd_projected *a,
                          const kd_projected *b, const kd_projected *c) {
    const float area = edge(a->x, a->y, b->x, b->y, c->x, c->y);
    if (fabsf(area) < 0.00001f) return;
    int left = (int)floorf(fminf(a->x, fminf(b->x, c->x)));
    int right = (int)ceilf(fmaxf(a->x, fmaxf(b->x, c->x)));
    int top = (int)floorf(fminf(a->y, fminf(b->y, c->y)));
    int bottom = (int)ceilf(fmaxf(a->y, fmaxf(b->y, c->y)));
    if (right <= 0 || bottom <= 0 || left >= (int)frame->width || top >= (int)frame->height) return;
    if (left < 0) left = 0;
    if (top < 0) top = 0;
    if (right > (int)frame->width) right = (int)frame->width;
    if (bottom > (int)frame->height) bottom = (int)frame->height;
    const float inverse = 1.f / area;
    for (int y = top; y < bottom; ++y) {
        const float py = y + 0.5f;
        const size_t row = (size_t)y * frame->width;
        for (int x = left; x < right; ++x) {
            const float px = x + 0.5f;
            const float w0 = edge(b->x, b->y, c->x, c->y, px, py) * inverse;
            const float w1 = edge(c->x, c->y, a->x, a->y, px, py) * inverse;
            const float w2 = 1.f - w0 - w1;
            if (w0 < 0.f || w1 < 0.f || w2 < 0.f) continue;
            const float inv_z = w0 * a->inv_z + w1 * b->inv_z + w2 * c->inv_z;
            const size_t index = row + (size_t)x;
            if (inv_z <= frame->depth[index]) continue;
            frame->depth[index] = inv_z;
            frame->pixels[index] = encode_color(w0 * a->r + w1 * b->r + w2 * c->r,
                                                w0 * a->g + w1 * b->g + w2 * c->g,
                                                w0 * a->b + w1 * b->b + w2 * c->b);
        }
    }
}

static void project_instance(kd_projected *output, const kd_bench_mesh *mesh,
                             uint32_t width, uint32_t height, double seconds, uint32_t instance) {
    const float angle = (float)(seconds * (24. + (instance % 5u) * 8.) * KD_BENCH_PI / 180.);
    const float co = cosf(angle), si = sinf(angle), t = 1.f - co;
    const float x = .6f / 1.2328828f, y = 1.f / 1.2328828f, z = .4f / 1.2328828f;
    const float r11 = t*x*x + co, r12 = t*x*y + si*z, r13 = t*x*z - si*y;
    const float r21 = t*x*y - si*z, r22 = t*y*y + co, r23 = t*y*z + si*x;
    const float r31 = t*x*z + si*y, r32 = t*y*z - si*x, r33 = t*z*z + co;
    const float tx = ((int)(instance % 6u) - 2.5f) * 2.7f;
    const float ty = ((int)(instance / 6u) - 2.5f) * 2.5f;
    const float scale = height * .625f;
    const float red = .20f + (instance % 3u) * .23f;
    const float green = .37f + (instance % 4u) * .13f;
    const float blue = .77f - (instance % 3u) * .18f;
    for (uint32_t i = 0; i < KD_BENCH_VERTICES; ++i) {
        const kd_bench_vertex *v = &mesh->vertices[i];
        const float px = v->x*r11 + v->y*r21 + v->z*r31 + tx;
        const float py = v->x*r12 + v->y*r22 + v->z*r32 + ty;
        const float pz = 19.f + v->x*r13 + v->y*r23 + v->z*r33;
        const float nx = v->nx*r11 + v->ny*r21 + v->nz*r31;
        const float ny = v->nx*r12 + v->ny*r22 + v->nz*r32;
        const float nz = v->nx*r13 + v->ny*r23 + v->nz*r33;
        const float diffuse = fmaxf(0.f, .30f*nx + .50f*ny + .80f*nz);
        const float light = .20f + .80f * diffuse;
        const float inv_z = 1.f / pz;
        output[i].x = width * .5f + scale * px * inv_z;
        output[i].y = height * .5f - scale * py * inv_z;
        output[i].inv_z = inv_z;
        output[i].r = red * light;
        output[i].g = green * light;
        output[i].b = blue * light;
    }
}

int main(void) {
    kd_bench_mesh mesh = {0};
    kd_projected *projected = NULL;
    kd_window *window = NULL;
    kd_software_frame display = {0};
    kd_model_log telemetry = {0};
    HWND hwnd = NULL;
    int result = 1;
    if (!kd_bench_mesh_create(&mesh)) goto cleanup;
    projected = (kd_projected *)malloc(sizeof(*projected) * KD_BENCH_VERTICES);
    if (!projected) goto cleanup;
    if (kd_window_create(1280, 720, "Queue Desk | GDI software triangle models", &window) != KD_OK) goto cleanup;
    kd_native_window_handles handles = {0};
    if (kd_window_get_native_handles(window, &handles) != KD_OK ||
        handles.platform != KD_WINDOW_PLATFORM_WIN32 || !handles.window) goto cleanup;
    hwnd = (HWND)(uintptr_t)handles.window;
    if (!kd_model_log_open(&telemetry, "gdi")) goto cleanup;
    const double start = timer_seconds();
    double report_at = start, call_sum = 0.;
    unsigned long long count = 0, total = 0;
    int closed = 0;
    result = 0;
    while (kd_window_poll(window, &closed) == KD_OK && !closed) {
        uint32_t width = 0, height = 0;
        if (kd_window_get_size(window, &width, &height) != KD_OK) { result = 1; break; }
        if (!width || !height) { Sleep(10); continue; }
        if (!ensure_frame(&display, hwnd, width, height)) { result = 1; break; }
        const double begun = timer_seconds();
        const size_t pixels = (size_t)width * height;
        for (size_t i = 0; i < pixels; ++i) {
            display.pixels[i] = 0x060911u;
            display.depth[i] = 0.f;
        }
        const double elapsed = begun - start;
        for (uint32_t instance = 0; instance < KD_BENCH_INSTANCES; ++instance) {
            project_instance(projected, &mesh, width, height, elapsed, instance);
            for (uint32_t index = 0; index < KD_BENCH_INDICES; index += 3) {
                draw_triangle(&display, &projected[mesh.indices[index]],
                              &projected[mesh.indices[index + 1]], &projected[mesh.indices[index + 2]]);
            }
        }
        HDC target = GetDC(hwnd);
        const int shown = target && BitBlt(target, 0, 0, (int)width, (int)height,
                                           display.memory_dc, 0, 0, SRCCOPY);
        if (target) ReleaseDC(hwnd, target);
        if (!shown) { result = 1; break; }
        const double finished = timer_seconds();
        const uint64_t elapsed_ns = (uint64_t)((finished - start) * 1000000000.0 + .5);
        const uint64_t call_ns = (uint64_t)((finished - begun) * 1000000000.0 + .5);
        if (!kd_model_log_record(&telemetry, elapsed_ns, call_ns,
                                 KD_BENCH_TRIANGLES_PER_FRAME, KD_BENCH_INSTANCES, width, height)) {
            result = 1;
            break;
        }
        count++; total++;
        call_sum += (finished - begun) * 1000.;
        if (finished - report_at >= 1.) {
            const double rate = count / (finished - report_at);
            const double mean = call_sum / count;
            char title[256];
            snprintf(title, sizeof(title), "Queue Desk | GDI CPU 3D | %u models | %u triangles | %.1f calls/s | %.2f ms/call",
                     KD_BENCH_INSTANCES, KD_BENCH_TRIANGLES_PER_FRAME, rate, mean);
            SetWindowTextA(hwnd, title);
            printf("%.3f,%llu,%.2f,%.3f\n", finished - start, total, rate, mean);
            count = 0; call_sum = 0.; report_at = finished;
        }
    }
cleanup:
    kd_model_log_close(&telemetry);
    dispose_frame(&display);
    free(projected);
    kd_bench_mesh_free(&mesh);
    kd_window_destroy(window);
    if (result) fputs("GDI software mesh renderer failed.\n", stderr);
    return result;
}
#else
#include <stdio.h>
int main(void) {
    fputs("GDI software model benchmark requires Windows.\n", stderr);
    return 1;
}
#endif
