#include "kotlin_display.h"
#include "window_internal.h"

#if defined(__APPLE__)

#import <AppKit/AppKit.h>
#import <QuartzCore/CAMetalLayer.h>
#import <dispatch/dispatch.h>
#if defined(KD_HAS_JNI)
#import <jawt_md.h>
#endif
#include <stdlib.h>
#include <limits.h>
#include <math.h>

@interface KDContentView : NSView
@property(nonatomic, assign) kd_window *owner;
@property(nonatomic, strong) NSTrackingArea *kdTrackingArea;
@end

static int32_t kd_macos_modifiers(NSEvent *event) {
    NSEventModifierFlags flags = event.modifierFlags;
    int32_t modifiers = 0;
    if (flags & NSEventModifierFlagShift) modifiers |= 1;
    if (flags & NSEventModifierFlagControl) modifiers |= 2;
    if (flags & NSEventModifierFlagOption) modifiers |= 4;
    if (flags & NSEventModifierFlagCommand) modifiers |= 8;
    return modifiers;
}

@implementation KDContentView
- (BOOL)isFlipped { return YES; }
- (BOOL)acceptsFirstResponder { return YES; }

- (void)updateTrackingAreas {
    [super updateTrackingAreas];
    if (self.kdTrackingArea) {
        [self removeTrackingArea:self.kdTrackingArea];
    }
    NSTrackingAreaOptions options =
        NSTrackingMouseEnteredAndExited |
        NSTrackingMouseMoved |
        NSTrackingActiveInKeyWindow |
        NSTrackingInVisibleRect;
    self.kdTrackingArea = [[NSTrackingArea alloc]
        initWithRect:NSZeroRect
        options:options
        owner:self
        userInfo:nil
    ];
    [self addTrackingArea:self.kdTrackingArea];
}

- (void)mouseEntered:(NSEvent *)event {
    if (!self.owner) return;
    NSPoint point = [self convertPoint:event.locationInWindow fromView:nil];
    kd_window_enqueue_pointer(
        self.owner,
        KD_POINTER_ENTER,
        (int32_t)point.x,
        (int32_t)point.y
    );
}

- (void)mouseExited:(NSEvent *)event {
    if (!self.owner) return;
    NSPoint point = [self convertPoint:event.locationInWindow fromView:nil];
    kd_window_enqueue_pointer(
        self.owner,
        KD_POINTER_EXIT,
        (int32_t)point.x,
        (int32_t)point.y
    );
}

- (void)keyDown:(NSEvent *)event {
    if (!self.owner) return;
    NSString *characters = event.charactersIgnoringModifiers ?: @"";
    int32_t key = characters.length > 0
        ? (int32_t)[[characters uppercaseString] characterAtIndex:0]
        : 0;
    kd_window_enqueue_pointer(
        self.owner,
        KD_KEY_DOWN,
        key,
        kd_macos_modifiers(event)
    );

    NSString *typed = event.characters ?: @"";
    for (NSUInteger i = 0; i < typed.length; ++i) {
        unichar value = [typed characterAtIndex:i];
        kd_window_enqueue_pointer(
            self.owner,
            KD_TEXT_INPUT,
            (int32_t)value,
            kd_macos_modifiers(event)
        );
    }
}

- (void)keyUp:(NSEvent *)event {
    if (!self.owner) return;
    NSString *characters = event.charactersIgnoringModifiers ?: @"";
    int32_t key = characters.length > 0
        ? (int32_t)[[characters uppercaseString] characterAtIndex:0]
        : 0;
    kd_window_enqueue_pointer(
        self.owner,
        KD_KEY_UP,
        key,
        kd_macos_modifiers(event)
    );
}

- (void)mouseMoved:(NSEvent *)event {
    if (!self.owner) return;
    NSPoint point = [self convertPoint:event.locationInWindow fromView:nil];
    kd_window_enqueue_pointer(
        self.owner,
        KD_POINTER_MOVE,
        (int32_t)point.x,
        (int32_t)point.y
    );
}

- (void)mouseDragged:(NSEvent *)event {
    [self mouseMoved:event];
}

- (void)scrollWheel:(NSEvent *)event {
    if (!self.owner) return;
    CGFloat vertical = event.scrollingDeltaY;
    CGFloat horizontal = event.scrollingDeltaX;
    CGFloat scale = event.hasPreciseScrollingDeltas ? 12.0 : 120.0;
    int32_t yUnits = (int32_t)llround(-vertical * scale);
    int32_t xUnits = (int32_t)llround(-horizontal * scale);
    if (yUnits != 0) {
        kd_window_enqueue_pointer(self.owner, KD_POINTER_SCROLL, 0, yUnits);
    }
    if (xUnits != 0) {
        kd_window_enqueue_pointer(
            self.owner,
            KD_POINTER_SCROLL_HORIZONTAL,
            xUnits,
            0
        );
    }
}

