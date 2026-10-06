#include "kotlin_display.h"

#if defined(__APPLE__)

#import <AppKit/AppKit.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <dispatch/dispatch.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct kd_metal_vertex {
    float red, green, blue, alpha;
    float x, y;
    float u, v;
} kd_metal_vertex;

typedef struct kd_metal_texture {
    uint32_t id;
    id<MTLTexture> texture;
    struct kd_metal_texture *next;
} kd_metal_texture;

struct kd_metal_frame {
    NSView *view;
    CAMetalLayer *layer;
    BOOL attached_layer;
    id<MTLDevice> device;
    id<MTLCommandQueue> queue;
    id<MTLRenderPipelineState> pipeline;
    id<MTLRenderPipelineState> clear_pipeline;
    id<MTLRenderPipelineState> texture_pipeline;
    id<MTLSamplerState> sampler;
    kd_metal_texture *textures;
    id<MTLTexture> backing_texture;
    NSUInteger backing_width;
    NSUInteger backing_height;
    BOOL backing_valid;
};

static int kd_valid_color(kd_draw_color color) {
    const float values[] = {color.red, color.green, color.blue, color.alpha};
    for (unsigned i = 0; i < 4; ++i) {
        if (!isfinite(values[i]) || values[i] < 0.f || values[i] > 1.f) return 0;
    }
    return 1;
}

static int kd_valid_rect(const kd_draw_command *command) {
    if (!command) return 0;
    const uint32_t kind = KD_DRAW_BASE_KIND(command->kind);
    return
        (kind == KD_DRAW_RECT ||
         kind == KD_DRAW_CLEAR_RECT ||
         kind == KD_DRAW_IMAGE ||
         kind == KD_DRAW_GLYPH) &&
        isfinite(command->x) && isfinite(command->y) &&
        isfinite(command->width) && isfinite(command->height) &&
        isfinite(command->u0) && isfinite(command->v0) &&
        isfinite(command->u1) && isfinite(command->v1) &&
        command->width >= 0.f && command->height >= 0.f &&
        kd_valid_color(command->color);
}

static kd_metal_texture *kd_metal_find_texture(
    kd_metal_frame *frame,
    uint32_t id
) {
    for (kd_metal_texture *entry = frame ? frame->textures : NULL;
         entry;
         entry = entry->next) {
        if (entry->id == id) return entry;
    }
    return NULL;
}

static void kd_metal_destroy_textures(kd_metal_frame *frame) {
    if (!frame) return;
    kd_metal_texture *entry = frame->textures;
    while (entry) {
        kd_metal_texture *next = entry->next;
        entry->texture = nil;
        free(entry);
        entry = next;
    }
    frame->textures = NULL;
}

static void kd_write_rect(
    kd_metal_vertex *vertices,
    const kd_draw_command *command
) {
    const float x0 = command->x;
    const float y0 = command->y;
    const float x1 = command->x + command->width;
    const float y1 = command->y + command->height;
    const uint32_t kind = KD_DRAW_BASE_KIND(command->kind);
    const kd_draw_color c =
        kind == KD_DRAW_CLEAR_RECT
            ? (kd_draw_color){0.f, 0.f, 0.f, 0.f}
            : kind == KD_DRAW_IMAGE
                ? (kd_draw_color){1.f, 1.f, 1.f, 1.f}
                : command->color;

    const kd_metal_vertex quad[6] = {
        {c.red, c.green, c.blue, c.alpha, x0, y0, command->u0, command->v0},
        {c.red, c.green, c.blue, c.alpha, x1, y0, command->u1, command->v0},
        {c.red, c.green, c.blue, c.alpha, x0, y1, command->u0, command->v1},
        {c.red, c.green, c.blue, c.alpha, x1, y0, command->u1, command->v0},
        {c.red, c.green, c.blue, c.alpha, x1, y1, command->u1, command->v1},
        {c.red, c.green, c.blue, c.alpha, x0, y1, command->u0, command->v1},
    };
    memcpy(vertices, quad, sizeof(quad));
}

