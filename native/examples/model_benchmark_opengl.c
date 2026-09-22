#include "kotlin_display.h"
#include "torus_mesh.h"
#include "model_benchmark_telemetry.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <GL/gl.h>
#include <math.h>
#include <stdio.h>
#include <stdint.h>

static GLuint create_model(const kd_bench_mesh *mesh) {
    GLuint list = glGenLists(1);
    if (!list) return 0;
    glNewList(list, GL_COMPILE);
    glBegin(GL_TRIANGLES);
    for (uint32_t index = 0; index < KD_BENCH_INDICES; ++index) {
        const kd_bench_vertex *p = &mesh->vertices[mesh->indices[index]];
        glNormal3f(p->nx, p->ny, p->nz);
        glVertex3f(p->x, p->y, p->z);
    }
    glEnd();
    glEndList();
    return list;
}

static double timer_seconds(void) {
    static double frequency = 0.0;
    LARGE_INTEGER value;
    if (frequency == 0.0) {
        QueryPerformanceFrequency(&value);
        frequency = (double)value.QuadPart;
    }
    QueryPerformanceCounter(&value);
    return (double)value.QuadPart / frequency;
}

int main(void) {
    kd_window *window = NULL;
    HDC dc = NULL;
    HWND hwnd = NULL;
    HGLRC context = NULL;
    GLuint gpu_mesh = 0;
    kd_bench_mesh mesh = {0};
    kd_model_log telemetry = {0};
    int exit_code = 1;
    if (!kd_bench_mesh_create(&mesh)) goto cleanup;
    if (kd_window_create(1280, 720, "Queue Desk | OpenGL triangle models", &window) != KD_OK) {
        fputs("Could not create benchmark window.\n", stderr);
        goto cleanup;
    }
    kd_native_window_handles handles = {0};
    if (kd_window_get_native_handles(window, &handles) != KD_OK ||
        handles.platform != KD_WINDOW_PLATFORM_WIN32 || !handles.window) goto cleanup;
    hwnd = (HWND)(uintptr_t)handles.window;
    dc = GetDC(hwnd);
    if (!dc) goto cleanup;
    PIXELFORMATDESCRIPTOR pixel = {0};
    pixel.nSize = sizeof(pixel);
    pixel.nVersion = 1;
    pixel.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pixel.iPixelType = PFD_TYPE_RGBA;
    pixel.cColorBits = 32;
    pixel.cDepthBits = 24;
    pixel.iLayerType = PFD_MAIN_PLANE;
    int chosen = ChoosePixelFormat(dc, &pixel);
    if (!chosen || !SetPixelFormat(dc, chosen, &pixel)) goto cleanup;
    context = wglCreateContext(dc);
    if (!context || !wglMakeCurrent(dc, context)) goto cleanup;
    GLint depth_bits = 0;
    glGetIntegerv(GL_DEPTH_BITS, &depth_bits);
    if (depth_bits < 16) {
        fprintf(stderr, "Depth buffer required, received %d bits.\n", depth_bits);
        goto cleanup;
    }
    gpu_mesh = create_model(&mesh);
    kd_bench_mesh_free(&mesh);
    if (!gpu_mesh) goto cleanup;
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_LIGHTING);
    glEnable(GL_LIGHT0);
    glEnable(GL_NORMALIZE);
    glEnable(GL_COLOR_MATERIAL);
    glColorMaterial(GL_FRONT_AND_BACK, GL_AMBIENT_AND_DIFFUSE);
    glDisable(GL_CULL_FACE);
    glShadeModel(GL_SMOOTH);
    const GLfloat light[] = {3.f, 5.f, 8.f, 1.f};
    const GLfloat ambient[] = {0.17f, 0.19f, 0.24f, 1.f};
    glLightfv(GL_LIGHT0, GL_POSITION, light);
    glLightModelfv(GL_LIGHT_MODEL_AMBIENT, ambient);
    glClearColor(0.025f, 0.035f, 0.065f, 1.f);
    if (!kd_model_log_open(&telemetry, "opengl")) {
        fputs("Could not open model telemetry file.\n", stderr);
        goto cleanup;
    }
    const double begin = timer_seconds();
    double report_at = begin;
    double render_ms_sum = 0.0;
    unsigned long long frames = 0, total_frames = 0;
    int closed = 0;
    exit_code = 0;
    while (kd_window_poll(window, &closed) == KD_OK && !closed) {
        uint32_t width = 0, height = 0;
        if (kd_window_get_size(window, &width, &height) != KD_OK) { exit_code = 1; break; }
        if (!width || !height) { Sleep(10); continue; }
        const double started = timer_seconds();
        glViewport(0, 0, (GLsizei)width, (GLsizei)height);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        const double aspect = (double)width / height;
        glFrustum(-aspect * 0.08, aspect * 0.08, -0.08, 0.08, 0.1, 100.);
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        glTranslated(0., 0., -19.);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        const double elapsed = started - begin;
        for (uint32_t i = 0; i < KD_BENCH_INSTANCES; ++i) {
            const int col = (int)(i % 6u), row = (int)(i / 6u);
            glPushMatrix();
            glTranslated((col - 2.5) * 2.7, (row - 2.5) * 2.5, 0.);
            glRotated(elapsed * (24. + (i % 5u) * 8.), 0.6, 1., 0.4);
            glColor3f(0.20f + (i % 3u) * 0.23f, 0.37f + (i % 4u) * 0.13f,
                      0.77f - (i % 3u) * 0.18f);
            glCallList(gpu_mesh);
            glPopMatrix();
        }
        if (glGetError() != GL_NO_ERROR || !SwapBuffers(dc)) {
            fputs("OpenGL drawing or SwapBuffers failed.\n", stderr);
            exit_code = 1;
            break;
        }
        const double complete = timer_seconds();
        const uint64_t elapsed_ns = (uint64_t)((complete - begin) * 1000000000.0 + 0.5);
        const uint64_t call_ns = (uint64_t)((complete - started) * 1000000000.0 + 0.5);
        if (!kd_model_log_record(&telemetry, elapsed_ns, call_ns,
                                 KD_BENCH_TRIANGLES_PER_FRAME, KD_BENCH_INSTANCES, width, height)) {
            fputs("Could not record model frame timing.\n", stderr);
            exit_code = 1;
            break;
        }
        render_ms_sum += (complete - started) * 1000.;
        ++frames;
        ++total_frames;
        if (complete - report_at >= 1.0) {
            const double fps = frames / (complete - report_at);
            const double call_ms = render_ms_sum / frames;
            char title[256];
            snprintf(title, sizeof(title), "Queue Desk | OpenGL 3D | %u models | %u triangles | %.1f calls/s | %.2f ms/call",
                     KD_BENCH_INSTANCES, KD_BENCH_TRIANGLES_PER_FRAME, fps, call_ms);
            SetWindowTextA(hwnd, title);
            printf("%.3f,%llu,%.2f,%.3f\n", complete - begin, total_frames, fps, call_ms);
            frames = 0;
            render_ms_sum = 0.;
            report_at = complete;
        }
    }
cleanup:
    kd_model_log_close(&telemetry);
    kd_bench_mesh_free(&mesh);
    if (gpu_mesh && context) glDeleteLists(gpu_mesh, 1);
    if (context) {
        if (wglGetCurrentContext() == context) wglMakeCurrent(NULL, NULL);
        wglDeleteContext(context);
    }
    if (dc && hwnd) ReleaseDC(hwnd, dc);
    kd_window_destroy(window);
    return exit_code;
}
#else
#include <stdio.h>
int main(void) {
    fputs("OpenGL model benchmark requires Windows.\n", stderr);
    return 1;
}
#endif