- (void)rightMouseDown:(NSEvent *)event {
    if (!self.owner) return;
    NSPoint point = [self convertPoint:event.locationInWindow fromView:nil];
    kd_window_enqueue_pointer(
        self.owner,
        KD_POINTER_RIGHT_DOWN,
        (int32_t)point.x,
        (int32_t)point.y
    );
}

- (void)rightMouseUp:(NSEvent *)event {
    if (!self.owner) return;
    NSPoint point = [self convertPoint:event.locationInWindow fromView:nil];
    kd_window_enqueue_pointer(
        self.owner,
        KD_POINTER_RIGHT_UP,
        (int32_t)point.x,
        (int32_t)point.y
    );
}

- (void)otherMouseDown:(NSEvent *)event {
    if (!self.owner) return;
    NSPoint point = [self convertPoint:event.locationInWindow fromView:nil];
    kd_window_enqueue_pointer(
        self.owner,
        KD_POINTER_MIDDLE_DOWN,
        (int32_t)point.x,
        (int32_t)point.y
    );
}

- (void)otherMouseUp:(NSEvent *)event {
    if (!self.owner) return;
    NSPoint point = [self convertPoint:event.locationInWindow fromView:nil];
    kd_window_enqueue_pointer(
        self.owner,
        KD_POINTER_MIDDLE_UP,
        (int32_t)point.x,
        (int32_t)point.y
    );
}

- (void)mouseDown:(NSEvent *)event {
    if (!self.owner) return;
    NSPoint point = [self convertPoint:event.locationInWindow fromView:nil];
    kd_window_enqueue_pointer(
        self.owner,
        KD_POINTER_DOWN,
        (int32_t)point.x,
        (int32_t)point.y
    );
}

- (void)mouseUp:(NSEvent *)event {
    if (!self.owner) return;
    NSPoint point = [self convertPoint:event.locationInWindow fromView:nil];
    kd_window_enqueue_pointer(
        self.owner,
        KD_POINTER_UP,
        (int32_t)point.x,
        (int32_t)point.y
    );
}
@end

@interface KDWindowDelegate : NSObject <NSWindowDelegate>
@property(nonatomic, assign) kd_window *owner;
@end

@implementation KDWindowDelegate
- (void)windowDidBecomeKey:(NSNotification *)notification {
    (void)notification;
    if (self.owner) {
        kd_window_enqueue_pointer(self.owner, KD_WINDOW_FOCUS_GAINED, 0, 0);
    }
}

- (void)windowDidResignKey:(NSNotification *)notification {
    (void)notification;
    if (self.owner) {
        kd_window_enqueue_pointer(self.owner, KD_WINDOW_FOCUS_LOST, 0, 0);
    }
}

- (void)windowWillClose:(NSNotification *)notification {
    (void)notification;
    if (self.owner) self.owner->closed = 1;
}
@end

kd_status kd_window_create(
    uint32_t width,
    uint32_t height,
    const char *title,
    kd_window **out
) {
    if (![NSThread isMainThread]) {
        __block kd_status result = KD_WINDOW_UNAVAILABLE;
        dispatch_sync(dispatch_get_main_queue(), ^{
            result = kd_window_create(width, height, title, out);
        });
        return result;
    }
    if (!out || !title || width == 0 || height == 0 ||
        width > INT_MAX || height > INT_MAX) return KD_INVALID_ARGUMENT;

    *out = NULL;
    kd_window *state = (kd_window *)calloc(1, sizeof(*state));
    if (!state) return KD_OUT_OF_MEMORY;

    @autoreleasepool {
        NSApplication *app = [NSApplication sharedApplication];
        if (!app) {
            free(state);
            return KD_WINDOW_UNAVAILABLE;
        }

        [app setActivationPolicy:NSApplicationActivationPolicyRegular];

        NSRect rect = NSMakeRect(0, 0, (CGFloat)width, (CGFloat)height);
        NSWindowStyleMask style =
            NSWindowStyleMaskTitled |
            NSWindowStyleMaskClosable |
            NSWindowStyleMaskMiniaturizable |
            NSWindowStyleMaskResizable;

        NSWindow *window = [[NSWindow alloc]
            initWithContentRect:rect
            styleMask:style
            backing:NSBackingStoreBuffered
            defer:NO
        ];
        if (!window) {
            free(state);
            return KD_WINDOW_UNAVAILABLE;
        }

        KDContentView *view = [[KDContentView alloc] initWithFrame:rect];
        KDWindowDelegate *delegate = [[KDWindowDelegate alloc] init];
        view.owner = state;
        delegate.owner = state;

        window.title = [NSString stringWithUTF8String:title];
        window.delegate = delegate;
        window.contentView = view;
        window.acceptsMouseMovedEvents = YES;
        [window center];
        [window makeKeyAndOrderFront:nil];
        [window makeFirstResponder:view];
        [app activateIgnoringOtherApps:YES];

        state->application = (__bridge void *)app;
        state->window = (__bridge_retained void *)window;
        state->view = (__bridge_retained void *)view;
        state->delegate = (__bridge_retained void *)delegate;
    }

    *out = state;
    return KD_OK;
}

