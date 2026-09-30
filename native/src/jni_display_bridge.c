#include "kotlin_display.h"

#if defined(KD_HAS_JNI)
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#if defined(_WIN32)
#include <windows.h>
#include <jawt.h>
#include <jawt_md.h>
#elif defined(__linux__)
#include <jawt.h>
#include <jawt_md.h>
#elif defined(__APPLE__)
#include <jawt.h>
#define KD_JAWT_MACOSX_USE_CALAYER 0x80000000
#endif

typedef struct kd_jvm_texture {
    uint32_t id;
    uint32_t width;
    uint32_t height;
    uint32_t *pixels;
    uint64_t revision;
    uint64_t uploaded_revision;
    uint64_t uploaded_generation;
    struct kd_jvm_texture *next;
} kd_jvm_texture;

typedef struct kd_jvm_renderer {
    kd_window *window;
    kd_vk_frame *vulkan;
    kd_gl_frame *opengl;
    kd_d3d9_frame *d3d9;
    kd_metal_frame *metal;
    int requested_backend;
    int backend;
    uint64_t backend_generation;
    kd_jvm_texture *textures;
} kd_jvm_renderer;

#if defined(_WIN32)
static HWND kd_jvm_awt_hwnd(JNIEnv *env, jobject component) {
    if (!env || !component) return NULL;

    HMODULE jawt_module = GetModuleHandleA("jawt.dll");
    if (!jawt_module) jawt_module = LoadLibraryA("jawt.dll");
    if (!jawt_module) return NULL;

    typedef jboolean (JNICALL *jawt_get_awt_fn)(JNIEnv *, JAWT *);
    jawt_get_awt_fn get_awt = (jawt_get_awt_fn)GetProcAddress(
        jawt_module,
        "JAWT_GetAWT"
    );
    if (!get_awt) return NULL;

    JAWT awt;
    memset(&awt, 0, sizeof(awt));
    awt.version = JAWT_VERSION_1_4;
    if (!get_awt(env, &awt)) return NULL;

    JAWT_DrawingSurface *surface = awt.GetDrawingSurface(env, component);
    if (!surface) return NULL;

    HWND hwnd = NULL;
    jint lock = surface->Lock(surface);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *info =
            surface->GetDrawingSurfaceInfo(surface);
        if (info && info->platformInfo) {
            JAWT_Win32DrawingSurfaceInfo *win_info =
                (JAWT_Win32DrawingSurfaceInfo *)info->platformInfo;
            hwnd = win_info->hwnd;
        }
        if (info) surface->FreeDrawingSurfaceInfo(info);
        surface->Unlock(surface);
    }
    awt.FreeDrawingSurface(surface);
    return hwnd;
}
#elif defined(__linux__)
static uintptr_t kd_jvm_awt_x11_window(
    JNIEnv *env,
    jobject component
) {
    if (!env || !component) return 0;

    JAWT awt;
    memset(&awt, 0, sizeof(awt));
    awt.version = JAWT_VERSION_1_4;
    if (!JAWT_GetAWT(env, &awt)) return 0;

    JAWT_DrawingSurface *surface =
        awt.GetDrawingSurface(env, component);
    if (!surface) return 0;

    uintptr_t drawable = 0;
    jint lock = surface->Lock(surface);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *info =
            surface->GetDrawingSurfaceInfo(surface);
        if (info && info->platformInfo) {
            JAWT_X11DrawingSurfaceInfo *x11 =
                (JAWT_X11DrawingSurfaceInfo *)info->platformInfo;
            drawable = (uintptr_t)x11->drawable;
        }
        if (info) surface->FreeDrawingSurfaceInfo(info);
        surface->Unlock(surface);
    }

    awt.FreeDrawingSurface(surface);
    return drawable;
}
#elif defined(__APPLE__)
static kd_status kd_jvm_create_awt_child(
    JNIEnv *env,
    jobject component,
    uint32_t width,
    uint32_t height,
    kd_window **out
) {
    if (!env || !component || !out) return KD_INVALID_ARGUMENT;
    *out = NULL;

    JAWT awt;
    memset(&awt, 0, sizeof(awt));
    awt.version = JAWT_VERSION_1_4 | KD_JAWT_MACOSX_USE_CALAYER;
    if (!JAWT_GetAWT(env, &awt)) {
        return KD_WINDOW_UNAVAILABLE;
    }

    JAWT_DrawingSurface *surface =
        awt.GetDrawingSurface(env, component);
    if (!surface) return KD_WINDOW_UNAVAILABLE;

    kd_status status = KD_WINDOW_UNAVAILABLE;
    jint lock = surface->Lock(surface);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *info =
            surface->GetDrawingSurfaceInfo(surface);
        if (info && info->platformInfo) {
            status = kd_window_create_child(
                (uintptr_t)info->platformInfo,
                width,
                height,
                out
            );
        }
        if (info) surface->FreeDrawingSurfaceInfo(info);
        surface->Unlock(surface);
    }

    awt.FreeDrawingSurface(surface);
    return status;
}
#endif

