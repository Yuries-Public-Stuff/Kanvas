#include "kotlin_display.h"
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define COBJMACROS
#include <windows.h>
#include <d3d9.h>
#endif

#if defined(_WIN32)
typedef struct kd_d3d9_texture {
    uint32_t id;
    uint32_t width;
    uint32_t height;
    IDirect3DTexture9 *texture;
    struct kd_d3d9_texture *next;
} kd_d3d9_texture;
#endif

struct kd_d3d9_frame {
#if defined(_WIN32)
    HWND window;
    IDirect3D9 *api;
    IDirect3DDevice9 *device;
    uint32_t width;
    uint32_t height;
    kd_draw_rect *scratch;
    uint32_t scratch_capacity;
    kd_d3d9_texture *textures;
#else
    int unused;
#endif
};

#if defined(_WIN32)
typedef struct kd_d3d9_color_vertex {
    float x, y, z, rhw;
    D3DCOLOR color;
} kd_d3d9_color_vertex;

typedef struct kd_d3d9_texture_vertex {
    float x, y, z, rhw;
    D3DCOLOR color;
    float u, v;
} kd_d3d9_texture_vertex;

#define KD_D3D9_COLOR_FVF (D3DFVF_XYZRHW | D3DFVF_DIFFUSE)
#define KD_D3D9_TEXTURE_FVF \
    (D3DFVF_XYZRHW | D3DFVF_DIFFUSE | D3DFVF_TEX1)

static D3DCOLOR kd_d3d9_color(kd_draw_color color) {
    return D3DCOLOR_ARGB(
        (int)(color.alpha * 255.f + .5f),
        (int)(color.red * 255.f + .5f),
        (int)(color.green * 255.f + .5f),
        (int)(color.blue * 255.f + .5f)
    );
}

static kd_d3d9_texture *kd_d3d9_find_texture(
    kd_d3d9_frame *frame,
    uint32_t id
) {
    for (kd_d3d9_texture *entry = frame ? frame->textures : NULL;
         entry;
         entry = entry->next) {
        if (entry->id == id) return entry;
    }
    return NULL;
}

static void kd_d3d9_destroy_textures(kd_d3d9_frame *frame) {
    if (!frame) return;
    kd_d3d9_texture *entry = frame->textures;
    while (entry) {
        kd_d3d9_texture *next = entry->next;
        if (entry->texture) {
            IDirect3DTexture9_Release(entry->texture);
        }
        free(entry);
        entry = next;
    }
    frame->textures = NULL;
}
#endif

kd_status kd_d3d9_frame_create(
    kd_window *window,
    kd_d3d9_frame **out
) {
    if (!window || !out) return KD_INVALID_ARGUMENT;
    *out = NULL;
#if !defined(_WIN32)
    return KD_UNSUPPORTED_PLATFORM;
#else
    kd_native_window_handles handles = {0};
    kd_status status = kd_window_get_native_handles(window, &handles);
    if (status != KD_OK) return status;
    if (handles.platform != KD_WINDOW_PLATFORM_WIN32 ||
        !handles.window) {
        return KD_UNSUPPORTED_PLATFORM;
    }

    uint32_t width = 0;
    uint32_t height = 0;
    status = kd_window_get_size(window, &width, &height);
    if (status != KD_OK) return status;
    if (!width || !height) return KD_SWAPCHAIN_OUT_OF_DATE;

    kd_d3d9_frame *frame =
        (kd_d3d9_frame *)calloc(1, sizeof(*frame));
    if (!frame) return KD_OUT_OF_MEMORY;

    frame->window = (HWND)(uintptr_t)handles.window;
    frame->width = width;
    frame->height = height;
    frame->api = Direct3DCreate9(D3D_SDK_VERSION);
    if (!frame->api) goto fail;

    D3DPRESENT_PARAMETERS settings = {0};
    settings.Windowed = TRUE;
    settings.SwapEffect = D3DSWAPEFFECT_COPY;
    settings.BackBufferFormat = D3DFMT_UNKNOWN;
    settings.BackBufferWidth = width;
    settings.BackBufferHeight = height;
    settings.hDeviceWindow = frame->window;
    settings.PresentationInterval = D3DPRESENT_INTERVAL_IMMEDIATE;

    HRESULT result = IDirect3D9_CreateDevice(
        frame->api,
        D3DADAPTER_DEFAULT,
        D3DDEVTYPE_HAL,
        frame->window,
        D3DCREATE_SOFTWARE_VERTEXPROCESSING,
        &settings,
        &frame->device
    );
    if (FAILED(result)) {
        settings.PresentationInterval = D3DPRESENT_INTERVAL_DEFAULT;
        result = IDirect3D9_CreateDevice(
            frame->api,
            D3DADAPTER_DEFAULT,
            D3DDEVTYPE_HAL,
            frame->window,
            D3DCREATE_SOFTWARE_VERTEXPROCESSING,
            &settings,
            &frame->device
        );
    }
    if (FAILED(result)) goto fail;

    *out = frame;
    return KD_OK;

fail:
    kd_d3d9_frame_destroy(frame);
    return KD_GRAPHICS_ERROR;
#endif
}