kd_status kd_window_create_child(
    uintptr_t parent,
    uint32_t width,
    uint32_t height,
    kd_window **out
) {
    if (![NSThread isMainThread]) {
        __block kd_status result = KD_WINDOW_UNAVAILABLE;
        dispatch_sync(dispatch_get_main_queue(), ^{
            result = kd_window_create_child(
                parent,
                width,
                height,
                out
            );
        });
        return result;
    }

    if (!out || !parent || width == 0 || height == 0) {
        return KD_INVALID_ARGUMENT;
    }
    *out = NULL;

#if !defined(KD_HAS_JNI)
    return KD_UNSUPPORTED_PLATFORM;
#else
    kd_window *state =
        (kd_window *)calloc(1, sizeof(*state));
    if (!state) return KD_OUT_OF_MEMORY;

    @autoreleasepool {
        id<JAWT_SurfaceLayers> host =
            (__bridge id<JAWT_SurfaceLayers>)(void *)parent;
        if (!host) {
            free(state);
            return KD_WINDOW_UNAVAILABLE;
        }

        CAMetalLayer *layer = [CAMetalLayer layer];
        layer.frame = CGRectMake(
            0,
            0,
            (CGFloat)width,
            (CGFloat)height
        );
        layer.bounds = layer.frame;
        layer.opaque = NO;
        layer.contentsScale =
            NSScreen.mainScreen.backingScaleFactor > 0.0
                ? NSScreen.mainScreen.backingScaleFactor
                : 1.0;

        // JAWT keeps the layer attached to the AWT host.
        host.layer = layer;

        state->application = (__bridge void *)NSApplication.sharedApplication;
        state->view = (__bridge_retained void *)layer;
        state->host = (__bridge_retained void *)host;
        state->attached_layer = 1;
    }

    *out = state;
    return KD_OK;
#endif
}

kd_status kd_window_resize(
    kd_window *window,
    uint32_t width,
    uint32_t height
) {
    if (![NSThread isMainThread]) {
        __block kd_status result = KD_WINDOW_UNAVAILABLE;
        dispatch_sync(dispatch_get_main_queue(), ^{
            result = kd_window_resize(window, width, height);
        });
        return result;
    }
    if (!window || width == 0 || height == 0) return KD_INVALID_ARGUMENT;
    if (window->closed || !window->view) return KD_WINDOW_UNAVAILABLE;

    @autoreleasepool {
        if (window->attached_layer) {
            CAMetalLayer *layer =
                (__bridge CAMetalLayer *)window->view;
            CGRect bounds = layer.bounds;
            bounds.size.width = (CGFloat)width;
            bounds.size.height = (CGFloat)height;
            layer.bounds = bounds;
            CGFloat scale = layer.contentsScale > 0.0
                ? layer.contentsScale
                : 1.0;
            layer.drawableSize = CGSizeMake(
                width * scale,
                height * scale
            );
        } else {
            NSWindow *nativeWindow =
                (__bridge NSWindow *)window->window;
            NSRect content =
                NSMakeRect(0, 0, (CGFloat)width, (CGFloat)height);
            NSRect frame =
                [nativeWindow frameRectForContentRect:content];
            NSRect current = nativeWindow.frame;
            frame.origin = current.origin;
            [nativeWindow setFrame:frame display:YES];
        }
    }
    return KD_OK;
}