enum {
    KD_JVM_AUTO = 0,
    KD_JVM_VULKAN = 1,
    KD_JVM_METAL = 2,
    KD_JVM_OPENGL = 3,
    KD_JVM_D3D9 = 4,
    KD_JVM_GDI = 5
};

static kd_status kd_jvm_backend_created(
    kd_jvm_renderer *renderer,
    kd_status status
) {
    if (renderer && status == KD_OK) {
        renderer->backend_generation++;
        if (renderer->backend_generation == 0) {
            renderer->backend_generation = 1;
        }
    }
    return status;
}

static kd_jvm_texture *kd_jvm_find_texture(
    kd_jvm_renderer *renderer,
    uint32_t id
) {
    for (kd_jvm_texture *entry = renderer ? renderer->textures : NULL;
         entry;
         entry = entry->next) {
        if (entry->id == id) return entry;
    }
    return NULL;
}

static void kd_jvm_destroy_textures(kd_jvm_renderer *renderer) {
    if (!renderer) return;
    kd_jvm_texture *entry = renderer->textures;
    while (entry) {
        kd_jvm_texture *next = entry->next;
        free(entry->pixels);
        free(entry);
        entry = next;
    }
    renderer->textures = NULL;
}

static kd_status kd_jvm_ensure_texture(
    kd_jvm_renderer *renderer,
    uint32_t id
) {
    kd_jvm_texture *texture =
        kd_jvm_find_texture(renderer, id);
    if (!texture || !texture->pixels) {
        return KD_INVALID_ARGUMENT;
    }

    if (texture->uploaded_revision == texture->revision &&
        texture->uploaded_generation == renderer->backend_generation) {
        return KD_OK;
    }

    kd_status status;
    switch (renderer->backend) {
        case KD_JVM_VULKAN:
            status = kd_vk_frame_upload_texture(
                renderer->vulkan,
                texture->id,
                texture->width,
                texture->height,
                texture->pixels
            );
            break;
        case KD_JVM_D3D9:
            status = kd_d3d9_frame_upload_texture(
                renderer->d3d9,
                texture->id,
                texture->width,
                texture->height,
                texture->pixels
            );
            break;
        case KD_JVM_OPENGL:
            status = kd_gl_frame_upload_texture(
                renderer->opengl,
                texture->id,
                texture->width,
                texture->height,
                texture->pixels
            );
            break;
        case KD_JVM_METAL:
            status = kd_metal_frame_upload_texture(
                renderer->metal,
                texture->id,
                texture->width,
                texture->height,
                texture->pixels
            );
            break;
        default:
            return KD_DRAW_UNSUPPORTED_OPERATION;
    }

    if (status == KD_OK) {
        texture->uploaded_revision = texture->revision;
        texture->uploaded_generation =
            renderer->backend_generation;
    }
    return status;
}

static void kd_jvm_destroy_backend(kd_jvm_renderer *renderer) {
    if (!renderer) return;
    kd_vk_frame_destroy(renderer->vulkan);
    kd_gl_frame_destroy(renderer->opengl);
    kd_d3d9_frame_destroy(renderer->d3d9);
    kd_metal_frame_destroy(renderer->metal);
    renderer->vulkan = NULL;
    renderer->opengl = NULL;
    renderer->d3d9 = NULL;
    renderer->metal = NULL;
}

