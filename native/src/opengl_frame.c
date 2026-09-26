#include "kotlin_display.h"
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <GL/gl.h>
#ifndef GL_BGRA
#define GL_BGRA 0x80E1
#endif

typedef struct kd_gl_texture {
    uint32_t id;
    uint32_t width;
    uint32_t height;
    GLuint texture;
    struct kd_gl_texture *next;
} kd_gl_texture;
#endif

struct kd_gl_frame {
#if defined(_WIN32)
    HWND window;
    HDC dc;
    HGLRC context;
    kd_draw_rect *scratch;
    uint32_t scratch_capacity;
    kd_gl_texture *textures;
#else
    int unused;
#endif
};

#if defined(_WIN32)
static kd_gl_texture *kd_gl_find_texture(
    kd_gl_frame *frame,
    uint32_t id
) {
    for (kd_gl_texture *entry = frame ? frame->textures : NULL;
         entry;
         entry = entry->next) {
        if (entry->id == id) return entry;
    }
    return NULL;
}

static void kd_gl_destroy_textures(kd_gl_frame *frame) {
    if (!frame) return;
    kd_gl_texture *entry = frame->textures;
    while (entry) {
        kd_gl_texture *next = entry->next;
        if (entry->texture) {
            glDeleteTextures(1, &entry->texture);
        }
        free(entry);
        entry = next;
    }
    frame->textures = NULL;
}
#endif

kd_status kd_gl_frame_create(kd_window *window, kd_gl_frame **out) {
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

    kd_gl_frame *frame =
        (kd_gl_frame *)calloc(1, sizeof(*frame));
    if (!frame) return KD_OUT_OF_MEMORY;

    frame->window = (HWND)(uintptr_t)handles.window;
    frame->dc = GetDC(frame->window);
    if (!frame->dc) goto fail;

    PIXELFORMATDESCRIPTOR desired = {0};
    desired.nSize = sizeof(desired);
    desired.nVersion = 1;
    desired.dwFlags =
        PFD_DRAW_TO_WINDOW |
        PFD_SUPPORT_OPENGL |
        PFD_DOUBLEBUFFER;
    desired.iPixelType = PFD_TYPE_RGBA;
    desired.cColorBits = 32;
    desired.cDepthBits = 0;
    desired.iLayerType = PFD_MAIN_PLANE;

    int format = ChoosePixelFormat(frame->dc, &desired);
    if (!format ||
        !SetPixelFormat(frame->dc, format, &desired)) {
        goto fail;
    }

    frame->context = wglCreateContext(frame->dc);
    if (!frame->context ||
        !wglMakeCurrent(frame->dc, frame->context)) {
        goto fail;
    }

    glDisable(GL_DITHER);
    *out = frame;
    return KD_OK;

fail:
    kd_gl_frame_destroy(frame);
    return KD_GRAPHICS_ERROR;
#endif
}

kd_status kd_gl_frame_upload_texture(
    kd_gl_frame *frame,
    uint32_t texture_id,
    uint32_t width,
    uint32_t height,
    const uint32_t *argb
) {
#if !defined(_WIN32)
    (void)frame; (void)texture_id; (void)width; (void)height; (void)argb;
    return KD_UNSUPPORTED_PLATFORM;
#else
    if (!frame || !texture_id || !width || !height || !argb) {
        return KD_INVALID_ARGUMENT;
    }
    if (wglGetCurrentContext() != frame->context &&
        !wglMakeCurrent(frame->dc, frame->context)) {
        return KD_GRAPHICS_ERROR;
    }

    kd_gl_texture *entry =
        kd_gl_find_texture(frame, texture_id);
    if (!entry) {
        entry = (kd_gl_texture *)calloc(1, sizeof(*entry));
        if (!entry) return KD_OUT_OF_MEMORY;
        entry->id = texture_id;
        entry->next = frame->textures;
        frame->textures = entry;
    } else if (entry->texture) {
        glDeleteTextures(1, &entry->texture);
        entry->texture = 0;
    }

    glGenTextures(1, &entry->texture);
    if (!entry->texture) return KD_GRAPHICS_ERROR;

    glBindTexture(GL_TEXTURE_2D, entry->texture);
    glTexParameteri(
        GL_TEXTURE_2D,
        GL_TEXTURE_MIN_FILTER,
        GL_LINEAR
    );
    glTexParameteri(
        GL_TEXTURE_2D,
        GL_TEXTURE_MAG_FILTER,
        GL_LINEAR
    );
    glTexParameteri(
        GL_TEXTURE_2D,
        GL_TEXTURE_WRAP_S,
        GL_CLAMP
    );
    glTexParameteri(
        GL_TEXTURE_2D,
        GL_TEXTURE_WRAP_T,
        GL_CLAMP
    );
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGBA,
        (GLsizei)width,
        (GLsizei)height,
        0,
        GL_BGRA,
        GL_UNSIGNED_BYTE,
        argb
    );

    GLenum error = glGetError();
    glBindTexture(GL_TEXTURE_2D, 0);
    if (error != GL_NO_ERROR) {
        glDeleteTextures(1, &entry->texture);
        entry->texture = 0;
        return KD_GRAPHICS_ERROR;
    }

    entry->width = width;
    entry->height = height;
    return KD_OK;