kd_status kd_window_poll(kd_window *window, int *should_close) {
    if (![NSThread isMainThread]) {
        __block kd_status result = KD_WINDOW_UNAVAILABLE;
        dispatch_sync(dispatch_get_main_queue(), ^{
            result = kd_window_poll(window, should_close);
        });
        return result;
    }
    if (!window || !should_close) return KD_INVALID_ARGUMENT;

    if (window->attached_layer) {
        *should_close = window->closed;
        return KD_OK;
    }

    @autoreleasepool {
        NSApplication *app = (__bridge NSApplication *)window->application;
        if (!app) return KD_WINDOW_UNAVAILABLE;

        for (;;) {
            NSEvent *event = [app nextEventMatchingMask:NSEventMaskAny
                untilDate:[NSDate distantPast]
                inMode:NSDefaultRunLoopMode
                dequeue:YES];
            if (!event) break;
            [app sendEvent:event];
        }
        [app updateWindows];
    }

    *should_close = window->closed;
    return KD_OK;
}

kd_status kd_window_get_native_handles(
    kd_window *window,
    kd_native_window_handles *out
) {
    if (![NSThread isMainThread]) {
        __block kd_status result = KD_WINDOW_UNAVAILABLE;
        dispatch_sync(dispatch_get_main_queue(), ^{
            result = kd_window_get_native_handles(window, out);
        });
        return result;
    }
    if (!window || !out) return KD_INVALID_ARGUMENT;
    if (window->closed || !window->view) return KD_WINDOW_UNAVAILABLE;

    out->platform = KD_WINDOW_PLATFORM_MACOS;
    out->display = window->view;
    out->window = (uintptr_t)window->window;
    return KD_OK;
}

kd_status kd_window_get_size(
    kd_window *window,
    uint32_t *width,
    uint32_t *height
) {
    if (![NSThread isMainThread]) {
        __block kd_status result = KD_WINDOW_UNAVAILABLE;
        dispatch_sync(dispatch_get_main_queue(), ^{
            result = kd_window_get_size(window, width, height);
        });
        return result;
    }
    if (!window || !width || !height) return KD_INVALID_ARGUMENT;
    if (window->closed || !window->view) return KD_WINDOW_UNAVAILABLE;

    @autoreleasepool {
        if (window->attached_layer) {
            CAMetalLayer *layer =
                (__bridge CAMetalLayer *)window->view;
            CGRect logical = layer.bounds;
            if (logical.size.width < 0 ||
                logical.size.height < 0 ||
                logical.size.width > UINT32_MAX ||
                logical.size.height > UINT32_MAX) {
                return KD_WINDOW_UNAVAILABLE;
            }
            *width = (uint32_t)logical.size.width;
            *height = (uint32_t)logical.size.height;
        } else {
            KDContentView *view =
                (__bridge KDContentView *)window->view;
            NSRect logical = view.bounds;
            if (logical.size.width < 0 ||
                logical.size.height < 0 ||
                logical.size.width > UINT32_MAX ||
                logical.size.height > UINT32_MAX) {
                return KD_WINDOW_UNAVAILABLE;
            }
            *width = (uint32_t)logical.size.width;
            *height = (uint32_t)logical.size.height;
        }
    }
    return KD_OK;
}

void kd_window_destroy(kd_window *window) {
    if (![NSThread isMainThread]) {
        dispatch_sync(dispatch_get_main_queue(), ^{
            kd_window_destroy(window);
        });
        return;
    }
    if (!window) return;

    @autoreleasepool {
        if (window->attached_layer) {
#if defined(KD_HAS_JNI)
            id<JAWT_SurfaceLayers> host =
                (__bridge id<JAWT_SurfaceLayers>)window->host;
            CAMetalLayer *layer =
                (__bridge CAMetalLayer *)window->view;
            if (host && host.layer == layer) {
                host.layer = nil;
            }
#endif
            if (window->host) CFBridgingRelease(window->host);
            if (window->view) CFBridgingRelease(window->view);
        } else {
            KDContentView *view =
                (__bridge KDContentView *)window->view;
            KDWindowDelegate *delegate =
                (__bridge KDWindowDelegate *)window->delegate;
            NSWindow *nativeWindow =
                (__bridge NSWindow *)window->window;

            view.owner = NULL;
            delegate.owner = NULL;
            nativeWindow.delegate = nil;
            [nativeWindow orderOut:nil];
            [nativeWindow close];

            if (window->delegate) CFBridgingRelease(window->delegate);
            if (window->view) CFBridgingRelease(window->view);
            if (window->window) CFBridgingRelease(window->window);
        }
    }

    free(window);
}

#endif