static kd_status kd_jvm_create_backend(kd_jvm_renderer *renderer) {
    if (!renderer || !renderer->window) return KD_INVALID_ARGUMENT;

    if (renderer->requested_backend == KD_JVM_AUTO) {
#if defined(__APPLE__)
        renderer->backend = KD_JVM_METAL;
        return kd_jvm_backend_created(
            renderer,
            kd_metal_frame_create(renderer->window, &renderer->metal)
        );
#elif defined(_WIN32)
        kd_status status;

        renderer->backend = KD_JVM_VULKAN;
        status = kd_vk_frame_create(renderer->window, &renderer->vulkan);
        if (status == KD_OK) {
            return kd_jvm_backend_created(renderer, status);
        }
        kd_jvm_destroy_backend(renderer);

        renderer->backend = KD_JVM_D3D9;
        status = kd_d3d9_frame_create(renderer->window, &renderer->d3d9);
        if (status == KD_OK) {
            return kd_jvm_backend_created(renderer, status);
        }
        kd_jvm_destroy_backend(renderer);

        renderer->backend = KD_JVM_OPENGL;
        status = kd_gl_frame_create(renderer->window, &renderer->opengl);
        if (status == KD_OK) {
            return kd_jvm_backend_created(renderer, status);
        }
        kd_jvm_destroy_backend(renderer);

        renderer->backend = KD_JVM_GDI;
        return kd_jvm_backend_created(renderer, KD_OK);
#elif defined(__linux__)
        renderer->backend = KD_JVM_VULKAN;
        return kd_jvm_backend_created(
            renderer,
            kd_vk_frame_create(renderer->window, &renderer->vulkan)
        );
#else
        return KD_UNSUPPORTED_PLATFORM;
#endif
    }

    renderer->backend = renderer->requested_backend;
    switch (renderer->backend) {
        case KD_JVM_VULKAN:
            return kd_jvm_backend_created(
                renderer,
                kd_vk_frame_create(renderer->window, &renderer->vulkan)
            );
        case KD_JVM_METAL:
            return kd_jvm_backend_created(
                renderer,
                kd_metal_frame_create(renderer->window, &renderer->metal)
            );
        case KD_JVM_OPENGL:
            return kd_jvm_backend_created(
                renderer,
                kd_gl_frame_create(renderer->window, &renderer->opengl)
            );
        case KD_JVM_D3D9:
            return kd_jvm_backend_created(
                renderer,
                kd_d3d9_frame_create(renderer->window, &renderer->d3d9)
            );
        case KD_JVM_GDI:
            return kd_jvm_backend_created(renderer, KD_OK);
        default:
            return KD_INVALID_ARGUMENT;
    }
}

static kd_status kd_jvm_fallback_backend(kd_jvm_renderer *renderer) {
    if (!renderer) return KD_INVALID_ARGUMENT;
    if (renderer->requested_backend != KD_JVM_AUTO) {
        return KD_GRAPHICS_ERROR;
    }

    kd_jvm_destroy_backend(renderer);

#if defined(_WIN32)
    kd_status status = KD_GRAPHICS_ERROR;

    if (renderer->backend == KD_JVM_VULKAN) {
        renderer->backend = KD_JVM_D3D9;
        status = kd_d3d9_frame_create(renderer->window, &renderer->d3d9);
        if (status == KD_OK) return KD_OK;
        kd_jvm_destroy_backend(renderer);
    }

    if (renderer->backend == KD_JVM_D3D9 ||
        renderer->backend == KD_JVM_VULKAN) {
        renderer->backend = KD_JVM_OPENGL;
        status = kd_gl_frame_create(renderer->window, &renderer->opengl);
        if (status == KD_OK) return KD_OK;
        kd_jvm_destroy_backend(renderer);
    }

    // AUTO fallback stays on GPU backends.
    return status;
#else
    return KD_GRAPHICS_ERROR;
#endif
}

static int kd_jvm_backend_missing(const kd_jvm_renderer *renderer) {
    if (!renderer) return 1;
    switch (renderer->backend) {
        case KD_JVM_VULKAN: return renderer->vulkan == NULL;
        case KD_JVM_METAL: return renderer->metal == NULL;
        case KD_JVM_OPENGL: return renderer->opengl == NULL;
        case KD_JVM_D3D9: return renderer->d3d9 == NULL;
        case KD_JVM_GDI: return 0;
        default: return 1;
    }
}