kd_status kd_d3d9_frame_upload_texture(
    kd_d3d9_frame *frame,
    uint32_t texture_id,
    uint32_t width,
    uint32_t height,
    const uint32_t *argb
) {
#if !defined(_WIN32)
    (void)frame; (void)texture_id; (void)width; (void)height; (void)argb;
    return KD_UNSUPPORTED_PLATFORM;
#else
    if (!frame || !frame->device || !texture_id ||
        !width || !height || !argb) {
        return KD_INVALID_ARGUMENT;
    }

    kd_d3d9_texture *entry =
        kd_d3d9_find_texture(frame, texture_id);
    if (!entry) {
        entry = (kd_d3d9_texture *)calloc(1, sizeof(*entry));
        if (!entry) return KD_OUT_OF_MEMORY;
        entry->id = texture_id;
        entry->next = frame->textures;
        frame->textures = entry;
    } else if (entry->texture) {
        IDirect3DTexture9_Release(entry->texture);
        entry->texture = NULL;
    }

    IDirect3DTexture9 *texture = NULL;
    HRESULT result = IDirect3DDevice9_CreateTexture(
        frame->device,
        width,
        height,
        1,
        0,
        D3DFMT_A8R8G8B8,
        D3DPOOL_MANAGED,
        &texture,
        NULL
    );
    if (FAILED(result) || !texture) return KD_GRAPHICS_ERROR;

    D3DLOCKED_RECT locked;
    result = IDirect3DTexture9_LockRect(
        texture,
        0,
        &locked,
        NULL,
        0
    );
    if (FAILED(result)) {
        IDirect3DTexture9_Release(texture);
        return KD_GRAPHICS_ERROR;
    }

    for (uint32_t y = 0; y < height; ++y) {
        memcpy(
            (unsigned char *)locked.pBits + (size_t)y * locked.Pitch,
            argb + (size_t)y * width,
            (size_t)width * sizeof(uint32_t)
        );
    }

    IDirect3DTexture9_UnlockRect(texture, 0);
    entry->texture = texture;
    entry->width = width;
    entry->height = height;
    return KD_OK;
#endif
}

void kd_d3d9_frame_release_texture(
    kd_d3d9_frame *frame,
    uint32_t texture_id
) {
#if defined(_WIN32)
    if (!frame || !texture_id) return;
    kd_d3d9_texture **link = &frame->textures;
    while (*link) {
        kd_d3d9_texture *entry = *link;
        if (entry->id == texture_id) {
            *link = entry->next;
            if (entry->texture) {
                IDirect3DTexture9_Release(entry->texture);
            }
            free(entry);
            return;
        }
        link = &entry->next;
    }
#else
    (void)frame;
    (void)texture_id;
#endif
}