#endif
}

void kd_gl_frame_release_texture(
    kd_gl_frame *frame,
    uint32_t texture_id
) {
#if defined(_WIN32)
    if (!frame || !texture_id) return;
    if (wglGetCurrentContext() != frame->context) {
        if (!wglMakeCurrent(frame->dc, frame->context)) return;
    }
    kd_gl_texture **link = &frame->textures;
    while (*link) {
        kd_gl_texture *entry = *link;
        if (entry->id == texture_id) {
            *link = entry->next;
            if (entry->texture) {
                glDeleteTextures(1, &entry->texture);
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

kd_status kd_gl_frame_present_commands(
    kd_gl_frame *frame,
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

    uint32_t width =
        (uint32_t)(client.right - client.left);
    uint32_t height =
        (uint32_t)(client.bottom - client.top);
    if (!width || !height) return KD_SWAPCHAIN_OUT_OF_DATE;

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

    if (wglGetCurrentContext() != frame->context &&
        !wglMakeCurrent(frame->dc, frame->context)) {
        return KD_GRAPHICS_ERROR;
    }

    glViewport(
        0,
        0,
        (GLsizei)width,
        (GLsizei)height
    );
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    glOrtho(
        0.,
        (GLdouble)width,
        (GLdouble)height,
        0.,
        -1.,
        1.
    );
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();

    if (batch.clear_enabled) {
        glClearColor(
            batch.clear.red,
            batch.clear.green,
            batch.clear.blue,
            batch.clear.alpha
        );
        glClear(GL_COLOR_BUFFER_BIT);
    }

    for (uint32_t i = 0; i < batch.rect_count; ++i) {
        const kd_draw_rect *rect = &frame->scratch[i];
        const GLfloat x = (GLfloat)rect->x;
        const GLfloat y = (GLfloat)rect->y;
        const GLfloat right =
            (GLfloat)(rect->x + rect->width);
        const GLfloat bottom =
            (GLfloat)(rect->y + rect->height);

        if (rect->kind == KD_DRAW_IMAGE ||
            rect->kind == KD_DRAW_GLYPH) {
            kd_gl_texture *texture =
                kd_gl_find_texture(
                    frame,
                    rect->resource_id
                );
            if (!texture || !texture->texture) {
                return KD_GRAPHICS_ERROR;
            }

            glEnable(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, texture->texture);
            glBlendFunc(
                GL_SRC_ALPHA,
                GL_ONE_MINUS_SRC_ALPHA
            );
            if (rect->kind == KD_DRAW_GLYPH) {
                glColor4f(
                    rect->color.red,
                    rect->color.green,
                    rect->color.blue,
                    rect->color.alpha
                );
            } else {
                glColor4f(1.f, 1.f, 1.f, 1.f);
            }

            glBegin(GL_QUADS);
            glTexCoord2f(rect->u0, 1.f - rect->v0);
            glVertex2f(x, y);
            glTexCoord2f(rect->u1, 1.f - rect->v0);
            glVertex2f(right, y);
            glTexCoord2f(rect->u1, 1.f - rect->v1);
            glVertex2f(right, bottom);
            glTexCoord2f(rect->u0, 1.f - rect->v1);
            glVertex2f(x, bottom);
            glEnd();

            glBindTexture(GL_TEXTURE_2D, 0);
            glDisable(GL_TEXTURE_2D);
            continue;
        }

        glDisable(GL_TEXTURE_2D);
        if (rect->kind == KD_DRAW_CLEAR_RECT) {
            glBlendFunc(GL_ZERO, GL_ZERO);
            glColor4f(0.f, 0.f, 0.f, 0.f);
        } else {
            glBlendFunc(
                GL_SRC_ALPHA,
                GL_ONE_MINUS_SRC_ALPHA
            );
            glColor4f(
                rect->color.red,
                rect->color.green,
                rect->color.blue,
                rect->color.alpha
            );
        }

        glBegin(GL_QUADS);
        glVertex2f(x, y);
        glVertex2f(right, y);
        glVertex2f(right, bottom);
        glVertex2f(x, bottom);
        glEnd();
    }

    GLenum error = glGetError();
    return error == GL_NO_ERROR &&
        SwapBuffers(frame->dc)
        ? KD_OK
        : KD_GRAPHICS_ERROR;
#endif
}

void kd_gl_frame_destroy(kd_gl_frame *frame) {
    if (!frame) return;
#if defined(_WIN32)
    if (frame->context) {
        if (wglGetCurrentContext() != frame->context) {
            wglMakeCurrent(frame->dc, frame->context);
        }
        kd_gl_destroy_textures(frame);
        if (wglGetCurrentContext() == frame->context) {
            wglMakeCurrent(NULL, NULL);
        }
        wglDeleteContext(frame->context);
    }
    if (frame->dc) {
        ReleaseDC(frame->window, frame->dc);
    }
    free(frame->scratch);
#endif
    free(frame);
}