static kd_status kd_jvm_present_backend(
    kd_jvm_renderer *renderer,
    const kd_draw_command *commands,
    uint32_t count
) {
    if (!renderer) return KD_INVALID_ARGUMENT;

    for (uint32_t i = 0; i < count; ++i) {
        uint32_t base_kind = KD_DRAW_BASE_KIND(commands[i].kind);
        if (base_kind != KD_DRAW_IMAGE &&
            base_kind != KD_DRAW_GLYPH) {
            continue;
        }
        kd_status uploaded = kd_jvm_ensure_texture(
            renderer,
            KD_DRAW_RESOURCE_ID(commands[i].kind)
        );
        if (uploaded != KD_OK) return uploaded;
    }

    switch (renderer->backend) {
        case KD_JVM_VULKAN:
            return kd_vk_frame_present_commands(renderer->vulkan, commands, count);
        case KD_JVM_METAL:
            return kd_metal_frame_present_commands(renderer->metal, commands, count);
        case KD_JVM_OPENGL:
            return kd_gl_frame_present_commands(renderer->opengl, commands, count);
        case KD_JVM_D3D9:
            return kd_d3d9_frame_present_commands(renderer->d3d9, commands, count);
        case KD_JVM_GDI:
            return kd_gdi_frame_present_commands(renderer->window, commands, count);
        default:
            return KD_INVALID_ARGUMENT;
    }
}

static void kd_jvm_destroy(kd_jvm_renderer *renderer) {
    if (!renderer) return;
    kd_jvm_destroy_backend(renderer);
    kd_jvm_destroy_textures(renderer);
    kd_window_destroy(renderer->window);
    free(renderer);
}

JNIEXPORT jlong JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_create(
    JNIEnv *env,
    jclass clazz,
    jint width,
    jint height,
    jstring title,
    jint backend
) {
    (void)clazz;
    if (width <= 0 || height <= 0 || !title) return 0;

    const char *native_title = (*env)->GetStringUTFChars(env, title, NULL);
    if (!native_title) return 0;

    kd_jvm_renderer *renderer = calloc(1, sizeof(*renderer));
    if (!renderer) {
        (*env)->ReleaseStringUTFChars(env, title, native_title);
        return 0;
    }
    renderer->requested_backend = backend;
    renderer->backend = backend;

    kd_status status = kd_window_create(
        (uint32_t)width,
        (uint32_t)height,
        native_title,
        &renderer->window
    );
    (*env)->ReleaseStringUTFChars(env, title, native_title);
    if (status != KD_OK) {
        kd_jvm_destroy(renderer);
        return 0;
    }

    status = kd_jvm_create_backend(renderer);

    if (status != KD_OK) {
        kd_jvm_destroy(renderer);
        return 0;
    }

    return (jlong)(intptr_t)renderer;
}

