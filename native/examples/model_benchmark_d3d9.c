#include "kotlin_display.h"
#include "torus_mesh.h"
#include "model_benchmark_telemetry.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define COBJMACROS
#include <windows.h>
#include <d3d9.h>
#include <stdio.h>
#include <string.h>

static double seconds_now(void) {
    static double frequency;
    LARGE_INTEGER time;
    if (frequency == 0.0) {
        QueryPerformanceFrequency(&time);
        frequency = (double)time.QuadPart;
    }
    QueryPerformanceCounter(&time);
    return (double)time.QuadPart / frequency;
}

static D3DMATRIX identity(void) {
    D3DMATRIX m = {0};
    m._11 = m._22 = m._33 = m._44 = 1.f;
    return m;
}

static D3DMATRIX projection(float aspect) {
    D3DMATRIX m = {0};
    const float near_plane = 0.1f, far_plane = 100.f;
    const float vertical_scale = 1.25f;
    m._11 = vertical_scale / aspect;
    m._22 = vertical_scale;
    m._33 = far_plane / (far_plane - near_plane);
    m._34 = 1.f;
    m._43 = -near_plane * far_plane / (far_plane - near_plane);
    return m;
}

static D3DMATRIX model_world(double seconds, unsigned instance) {
    const float angle = (float)(seconds * (24. + (instance % 5u) * 8.) * KD_BENCH_PI / 180.0);
    const float c = cosf(angle), s = sinf(angle), t = 1.f - c;
    const float x = 0.6f / 1.2328828f;
    const float y = 1.f / 1.2328828f;
    const float z = 0.4f / 1.2328828f;
    D3DMATRIX m = identity();
    m._11 = t * x * x + c;
    m._12 = t * x * y + s * z;
    m._13 = t * x * z - s * y;
    m._21 = t * x * y - s * z;
    m._22 = t * y * y + c;
    m._23 = t * y * z + s * x;
    m._31 = t * x * z + s * y;
    m._32 = t * y * z - s * x;
    m._33 = t * z * z + c;
    m._41 = ((int)(instance % 6u) - 2.5f) * 2.7f;
    m._42 = ((int)(instance / 6u) - 2.5f) * 2.5f;
    return m;
}

static int create_device(IDirect3D9 *api, HWND window, unsigned width, unsigned height,
                         IDirect3DDevice9 **out, D3DPRESENT_PARAMETERS *parameters) {
    memset(parameters, 0, sizeof(*parameters));
    parameters->Windowed = TRUE;
    parameters->SwapEffect = D3DSWAPEFFECT_DISCARD;
    parameters->BackBufferFormat = D3DFMT_UNKNOWN;
    parameters->BackBufferWidth = width;
    parameters->BackBufferHeight = height;
    parameters->EnableAutoDepthStencil = TRUE;
    parameters->AutoDepthStencilFormat = D3DFMT_D24S8;
    parameters->hDeviceWindow = window;
    parameters->PresentationInterval = D3DPRESENT_INTERVAL_IMMEDIATE;
    HRESULT status = IDirect3D9_CreateDevice(api, D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, window,
                                           D3DCREATE_SOFTWARE_VERTEXPROCESSING, parameters, out);
    if (FAILED(status)) {
        parameters->AutoDepthStencilFormat = D3DFMT_D16;
        status = IDirect3D9_CreateDevice(api, D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, window,
                                        D3DCREATE_SOFTWARE_VERTEXPROCESSING, parameters, out);
    }
    return SUCCEEDED(status);
}

static void setup_render_state(IDirect3DDevice9 *device) {
    D3DMATRIX view = identity();
    view._43 = 19.f;
    IDirect3DDevice9_SetTransform(device, D3DTS_VIEW, &view);
    IDirect3DDevice9_SetRenderState(device, D3DRS_ZENABLE, D3DZB_TRUE);
    IDirect3DDevice9_SetRenderState(device, D3DRS_ZWRITEENABLE, TRUE);
    IDirect3DDevice9_SetRenderState(device, D3DRS_LIGHTING, TRUE);
    IDirect3DDevice9_SetRenderState(device, D3DRS_NORMALIZENORMALS, TRUE);
    IDirect3DDevice9_SetRenderState(device, D3DRS_CULLMODE, D3DCULL_NONE);
    IDirect3DDevice9_SetRenderState(device, D3DRS_AMBIENT, D3DCOLOR_XRGB(32, 38, 50));
    D3DLIGHT9 light = {0};
    light.Type = D3DLIGHT_DIRECTIONAL;
    light.Diffuse.r = light.Diffuse.g = light.Diffuse.b = 0.9f;
    light.Diffuse.a = 1.f;
    light.Direction.x = -0.3f;
    light.Direction.y = -0.5f;
    light.Direction.z = 0.8f;
    IDirect3DDevice9_SetLight(device, 0, &light);
    IDirect3DDevice9_LightEnable(device, 0, TRUE);
    IDirect3DDevice9_SetFVF(device, D3DFVF_XYZ | D3DFVF_NORMAL);
}