kd_status kd_metal_frame_create(kd_window *window, kd_metal_frame **out) {
    if (![NSThread isMainThread]) {
        __block kd_status result = KD_GRAPHICS_ERROR;
        dispatch_sync(dispatch_get_main_queue(), ^{
            result = kd_metal_frame_create(window, out);
        });
        return result;
    }
    if (!window || !out) return KD_INVALID_ARGUMENT;
    *out = NULL;

    kd_native_window_handles handles = {0};
    kd_status status = kd_window_get_native_handles(window, &handles);
    if (status != KD_OK) return status;
    if (handles.platform != KD_WINDOW_PLATFORM_MACOS || !handles.display) {
        return KD_UNSUPPORTED_PLATFORM;
    }

    @autoreleasepool {
        id display = (__bridge id)handles.display;
        NSView *view = nil;
        CAMetalLayer *layer = nil;
        BOOL attached_layer = handles.window == 0 &&
            [display isKindOfClass:[CAMetalLayer class]];

        if (attached_layer) {
            layer = (CAMetalLayer *)display;
        } else {
            view = (NSView *)display;
            layer = [CAMetalLayer layer];
        }

        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (!device) return KD_NO_GRAPHICS_DEVICE;

        layer.device = device;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        // Retained frames use a persistent backing texture.
        layer.framebufferOnly = NO;
        layer.opaque = NO;

        NSWindow *nativeWindow = view ? view.window : nil;
        CGFloat scale = nativeWindow
            ? nativeWindow.backingScaleFactor
            : layer.contentsScale;
        if (scale <= 0.0) {
            scale = NSScreen.mainScreen.backingScaleFactor;
        }
        if (scale <= 0.0) scale = 1.0;
        layer.contentsScale = scale;

        if (view) {
            layer.frame = view.bounds;
            layer.drawableSize = CGSizeMake(
                view.bounds.size.width * scale,
                view.bounds.size.height * scale
            );
            view.wantsLayer = YES;
            view.layer = layer;
        } else {
            layer.drawableSize = CGSizeMake(
                layer.bounds.size.width * scale,
                layer.bounds.size.height * scale
            );
        }

        static NSString *source =
            @"#include <metal_stdlib>\n"
             "using namespace metal;\n"
             "struct KDVertex { float4 color; float2 position; float2 uv; };\n"
             "struct KDOut { float4 position [[position]]; float4 color; float2 uv; };\n"
             "vertex KDOut kd_vertex(const device KDVertex *v [[buffer(0)]],\n"
             "                       constant float2 &viewport [[buffer(1)]],\n"
             "                       uint id [[vertex_id]]) {\n"
             "  KDOut o;\n"
             "  float2 p = v[id].position;\n"
             "  o.position = float4((p.x / viewport.x) * 2.0 - 1.0,\n"
             "                      1.0 - (p.y / viewport.y) * 2.0, 0.0, 1.0);\n"
             "  o.color = v[id].color;\n"
             "  o.uv = v[id].uv;\n"
             "  return o;\n"
             "}\n"
             "fragment float4 kd_fragment(KDOut in [[stage_in]]) { return in.color; }\n"
             "fragment float4 kd_texture_fragment(\n"
             "    KDOut in [[stage_in]],\n"
             "    texture2d<float> image [[texture(0)]],\n"
             "    sampler imageSampler [[sampler(0)]]) {\n"
             "  return image.sample(imageSampler, in.uv) * in.color;\n"
             "}\n";

        NSError *error = nil;
        id<MTLLibrary> library = [device newLibraryWithSource:source options:nil error:&error];
        if (!library) {
            NSLog(@"Kotlin Display Metal shader compile failed: %@", error);
            return KD_GRAPHICS_ERROR;
        }

        id<MTLFunction> vertex = [library newFunctionWithName:@"kd_vertex"];
        id<MTLFunction> fragment = [library newFunctionWithName:@"kd_fragment"];
        id<MTLFunction> texture_fragment =
            [library newFunctionWithName:@"kd_texture_fragment"];
        if (!vertex || !fragment || !texture_fragment) {
            return KD_GRAPHICS_ERROR;
        }

        MTLRenderPipelineDescriptor *descriptor = [[MTLRenderPipelineDescriptor alloc] init];
        descriptor.vertexFunction = vertex;
        descriptor.fragmentFunction = fragment;
        descriptor.colorAttachments[0].pixelFormat = layer.pixelFormat;
        descriptor.colorAttachments[0].blendingEnabled = YES;
        descriptor.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
        descriptor.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
        descriptor.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
        descriptor.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
        descriptor.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
        descriptor.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;

        id<MTLRenderPipelineState> pipeline =
            [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
        if (!pipeline) {
            NSLog(@"Kotlin Display Metal pipeline failed: %@", error);
            return KD_GRAPHICS_ERROR;
        }

        descriptor.colorAttachments[0].blendingEnabled = NO;
        id<MTLRenderPipelineState> clear_pipeline =
            [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
        if (!clear_pipeline) {
            NSLog(@"Kotlin Display Metal clear pipeline failed: %@", error);
            return KD_GRAPHICS_ERROR;
        }

        descriptor.fragmentFunction = texture_fragment;
        descriptor.colorAttachments[0].blendingEnabled = YES;
        // Skia snapshots are premultiplied BGRA.
        descriptor.colorAttachments[0].sourceRGBBlendFactor =
            MTLBlendFactorOne;
        descriptor.colorAttachments[0].destinationRGBBlendFactor =
            MTLBlendFactorOneMinusSourceAlpha;
        descriptor.colorAttachments[0].sourceAlphaBlendFactor =
            MTLBlendFactorOne;
        descriptor.colorAttachments[0].destinationAlphaBlendFactor =
            MTLBlendFactorOneMinusSourceAlpha;
        id<MTLRenderPipelineState> texture_pipeline =
            [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
        if (!texture_pipeline) {
            NSLog(@"Kotlin Display Metal texture pipeline failed: %@", error);
            return KD_GRAPHICS_ERROR;
        }

        MTLSamplerDescriptor *sampler_descriptor =
            [[MTLSamplerDescriptor alloc] init];
        sampler_descriptor.minFilter = MTLSamplerMinMagFilterLinear;
        sampler_descriptor.magFilter = MTLSamplerMinMagFilterLinear;
        sampler_descriptor.sAddressMode = MTLSamplerAddressModeClampToEdge;
        sampler_descriptor.tAddressMode = MTLSamplerAddressModeClampToEdge;
        id<MTLSamplerState> sampler =
            [device newSamplerStateWithDescriptor:sampler_descriptor];
        if (!sampler) return KD_GRAPHICS_ERROR;

        id<MTLCommandQueue> queue = [device newCommandQueue];
        if (!queue) return KD_GRAPHICS_ERROR;

        kd_metal_frame *frame = calloc(1, sizeof(*frame));
        if (!frame) return KD_OUT_OF_MEMORY;
        frame->view = view;
        frame->layer = layer;
        frame->attached_layer = attached_layer;
        frame->device = device;
        frame->queue = queue;
        frame->pipeline = pipeline;
        frame->clear_pipeline = clear_pipeline;
        frame->texture_pipeline = texture_pipeline;
        frame->sampler = sampler;
        *out = frame;
    }
    return KD_OK;
}


kd_status kd_metal_frame_upload_texture(
    kd_metal_frame *frame,
    uint32_t texture_id,
    uint32_t width,
    uint32_t height,
    const uint32_t *argb
) {
    if (!frame || !texture_id || !width || !height || !argb) {
        return KD_INVALID_ARGUMENT;
    }

    kd_metal_texture *entry =
        kd_metal_find_texture(frame, texture_id);
    if (!entry) {
        entry = (kd_metal_texture *)calloc(1, sizeof(*entry));
        if (!entry) return KD_OUT_OF_MEMORY;
        entry->id = texture_id;
        entry->next = frame->textures;
        frame->textures = entry;
    }

    MTLTextureDescriptor *descriptor =
        [MTLTextureDescriptor
            texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
            width:width
            height:height
            mipmapped:NO];
    descriptor.storageMode = MTLStorageModeShared;
    descriptor.usage = MTLTextureUsageShaderRead;

    id<MTLTexture> texture =
        [frame->device newTextureWithDescriptor:descriptor];
    if (!texture) return KD_OUT_OF_MEMORY;

    [texture replaceRegion:MTLRegionMake2D(0, 0, width, height)
        mipmapLevel:0
        withBytes:argb
        bytesPerRow:(NSUInteger)width * sizeof(uint32_t)];

    entry->texture = texture;
    return KD_OK;
}

void kd_metal_frame_release_texture(
    kd_metal_frame *frame,
    uint32_t texture_id
) {
    if (!frame || !texture_id) return;
    kd_metal_texture **link = &frame->textures;
    while (*link) {
        kd_metal_texture *entry = *link;
        if (entry->id == texture_id) {
            *link = entry->next;
            entry->texture = nil;
            free(entry);
            return;
        }
        link = &entry->next;
    }
}

kd_status kd_metal_frame_present_commands(
    kd_metal_frame *frame,
    const kd_draw_command *commands,
    uint32_t count
) {
    if (![NSThread isMainThread]) {
        __block kd_status result = KD_GRAPHICS_ERROR;
        dispatch_sync(dispatch_get_main_queue(), ^{
            result = kd_metal_frame_present_commands(frame, commands, count);
        });
        return result;
    }
    if (!frame || !commands || count == 0 || count > KD_DRAW_MAX_COMMANDS) {
        return KD_INVALID_ARGUMENT;
    }
    const uint32_t first_kind = KD_DRAW_BASE_KIND(commands[0].kind);
    if ((first_kind != KD_DRAW_CLEAR &&
         first_kind != KD_DRAW_RETAIN) ||
        !kd_valid_color(commands[0].color)) {
        return KD_INVALID_ARGUMENT;
    }

    uint32_t rect_count = 0;
    for (uint32_t i = 1; i < count; ++i) {
        if (!kd_valid_rect(&commands[i])) return KD_INVALID_ARGUMENT;
        if (commands[i].width > 0.f && commands[i].height > 0.f) rect_count++;
    }

    @autoreleasepool {
        NSWindow *window = frame->view ? frame->view.window : nil;
        CGFloat scale = window
            ? window.backingScaleFactor
            : frame->layer.contentsScale;
        if (scale <= 0.0) scale = 1.0;
        frame->layer.contentsScale = scale;

        CGRect bounds = frame->view
            ? frame->view.bounds
            : frame->layer.bounds;
        if (frame->view) {
            frame->layer.frame = bounds;
        }
        frame->layer.drawableSize = CGSizeMake(
            bounds.size.width * scale,
            bounds.size.height * scale
        );

        id<CAMetalDrawable> drawable = [frame->layer nextDrawable];
        if (!drawable) return KD_SWAPCHAIN_OUT_OF_DATE;

        const float viewport[2] = {
            (float)drawable.texture.width,
            (float)drawable.texture.height
        };
        if (viewport[0] <= 0.f || viewport[1] <= 0.f) return KD_SWAPCHAIN_OUT_OF_DATE;

        kd_metal_vertex *cpu_vertices = NULL;
        id<MTLBuffer> vertex_buffer = nil;
        if (rect_count) {
            const size_t vertex_count = (size_t)rect_count * 6u;
            const size_t bytes = vertex_count * sizeof(kd_metal_vertex);
            cpu_vertices = malloc(bytes);
            if (!cpu_vertices) return KD_OUT_OF_MEMORY;

            uint32_t output = 0;
            for (uint32_t i = 1; i < count; ++i) {
                if (commands[i].width <= 0.f || commands[i].height <= 0.f) continue;
                kd_draw_command scaled = commands[i];
                scaled.x *= (float)scale;
                scaled.y *= (float)scale;
                scaled.width *= (float)scale;
                scaled.height *= (float)scale;
                kd_write_rect(cpu_vertices + ((size_t)output * 6u), &scaled);
                output++;
            }

            vertex_buffer = [frame->device newBufferWithBytes:cpu_vertices
                length:bytes
                options:MTLResourceStorageModeShared];
            free(cpu_vertices);
            if (!vertex_buffer) return KD_OUT_OF_MEMORY;
        }

        // Render retained frames into one persistent texture.
        if (!frame->backing_texture ||
            frame->backing_width != drawable.texture.width ||
            frame->backing_height != drawable.texture.height) {
            MTLTextureDescriptor *texture =
                [MTLTextureDescriptor
                    texture2DDescriptorWithPixelFormat:frame->layer.pixelFormat
                    width:drawable.texture.width
                    height:drawable.texture.height
                    mipmapped:NO];
            texture.storageMode = MTLStorageModePrivate;
            texture.usage =
                MTLTextureUsageRenderTarget |
                MTLTextureUsageShaderRead;
            frame->backing_texture =
                [frame->device newTextureWithDescriptor:texture];
            if (!frame->backing_texture) return KD_OUT_OF_MEMORY;
            frame->backing_width = drawable.texture.width;
            frame->backing_height = drawable.texture.height;
            frame->backing_valid = NO;
        }

        const BOOL can_retain =
            first_kind == KD_DRAW_RETAIN && frame->backing_valid;

        MTLRenderPassDescriptor *pass =
            [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = frame->backing_texture;
        pass.colorAttachments[0].loadAction =
            can_retain ? MTLLoadActionLoad : MTLLoadActionClear;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        if (!can_retain) {
            const kd_draw_color clear =
                first_kind == KD_DRAW_CLEAR
                    ? commands[0].color
                    : (kd_draw_color){0.f, 0.f, 0.f, 0.f};
            pass.colorAttachments[0].clearColor = MTLClearColorMake(
                clear.red,
                clear.green,
                clear.blue,
                clear.alpha
            );
        }

        id<MTLCommandBuffer> command = [frame->queue commandBuffer];
        if (!command) return KD_GRAPHICS_ERROR;

        id<MTLRenderCommandEncoder> encoder =
            [command renderCommandEncoderWithDescriptor:pass];
        if (!encoder) return KD_GRAPHICS_ERROR;

        if (rect_count) {
            [encoder setVertexBuffer:vertex_buffer offset:0 atIndex:0];
            [encoder setVertexBytes:viewport length:sizeof(viewport) atIndex:1];
            uint32_t output = 0;
            for (uint32_t i = 1; i < count; ++i) {
                if (commands[i].width <= 0.f || commands[i].height <= 0.f) continue;
                const uint32_t kind =
                    KD_DRAW_BASE_KIND(commands[i].kind);
                if (kind == KD_DRAW_IMAGE ||
                    kind == KD_DRAW_GLYPH) {
                    kd_metal_texture *texture =
                        kd_metal_find_texture(
                            frame,
                            KD_DRAW_RESOURCE_ID(commands[i].kind)
                        );
                    if (!texture || !texture->texture) {
                        [encoder endEncoding];
                        return KD_GRAPHICS_ERROR;
                    }
                    [encoder setRenderPipelineState:
                        frame->texture_pipeline];
                    [encoder setFragmentTexture:
                        texture->texture
                        atIndex:0];
                    [encoder setFragmentSamplerState:
                        frame->sampler
                        atIndex:0];
                } else {
                    [encoder setRenderPipelineState:
                        kind == KD_DRAW_CLEAR_RECT
                            ? frame->clear_pipeline
                            : frame->pipeline
                    ];
                }
                [encoder drawPrimitives:MTLPrimitiveTypeTriangle
                    vertexStart:(NSUInteger)output * 6u
                    vertexCount:6u];
                output++;
            }
        }

        [encoder endEncoding];

        id<MTLBlitCommandEncoder> blit = [command blitCommandEncoder];
        if (!blit) return KD_GRAPHICS_ERROR;
        MTLSize copy_size = MTLSizeMake(
            drawable.texture.width,
            drawable.texture.height,
            1
        );
        [blit copyFromTexture:frame->backing_texture
            sourceSlice:0
            sourceLevel:0
            sourceOrigin:MTLOriginMake(0, 0, 0)
            sourceSize:copy_size
            toTexture:drawable.texture
            destinationSlice:0
            destinationLevel:0
            destinationOrigin:MTLOriginMake(0, 0, 0)];
        [blit endEncoding];

        frame->backing_valid = YES;
        [command presentDrawable:drawable];
        [command commit];
    }

    return KD_OK;
}

void kd_metal_frame_destroy(kd_metal_frame *frame) {
    if (![NSThread isMainThread]) {
        dispatch_sync(dispatch_get_main_queue(), ^{
            kd_metal_frame_destroy(frame);
        });
        return;
    }
    if (!frame) return;
    @autoreleasepool {
        frame->pipeline = nil;
        frame->clear_pipeline = nil;
        frame->texture_pipeline = nil;
        frame->sampler = nil;
        kd_metal_destroy_textures(frame);
        frame->backing_texture = nil;
        frame->backing_width = 0;
        frame->backing_height = 0;
        frame->backing_valid = NO;
        frame->queue = nil;
        frame->device = nil;
        if (frame->view && frame->view.layer == frame->layer) {
            frame->view.layer = nil;
        }
        frame->layer = nil;
        frame->view = nil;
    }
    free(frame);
}

#else

struct kd_metal_frame { int unused; };

kd_status kd_metal_frame_create(kd_window *window, kd_metal_frame **out) {
    if (!window || !out) return KD_INVALID_ARGUMENT;
    *out = NULL;
    return KD_UNSUPPORTED_PLATFORM;
}

kd_status kd_metal_frame_present_commands(
    kd_metal_frame *frame,
    const kd_draw_command *commands,
    uint32_t count
) {
    if (!frame || !commands || !count) return KD_INVALID_ARGUMENT;
    return KD_UNSUPPORTED_PLATFORM;
}

void kd_metal_frame_destroy(kd_metal_frame *frame) {
    (void)frame;
}

#endif