JNIEXPORT jlong JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_createAttached(
    JNIEnv *env,
    jclass clazz,
    jobject host,
    jint width,
    jint height,
    jint backend
) {
    (void)clazz;
#if defined(__APPLE__)
    if (!host || width <= 0 || height <= 0) return 0;

    kd_jvm_renderer *renderer = calloc(1, sizeof(*renderer));
    if (!renderer) return 0;
    renderer->requested_backend = backend;
    renderer->backend = backend;

    kd_status status = kd_jvm_create_awt_child(
        env,
        host,
        (uint32_t)width,
        (uint32_t)height,
        &renderer->window
    );
    if (status != KD_OK) {
        kd_jvm_destroy(renderer);
        return 0;
    }

    status = kd_jvm_create_backend(renderer);
    if (status != KD_OK) {
        kd_jvm_destroy(renderer);
        return 0;
    }

    return (jlong)(intptr_t)renderer;
#elif defined(__linux__)
    if (!host || width <= 0 || height <= 0) return 0;

    uintptr_t parent = kd_jvm_awt_x11_window(env, host);
    if (!parent) return 0;

    kd_jvm_renderer *renderer = calloc(1, sizeof(*renderer));
    if (!renderer) return 0;
    renderer->requested_backend = backend;
    renderer->backend = backend;

    kd_status status = kd_window_create_child(
        parent,
        (uint32_t)width,
        (uint32_t)height,
        &renderer->window
    );
    if (status != KD_OK) {
        kd_jvm_destroy(renderer);
        return 0;
    }

    status = kd_jvm_create_backend(renderer);
    if (status != KD_OK) {
        kd_jvm_destroy(renderer);
        return 0;
    }

    return (jlong)(intptr_t)renderer;
#elif defined(_WIN32)
    if (!host || width <= 0 || height <= 0) return 0;

    HWND host_hwnd = kd_jvm_awt_hwnd(env, host);
    if (!host_hwnd) return 0;

    HWND parent = GetParent(host_hwnd);
    if (!parent) parent = host_hwnd;

    RECT host_rect;
    if (!GetWindowRect(host_hwnd, &host_rect)) return 0;

    POINT corners[2] = {
        {host_rect.left, host_rect.top},
        {host_rect.right, host_rect.bottom}
    };
    if (parent != host_hwnd) {
        MapWindowPoints(HWND_DESKTOP, parent, corners, 2);
    } else {
        corners[0].x = 0;
        corners[0].y = 0;
        corners[1].x = width;
        corners[1].y = height;
    }

    int overlay_width = corners[1].x - corners[0].x;
    int overlay_height = corners[1].y - corners[0].y;
    if (overlay_width <= 0) overlay_width = width;
    if (overlay_height <= 0) overlay_height = height;

    kd_jvm_renderer *renderer = calloc(1, sizeof(*renderer));
    if (!renderer) return 0;
    renderer->requested_backend = backend;
    renderer->backend = backend;

    kd_status status = kd_window_create_child(
        (uintptr_t)parent,
        (uint32_t)overlay_width,
        (uint32_t)overlay_height,
        &renderer->window
    );
    if (status != KD_OK) {
        kd_jvm_destroy(renderer);
        return 0;
    }

    kd_native_window_handles overlay_handles = {0};
    if (kd_window_get_native_handles(
            renderer->window,
            &overlay_handles
        ) == KD_OK &&
        overlay_handles.platform == KD_WINDOW_PLATFORM_WIN32 &&
        overlay_handles.window) {
        HWND overlay = (HWND)(uintptr_t)overlay_handles.window;
        SetWindowPos(
            overlay,
            HWND_TOP,
            corners[0].x,
            corners[0].y,
            overlay_width,
            overlay_height,
            SWP_SHOWWINDOW | SWP_NOACTIVATE
        );
    }

    status = kd_jvm_create_backend(renderer);
    if (status != KD_OK) {
        kd_jvm_destroy(renderer);
        return 0;
    }

    return (jlong)(intptr_t)renderer;
#else
    (void)env; (void)host; (void)width; (void)height; (void)backend;
    return 0;
#endif
}

JNIEXPORT jint JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_resize(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jint width,
    jint height
) {
    (void)env; (void)clazz;
    kd_jvm_renderer *renderer = (kd_jvm_renderer *)(intptr_t)handle;
    if (!renderer || width <= 0 || height <= 0) {
        return (jint)KD_INVALID_ARGUMENT;
    }
    kd_status status = kd_window_resize(
        renderer->window,
        (uint32_t)width,
        (uint32_t)height
    );
    if (status == KD_OK &&
        (renderer->backend == KD_JVM_VULKAN ||
         renderer->backend == KD_JVM_D3D9)) {
        kd_jvm_destroy_backend(renderer);
    }
    return (jint)status;
}