int main(void) {
    kd_window *window = NULL;
    IDirect3D9 *api = NULL;
    IDirect3DDevice9 *device = NULL;
    IDirect3DVertexBuffer9 *vertices = NULL;
    IDirect3DIndexBuffer9 *indices = NULL;
    kd_bench_mesh mesh = {0};
    kd_model_log telemetry = {0};
    D3DPRESENT_PARAMETERS parameters = {0};
    int result = 1;
    if (!kd_bench_mesh_create(&mesh)) goto cleanup;
    if (kd_window_create(1280, 720, "Queue Desk | D3D9 triangle models", &window) != KD_OK) goto cleanup;
    kd_native_window_handles handles = {0};
    if (kd_window_get_native_handles(window, &handles) != KD_OK ||
        handles.platform != KD_WINDOW_PLATFORM_WIN32 || !handles.window) goto cleanup;
    HWND hwnd = (HWND)(uintptr_t)handles.window;
    api = Direct3DCreate9(D3D_SDK_VERSION);
    if (!api || !create_device(api, hwnd, 1280, 720, &device, &parameters)) goto cleanup;
    if (FAILED(IDirect3DDevice9_CreateVertexBuffer(device, sizeof(kd_bench_vertex) * KD_BENCH_VERTICES,
                   D3DUSAGE_WRITEONLY, D3DFVF_XYZ | D3DFVF_NORMAL, D3DPOOL_MANAGED, &vertices, NULL))) goto cleanup;
    if (FAILED(IDirect3DDevice9_CreateIndexBuffer(device, sizeof(uint16_t) * KD_BENCH_INDICES,
                   D3DUSAGE_WRITEONLY, D3DFMT_INDEX16, D3DPOOL_MANAGED, &indices, NULL))) goto cleanup;
    void *mapped = NULL;
    if (FAILED(IDirect3DVertexBuffer9_Lock(vertices, 0, 0, &mapped, 0))) goto cleanup;
    memcpy(mapped, mesh.vertices, sizeof(kd_bench_vertex) * KD_BENCH_VERTICES);
    IDirect3DVertexBuffer9_Unlock(vertices);
    if (FAILED(IDirect3DIndexBuffer9_Lock(indices, 0, 0, &mapped, 0))) goto cleanup;
    memcpy(mapped, mesh.indices, sizeof(uint16_t) * KD_BENCH_INDICES);
    IDirect3DIndexBuffer9_Unlock(indices);
    kd_bench_mesh_free(&mesh);
    if (FAILED(IDirect3DDevice9_SetStreamSource(device, 0, vertices, 0, sizeof(kd_bench_vertex))) ||
        FAILED(IDirect3DDevice9_SetIndices(device, indices))) goto cleanup;
    setup_render_state(device);
    if (!kd_model_log_open(&telemetry, "direct3d9")) {
        fputs("Could not open model telemetry file.\n", stderr);
        goto cleanup;
    }
    const double start = seconds_now();
    double report_time = start, sum_ms = 0.;
    unsigned long long sample_frames = 0, total_frames = 0;
    int closed = 0;
    result = 0;
    while (kd_window_poll(window, &closed) == KD_OK && !closed) {
        uint32_t width = 0, height = 0;
        if (kd_window_get_size(window, &width, &height) != KD_OK) { result = 1; break; }
        if (!width || !height) { Sleep(10); continue; }
        if (width != parameters.BackBufferWidth || height != parameters.BackBufferHeight) {
            parameters.BackBufferWidth = width;
            parameters.BackBufferHeight = height;
            if (FAILED(IDirect3DDevice9_Reset(device, &parameters))) { Sleep(25); continue; }
            setup_render_state(device);
            IDirect3DDevice9_SetStreamSource(device, 0, vertices, 0, sizeof(kd_bench_vertex));
            IDirect3DDevice9_SetIndices(device, indices);
        }
        HRESULT cooperative = IDirect3DDevice9_TestCooperativeLevel(device);
        if (cooperative == D3DERR_DEVICELOST) { Sleep(25); continue; }
        if (cooperative == D3DERR_DEVICENOTRESET) {
            if (FAILED(IDirect3DDevice9_Reset(device, &parameters))) { Sleep(25); continue; }
            setup_render_state(device);
            IDirect3DDevice9_SetStreamSource(device, 0, vertices, 0, sizeof(kd_bench_vertex));
            IDirect3DDevice9_SetIndices(device, indices);
        } else if (FAILED(cooperative)) { result = 1; break; }
        const double begin = seconds_now();
        D3DMATRIX lens = projection((float)width / height);
        if (FAILED(IDirect3DDevice9_SetTransform(device, D3DTS_PROJECTION, &lens)) ||
            FAILED(IDirect3DDevice9_Clear(device, 0, NULL, D3DCLEAR_TARGET | D3DCLEAR_ZBUFFER,
                                          D3DCOLOR_XRGB(6, 9, 17), 1.f, 0)) ||
            FAILED(IDirect3DDevice9_BeginScene(device))) { result = 1; break; }
        const double elapsed = begin - start;
        HRESULT draw = D3D_OK;
        for (unsigned i = 0; i < KD_BENCH_INSTANCES && SUCCEEDED(draw); ++i) {
            D3DMATRIX world = model_world(elapsed, i);
            D3DMATERIAL9 material = {0};
            material.Diffuse.r = 0.20f + (i % 3u) * 0.23f;
            material.Diffuse.g = 0.37f + (i % 4u) * 0.13f;
            material.Diffuse.b = 0.77f - (i % 3u) * 0.18f;
            material.Diffuse.a = 1.f;
            material.Ambient = material.Diffuse;
            draw = IDirect3DDevice9_SetTransform(device, D3DTS_WORLD, &world);
            if (SUCCEEDED(draw)) draw = IDirect3DDevice9_SetMaterial(device, &material);
            if (SUCCEEDED(draw)) draw = IDirect3DDevice9_DrawIndexedPrimitive(device,
                D3DPT_TRIANGLELIST, 0, 0, KD_BENCH_VERTICES, 0, KD_BENCH_TRIANGLES);
        }
        HRESULT end = IDirect3DDevice9_EndScene(device);
        if (FAILED(draw) || FAILED(end)) { result = 1; break; }
        HRESULT presented = IDirect3DDevice9_Present(device, NULL, NULL, NULL, NULL);
        if (presented == D3DERR_DEVICELOST) { Sleep(25); continue; }
        if (FAILED(presented)) { result = 1; break; }
        const double complete = seconds_now();
        const uint64_t elapsed_ns = (uint64_t)((complete - start) * 1000000000.0 + 0.5);
        const uint64_t call_ns = (uint64_t)((complete - begin) * 1000000000.0 + 0.5);
        if (!kd_model_log_record(&telemetry, elapsed_ns, call_ns,
                                 KD_BENCH_TRIANGLES_PER_FRAME, KD_BENCH_INSTANCES, width, height)) {
            fputs("Could not record model frame timing.\n", stderr);
            result = 1;
            break;
        }
        ++sample_frames;
        ++total_frames;
        sum_ms += (complete - begin) * 1000.;
        if (complete - report_time >= 1.0) {
            char title[256];
            const double fps = sample_frames / (complete - report_time);
            const double mean_ms = sum_ms / sample_frames;
            snprintf(title, sizeof(title), "Queue Desk | D3D9 3D | %u models | %u triangles | %.1f calls/s | %.2f ms/call",
                     KD_BENCH_INSTANCES, KD_BENCH_TRIANGLES_PER_FRAME, fps, mean_ms);
            SetWindowTextA(hwnd, title);
            printf("%.3f,%llu,%.2f,%.3f\n", complete - start, total_frames, fps, mean_ms);
            report_time = complete;
            sample_frames = 0;
            sum_ms = 0.;
        }
    }
cleanup:
    kd_model_log_close(&telemetry);
    kd_bench_mesh_free(&mesh);
    if (indices) IDirect3DIndexBuffer9_Release(indices);
    if (vertices) IDirect3DVertexBuffer9_Release(vertices);
    if (device) IDirect3DDevice9_Release(device);
    if (api) IDirect3D9_Release(api);
    kd_window_destroy(window);
    if (result) fputs("D3D9 model benchmark failed; see device/driver support.\n", stderr);
    return result;
}
#else
#include <stdio.h>
int main(void) {
    fputs("Direct3D9 model benchmark requires Windows.\n", stderr);
    return 1;
}
#endif