kd_status kd_d3d9_frame_present_commands(
    kd_d3d9_frame *frame,
    const kd_draw_command *commands,
    uint32_t count
) {
    if (!frame || !commands || !count ||
        count > KD_DRAW_MAX_COMMANDS) {
        return KD_INVALID_ARGUMENT;
    }
#if !defined(_WIN32)
    return KD_UNSUPPORTED_PLATFORM;
#else
    RECT client;
    if (!GetClientRect(frame->window, &client)) {
        return KD_WINDOW_UNAVAILABLE;
    }

    uint32_t width = (uint32_t)(client.right - client.left);
    uint32_t height = (uint32_t)(client.bottom - client.top);
    if (!width || !height ||
        width != frame->width ||
        height != frame->height) {
        return KD_SWAPCHAIN_OUT_OF_DATE;
    }

    if (count > frame->scratch_capacity) {
        kd_draw_rect *grown = (kd_draw_rect *)realloc(
            frame->scratch,
            (size_t)count * sizeof(*grown)
        );
        if (!grown) return KD_OUT_OF_MEMORY;
        frame->scratch = grown;
        frame->scratch_capacity = count;
    }

    kd_draw_batch batch = {0};
    kd_draw_result prepared = kd_prepare_draw_batch(
        width,
        height,
        commands,
        count,
        frame->scratch,
        frame->scratch_capacity,
        &batch
    );
    if (prepared != KD_DRAW_OK) {
        return prepared == KD_DRAW_UNSUPPORTED
            ? KD_DRAW_UNSUPPORTED_OPERATION
            : KD_INVALID_ARGUMENT;
    }

    HRESULT result = D3D_OK;
    if (batch.clear_enabled) {
        result = IDirect3DDevice9_Clear(
            frame->device,
            0,
            NULL,
            D3DCLEAR_TARGET,
            kd_d3d9_color(batch.clear),
            1.f,
            0
        );
    }

    if (SUCCEEDED(result) && batch.rect_count) {
        result = IDirect3DDevice9_BeginScene(frame->device);
        if (SUCCEEDED(result)) {
            IDirect3DDevice9_SetRenderState(
                frame->device,
                D3DRS_LIGHTING,
                FALSE
            );
            IDirect3DDevice9_SetRenderState(
                frame->device,
                D3DRS_ZENABLE,
                FALSE
            );
            IDirect3DDevice9_SetRenderState(
                frame->device,
                D3DRS_CULLMODE,
                D3DCULL_NONE
            );
            IDirect3DDevice9_SetRenderState(
                frame->device,
                D3DRS_ALPHABLENDENABLE,
                TRUE
            );
            IDirect3DDevice9_SetRenderState(
                frame->device,
                D3DRS_BLENDOP,
                D3DBLENDOP_ADD
            );

            IDirect3DDevice9_SetSamplerState(
                frame->device, 0,
                D3DSAMP_MINFILTER,
                D3DTEXF_LINEAR
            );
            IDirect3DDevice9_SetSamplerState(
                frame->device, 0,
                D3DSAMP_MAGFILTER,
                D3DTEXF_LINEAR
            );
            IDirect3DDevice9_SetSamplerState(
                frame->device, 0,
                D3DSAMP_ADDRESSU,
                D3DTADDRESS_CLAMP
            );
            IDirect3DDevice9_SetSamplerState(
                frame->device, 0,
                D3DSAMP_ADDRESSV,
                D3DTADDRESS_CLAMP
            );

            for (uint32_t i = 0;
                 SUCCEEDED(result) && i < batch.rect_count;
                 ++i) {
                const kd_draw_rect *rect = &frame->scratch[i];

                const float left = (float)rect->x - 0.5f;
                const float top = (float)rect->y - 0.5f;
                const float right =
                    (float)(rect->x + rect->width) - 0.5f;
                const float bottom =
                    (float)(rect->y + rect->height) - 0.5f;

                if (rect->kind == KD_DRAW_IMAGE ||
                    rect->kind == KD_DRAW_GLYPH) {
                    kd_d3d9_texture *texture =
                        kd_d3d9_find_texture(
                            frame,
                            rect->resource_id
                        );
                    if (!texture || !texture->texture) {
                        result = D3DERR_INVALIDCALL;
                        break;
                    }

                    IDirect3DDevice9_SetTexture(
                        frame->device,
                        0,
                        (IDirect3DBaseTexture9 *)texture->texture
                    );
                    IDirect3DDevice9_SetFVF(
                        frame->device,
                        KD_D3D9_TEXTURE_FVF
                    );
                    IDirect3DDevice9_SetRenderState(
                        frame->device,
                        D3DRS_SRCBLEND,
                        D3DBLEND_SRCALPHA
                    );
                    IDirect3DDevice9_SetRenderState(
                        frame->device,
                        D3DRS_DESTBLEND,
                        D3DBLEND_INVSRCALPHA
                    );
                    if (rect->kind == KD_DRAW_GLYPH) {
                        IDirect3DDevice9_SetTextureStageState(
                            frame->device,
                            0,
                            D3DTSS_COLOROP,
                            D3DTOP_MODULATE
                        );
                        IDirect3DDevice9_SetTextureStageState(
                            frame->device,
                            0,
                            D3DTSS_COLORARG1,
                            D3DTA_TEXTURE
                        );
                        IDirect3DDevice9_SetTextureStageState(
                            frame->device,
                            0,
                            D3DTSS_COLORARG2,
                            D3DTA_DIFFUSE
                        );
                        IDirect3DDevice9_SetTextureStageState(
                            frame->device,
                            0,
                            D3DTSS_ALPHAOP,
                            D3DTOP_MODULATE
                        );
                        IDirect3DDevice9_SetTextureStageState(
                            frame->device,
                            0,
                            D3DTSS_ALPHAARG1,
                            D3DTA_TEXTURE
                        );
                        IDirect3DDevice9_SetTextureStageState(
                            frame->device,
                            0,
                            D3DTSS_ALPHAARG2,
                            D3DTA_DIFFUSE
                        );
                    } else {
                        IDirect3DDevice9_SetTextureStageState(
                            frame->device,
                            0,
                            D3DTSS_COLOROP,
                            D3DTOP_SELECTARG1
                        );
                        IDirect3DDevice9_SetTextureStageState(
                            frame->device,
                            0,
                            D3DTSS_COLORARG1,
                            D3DTA_TEXTURE
                        );
                        IDirect3DDevice9_SetTextureStageState(
                            frame->device,
                            0,
                            D3DTSS_ALPHAOP,
                            D3DTOP_SELECTARG1
                        );
                        IDirect3DDevice9_SetTextureStageState(
                            frame->device,
                            0,
                            D3DTSS_ALPHAARG1,
                            D3DTA_TEXTURE
                        );
                    }

                    const D3DCOLOR tint =
                        rect->kind == KD_DRAW_GLYPH
                            ? kd_d3d9_color(rect->color)
                            : 0xffffffffu;

                    const kd_d3d9_texture_vertex vertices[4] = {
                        {left, top, 0.f, 1.f, tint,
                         rect->u0, rect->v0},
                        {right, top, 0.f, 1.f, tint,
                         rect->u1, rect->v0},
                        {left, bottom, 0.f, 1.f, tint,
                         rect->u0, rect->v1},
                        {right, bottom, 0.f, 1.f, tint,
                         rect->u1, rect->v1},
                    };

                    result = IDirect3DDevice9_DrawPrimitiveUP(
                        frame->device,
                        D3DPT_TRIANGLESTRIP,
                        2,
                        vertices,
                        sizeof(kd_d3d9_texture_vertex)
                    );
                    continue;
                }

                IDirect3DDevice9_SetTexture(
                    frame->device,
                    0,
                    NULL
                );
                IDirect3DDevice9_SetFVF(
                    frame->device,
                    KD_D3D9_COLOR_FVF
                );

                const D3DCOLOR color =
                    rect->kind == KD_DRAW_CLEAR_RECT
                        ? 0
                        : kd_d3d9_color(rect->color);

                if (rect->kind == KD_DRAW_CLEAR_RECT) {
                    IDirect3DDevice9_SetRenderState(
                        frame->device,
                        D3DRS_SRCBLEND,
                        D3DBLEND_ZERO
                    );
                    IDirect3DDevice9_SetRenderState(
                        frame->device,
                        D3DRS_DESTBLEND,
                        D3DBLEND_ZERO
                    );
                } else {
                    IDirect3DDevice9_SetRenderState(
                        frame->device,
                        D3DRS_SRCBLEND,
                        D3DBLEND_SRCALPHA
                    );
                    IDirect3DDevice9_SetRenderState(
                        frame->device,
                        D3DRS_DESTBLEND,
                        D3DBLEND_INVSRCALPHA
                    );
                }

                const kd_d3d9_color_vertex vertices[4] = {
                    {left, top, 0.f, 1.f, color},
                    {right, top, 0.f, 1.f, color},
                    {left, bottom, 0.f, 1.f, color},
                    {right, bottom, 0.f, 1.f, color},
                };

                result = IDirect3DDevice9_DrawPrimitiveUP(
                    frame->device,
                    D3DPT_TRIANGLESTRIP,
                    2,
                    vertices,
                    sizeof(kd_d3d9_color_vertex)
                );
            }

            IDirect3DDevice9_SetTexture(frame->device, 0, NULL);
            HRESULT ended =
                IDirect3DDevice9_EndScene(frame->device);
            if (SUCCEEDED(result)) result = ended;
        }
    }

    if (SUCCEEDED(result)) {
        result = IDirect3DDevice9_Present(
            frame->device,
            NULL,
            NULL,
            NULL,
            NULL
        );
    }

    if (result == D3DERR_DEVICELOST ||
        result == D3DERR_DEVICENOTRESET) {
        return KD_SWAPCHAIN_OUT_OF_DATE;
    }

    if (FAILED(result)) {
        HRESULT cooperative =
            IDirect3DDevice9_TestCooperativeLevel(frame->device);
        if (cooperative == D3DERR_DEVICELOST ||
            cooperative == D3DERR_DEVICENOTRESET) {
            return KD_SWAPCHAIN_OUT_OF_DATE;
        }
    }

    return SUCCEEDED(result) ? KD_OK : KD_GRAPHICS_ERROR;
#endif
}

void kd_d3d9_frame_destroy(kd_d3d9_frame *frame) {
    if (!frame) return;
#if defined(_WIN32)
    kd_d3d9_destroy_textures(frame);
    if (frame->device) IDirect3DDevice9_Release(frame->device);
    if (frame->api) IDirect3D9_Release(frame->api);
    free(frame->scratch);
#endif
    free(frame);
}