JNIEXPORT jboolean JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_poll(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env; (void)clazz;
    kd_jvm_renderer *renderer = (kd_jvm_renderer *)(intptr_t)handle;
    if (!renderer) return JNI_FALSE;
    int should_close = 0;
    if (kd_window_poll(renderer->window, &should_close) != KD_OK) return JNI_FALSE;
    return should_close ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_size(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env; (void)clazz;
    kd_jvm_renderer *renderer = (kd_jvm_renderer *)(intptr_t)handle;
    if (!renderer) return 0;
    uint32_t width = 0, height = 0;
    if (kd_window_get_size(renderer->window, &width, &height) != KD_OK) return 0;
    return ((jlong)width << 32) | (jlong)height;
}

JNIEXPORT jboolean JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_nextPointer(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jintArray output
) {
    (void)clazz;
    kd_jvm_renderer *renderer = (kd_jvm_renderer *)(intptr_t)handle;
    if (!renderer || !output || (*env)->GetArrayLength(env, output) < 3) return JNI_FALSE;

    kd_pointer_event event = {0};
    int pending = 0;
    kd_status status = kd_window_next_pointer(renderer->window, &event, &pending);
    if (status == KD_INPUT_OVERFLOW) {
        pending = 0;
        status = kd_window_next_pointer(renderer->window, &event, &pending);
    }
    if (status != KD_OK || !pending) return JNI_FALSE;

    jint values[3] = {(jint)event.kind, (jint)event.x, (jint)event.y};
    (*env)->SetIntArrayRegion(env, output, 0, 3, values);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_activeBackend(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env; (void)clazz;
    kd_jvm_renderer *renderer =
        (kd_jvm_renderer *)(intptr_t)handle;
    return renderer ? (jint)renderer->backend : 0;
}

JNIEXPORT jint JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_uploadTexture(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jint texture_id,
    jint width,
    jint height,
    jintArray pixels
) {
    (void)clazz;
    kd_jvm_renderer *renderer =
        (kd_jvm_renderer *)(intptr_t)handle;
    if (!renderer ||
        texture_id <= 0 ||
        texture_id > (jint)KD_DRAW_RESOURCE_MAX ||
        width <= 0 ||
        height <= 0 ||
        !pixels) {
        return (jint)KD_INVALID_ARGUMENT;
    }

    const jsize required = (jsize)((int64_t)width * height);
    if (required <= 0 ||
        (*env)->GetArrayLength(env, pixels) != required) {
        return (jint)KD_INVALID_ARGUMENT;
    }

    jint *source =
        (*env)->GetIntArrayElements(env, pixels, NULL);
    if (!source) return (jint)KD_OUT_OF_MEMORY;

    size_t bytes =
        (size_t)required * sizeof(uint32_t);
    uint32_t *copy = (uint32_t *)malloc(bytes);
    if (!copy) {
        (*env)->ReleaseIntArrayElements(
            env,
            pixels,
            source,
            JNI_ABORT
        );
        return (jint)KD_OUT_OF_MEMORY;
    }
    memcpy(copy, source, bytes);
    (*env)->ReleaseIntArrayElements(
        env,
        pixels,
        source,
        JNI_ABORT
    );

    kd_jvm_texture *entry =
        kd_jvm_find_texture(
            renderer,
            (uint32_t)texture_id
        );
    if (!entry) {
        entry = (kd_jvm_texture *)calloc(
            1,
            sizeof(*entry)
        );
        if (!entry) {
            free(copy);
            return (jint)KD_OUT_OF_MEMORY;
        }
        entry->id = (uint32_t)texture_id;
        entry->next = renderer->textures;
        renderer->textures = entry;
    }

    free(entry->pixels);
    entry->pixels = copy;
    entry->width = (uint32_t)width;
    entry->height = (uint32_t)height;
    entry->revision++;
    if (entry->revision == 0) entry->revision = 1;

    return (jint)kd_jvm_ensure_texture(
        renderer,
        entry->id
    );
}

JNIEXPORT jint JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_uploadTextureAddress(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jint texture_id,
    jint width,
    jint height,
    jlong address,
    jint row_bytes
) {
    (void)env;
    (void)clazz;
    kd_jvm_renderer *renderer =
        (kd_jvm_renderer *)(intptr_t)handle;
    if (!renderer ||
        texture_id <= 0 ||
        texture_id > (jint)KD_DRAW_RESOURCE_MAX ||
        width <= 0 ||
        height <= 0 ||
        address == 0 ||
        row_bytes < width * (jint)sizeof(uint32_t)) {
        return (jint)KD_INVALID_ARGUMENT;
    }

    size_t pixel_count = (size_t)width * (size_t)height;
    if (pixel_count == 0 ||
        pixel_count > SIZE_MAX / sizeof(uint32_t)) {
        return (jint)KD_INVALID_ARGUMENT;
    }

    uint32_t *copy =
        (uint32_t *)malloc(pixel_count * sizeof(uint32_t));
    if (!copy) return (jint)KD_OUT_OF_MEMORY;

    const uint8_t *source =
        (const uint8_t *)(uintptr_t)address;
    for (jint y = 0; y < height; ++y) {
        memcpy(
            copy + (size_t)y * (size_t)width,
            source + (size_t)y * (size_t)row_bytes,
            (size_t)width * sizeof(uint32_t)
        );
    }

    kd_jvm_texture *entry =
        kd_jvm_find_texture(
            renderer,
            (uint32_t)texture_id
        );
    if (!entry) {
        entry = (kd_jvm_texture *)calloc(
            1,
            sizeof(*entry)
        );
        if (!entry) {
            free(copy);
            return (jint)KD_OUT_OF_MEMORY;
        }
        entry->id = (uint32_t)texture_id;
        entry->next = renderer->textures;
        renderer->textures = entry;
    }

    free(entry->pixels);
    entry->pixels = copy;
    entry->width = (uint32_t)width;
    entry->height = (uint32_t)height;
    entry->revision++;
    if (entry->revision == 0) entry->revision = 1;

    return (jint)kd_jvm_ensure_texture(
        renderer,
        entry->id
    );
}

JNIEXPORT void JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_releaseTexture(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jint texture_id
) {
    (void)env; (void)clazz;
    kd_jvm_renderer *renderer =
        (kd_jvm_renderer *)(intptr_t)handle;
    if (!renderer || texture_id <= 0) return;

    kd_jvm_texture **link = &renderer->textures;
    while (*link) {
        kd_jvm_texture *entry = *link;
        if (entry->id == (uint32_t)texture_id) {
            *link = entry->next;
            if (renderer->backend == KD_JVM_VULKAN &&
                renderer->vulkan) {
                kd_vk_frame_release_texture(
                    renderer->vulkan,
                    entry->id
                );
            } else if (renderer->backend == KD_JVM_D3D9 &&
                renderer->d3d9) {
                kd_d3d9_frame_release_texture(
                    renderer->d3d9,
                    entry->id
                );
            } else if (
                renderer->backend == KD_JVM_OPENGL &&
                renderer->opengl
            ) {
                kd_gl_frame_release_texture(
                    renderer->opengl,
                    entry->id
                );
            } else if (
                renderer->backend == KD_JVM_METAL &&
                renderer->metal
            ) {
                kd_metal_frame_release_texture(
                    renderer->metal,
                    entry->id
                );
            }
            free(entry->pixels);
            free(entry);
            return;
        }
        link = &entry->next;
    }
}

JNIEXPORT jint JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_present(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jintArray kinds,
    jfloatArray geometry,
    jfloatArray colors,
    jfloatArray uvs
) {
    (void)clazz;
    kd_jvm_renderer *renderer = (kd_jvm_renderer *)(intptr_t)handle;
    if (!renderer || !kinds || !geometry || !colors || !uvs) {
        return (jint)KD_INVALID_ARGUMENT;
    }

    const jsize count = (*env)->GetArrayLength(env, kinds);
    if (count <= 0 || count > (jsize)KD_DRAW_MAX_COMMANDS ||
        (*env)->GetArrayLength(env, geometry) != count * 4 ||
        (*env)->GetArrayLength(env, colors) != count * 4 ||
        (*env)->GetArrayLength(env, uvs) != count * 4) {
        return (jint)KD_INVALID_ARGUMENT;
    }

    jint *native_kinds = (*env)->GetIntArrayElements(env, kinds, NULL);
    jfloat *native_geometry = (*env)->GetFloatArrayElements(env, geometry, NULL);
    jfloat *native_colors = (*env)->GetFloatArrayElements(env, colors, NULL);
    jfloat *native_uvs = (*env)->GetFloatArrayElements(env, uvs, NULL);
    if (!native_kinds || !native_geometry || !native_colors || !native_uvs) {
        if (native_kinds) (*env)->ReleaseIntArrayElements(env, kinds, native_kinds, JNI_ABORT);
        if (native_geometry) (*env)->ReleaseFloatArrayElements(env, geometry, native_geometry, JNI_ABORT);
        if (native_colors) (*env)->ReleaseFloatArrayElements(env, colors, native_colors, JNI_ABORT);
        if (native_uvs) (*env)->ReleaseFloatArrayElements(env, uvs, native_uvs, JNI_ABORT);
        return (jint)KD_OUT_OF_MEMORY;
    }

    kd_draw_command *commands = calloc((size_t)count, sizeof(*commands));
    if (!commands) {
        (*env)->ReleaseIntArrayElements(env, kinds, native_kinds, JNI_ABORT);
        (*env)->ReleaseFloatArrayElements(env, geometry, native_geometry, JNI_ABORT);
        (*env)->ReleaseFloatArrayElements(env, colors, native_colors, JNI_ABORT);
        (*env)->ReleaseFloatArrayElements(env, uvs, native_uvs, JNI_ABORT);
        return (jint)KD_OUT_OF_MEMORY;
    }

    for (jsize i = 0; i < count; ++i) {
        commands[i].kind = (uint32_t)native_kinds[i];
        commands[i].x = native_geometry[i * 4 + 0];
        commands[i].y = native_geometry[i * 4 + 1];
        commands[i].width = native_geometry[i * 4 + 2];
        commands[i].height = native_geometry[i * 4 + 3];
        commands[i].color.red = native_colors[i * 4 + 0];
        commands[i].color.green = native_colors[i * 4 + 1];
        commands[i].color.blue = native_colors[i * 4 + 2];
        commands[i].color.alpha = native_colors[i * 4 + 3];
        commands[i].u0 = native_uvs[i * 4 + 0];
        commands[i].v0 = native_uvs[i * 4 + 1];
        commands[i].u1 = native_uvs[i * 4 + 2];
        commands[i].v1 = native_uvs[i * 4 + 3];
    }

    (*env)->ReleaseIntArrayElements(env, kinds, native_kinds, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, geometry, native_geometry, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, colors, native_colors, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, uvs, native_uvs, JNI_ABORT);

    kd_status status = KD_OK;
    if (kd_jvm_backend_missing(renderer)) {
        status = kd_jvm_create_backend(renderer);
    }
    if (status == KD_OK) {
        status = kd_jvm_present_backend(
            renderer,
            commands,
            (uint32_t)count
        );
    }

    if (status == KD_SWAPCHAIN_OUT_OF_DATE &&
        (renderer->backend == KD_JVM_VULKAN ||
         renderer->backend == KD_JVM_D3D9)) {
        int failed_backend = renderer->backend;
        kd_jvm_destroy_backend(renderer);

        // Retry the current backend first.
        renderer->backend = failed_backend;
        if (failed_backend == KD_JVM_VULKAN) {
            status = kd_vk_frame_create(renderer->window, &renderer->vulkan);
        } else {
            status = kd_d3d9_frame_create(renderer->window, &renderer->d3d9);
        }

        if (status == KD_OK) {
            kd_jvm_backend_created(renderer, status);
            status = kd_jvm_present_backend(
                renderer,
                commands,
                (uint32_t)count
            );
        }

        // AUTO can fall through to the next GPU backend.
        if ((status == KD_GRAPHICS_ERROR ||
             status == KD_SWAPCHAIN_OUT_OF_DATE) &&
            renderer->requested_backend == KD_JVM_AUTO) {
            renderer->backend = failed_backend;
            kd_status fallback = kd_jvm_fallback_backend(renderer);
            if (fallback == KD_OK) {
                status = kd_jvm_present_backend(
                    renderer,
                    commands,
                    (uint32_t)count
                );
            } else {
                status = fallback;
            }
        }
    } else if (status == KD_GRAPHICS_ERROR &&
               renderer->requested_backend == KD_JVM_AUTO) {
        kd_status fallback = kd_jvm_fallback_backend(renderer);
        if (fallback == KD_OK) {
            status = kd_jvm_present_backend(
                renderer,
                commands,
                (uint32_t)count
            );
        } else {
            status = fallback;
        }
    }

    free(commands);
    return (jint)status;
}

JNIEXPORT void JNICALL
Java_dev_yurie_display_composebridge_nativebridge_NativeGpuBridge_destroy(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env; (void)clazz;
    kd_jvm_destroy((kd_jvm_renderer *)(intptr_t)handle);
}

#endif
