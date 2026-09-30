package dev.yurie.display.agent;

import dev.yurie.display.composebridge.nativebridge.NativeGpuBridge;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowEvent;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import javax.swing.SwingUtilities;

final class LiveGpuTakeover {
    private static final int MAX_COMMANDS = 65536;

    private static final class State {
        float a = 1f;
        float b = 0f;
        float c = 0f;
        float d = 1f;
        float tx = 0f;
        float ty = 0f;
        float opacity = 1f;
        float clipX;
        float clipY;
        float clipW;
        float clipH;
        boolean hasExclude;
        float excludeX;
        float excludeY;
        float excludeW;
        float excludeH;
        boolean hasRoundClip;
        float roundClipX;
        float roundClipY;
        float roundClipW;
        float roundClipH;
        float roundClipRx;
        float roundClipRy;
        List<List<P>> pathClip;

        State(int width, int height) {
            clipX = 0f;
            clipY = 0f;
            clipW = width;
            clipH = height;
        }

        State(State other) {
            a = other.a;
            b = other.b;
            c = other.c;
            d = other.d;
            tx = other.tx;
            ty = other.ty;
            opacity = other.opacity;
            clipX = other.clipX;
            clipY = other.clipY;
            clipW = other.clipW;
            clipH = other.clipH;
            hasExclude = other.hasExclude;
            excludeX = other.excludeX;
            excludeY = other.excludeY;
            excludeW = other.excludeW;
            excludeH = other.excludeH;
            hasRoundClip = other.hasRoundClip;
            roundClipX = other.roundClipX;
            roundClipY = other.roundClipY;
            roundClipW = other.roundClipW;
            roundClipH = other.roundClipH;
            roundClipRx = other.roundClipRx;
            roundClipRy = other.roundClipRy;
            pathClip = other.pathClip;
        }
    }

    private record Command(
        int kind,
        float x,
        float y,
        float w,
        float h,
        int argb,
        float u0,
        float v0,
        float u1,
        float v1
    ) {
        Command(int kind, float x, float y, float w, float h, int argb) {
            this(kind, x, y, w, h, argb, 0f, 0f, 0f, 0f);
        }

        static Command image(
            int encodedKind,
            float x,
            float y,
            float w,
            float h,
            float u0,
            float v0,
            float u1,
            float v1
        ) {
            return new Command(
                encodedKind,
                x, y, w, h,
                0,
                u0, v0, u1, v1
            );
        }

        static Command glyph(
            int encodedKind,
            float x,
            float y,
            float w,
            float h,
            int argb,
            float u0,
            float v0,
            float u1,
            float v1
        ) {
            return new Command(
                encodedKind,
                x, y, w, h,
                argb,
                u0, v0, u1, v1
            );
        }
    }
    private record P(float x, float y) {}

    private static final int GLYPH_ATLAS_SIZE = 1024;
    private static final int GLYPH_ATLAS_LIMIT = 8;
    private static final int GLYPH_PADDING = 2;

    private record GlyphKey(
        String family,
        int style,
        int sizeBits,
        int glyphCode
    ) {}

    private record GlyphEntry(
        int textureId,
        float u0,
        float v0,
        float u1,
        float v1,
        float offsetX,
        float offsetY,
        int width,
        int height
    ) {}

    static final class GlyphAtlas {
        final int textureId;
        final BufferedImage image = new BufferedImage(
            GLYPH_ATLAS_SIZE,
            GLYPH_ATLAS_SIZE,
            BufferedImage.TYPE_INT_ARGB
        );
        int cursorX = 1;
        int cursorY = 1;
        int rowHeight;
        boolean dirty;

        GlyphAtlas(int textureId) {
            this.textureId = textureId;
        }

        int[] place(BufferedImage glyph) {
            int width = glyph.getWidth();
            int height = glyph.getHeight();
            if (width + 2 > GLYPH_ATLAS_SIZE ||
                height + 2 > GLYPH_ATLAS_SIZE) {
                return null;
            }

            if (cursorX + width + 1 > GLYPH_ATLAS_SIZE) {
                cursorX = 1;
                cursorY += rowHeight + 1;
                rowHeight = 0;
            }
            if (cursorY + height + 1 > GLYPH_ATLAS_SIZE) {
                return null;
            }

            int x = cursorX;
            int y = cursorY;
            int[] pixels = glyph.getRGB(
                0, 0, width, height,
                null, 0, width
            );
            image.setRGB(
                x, y, width, height,
                pixels, 0, width
            );
            cursorX += width + 1;
            rowHeight = Math.max(rowHeight, height);
            dirty = true;
            return new int[]{x, y};
        }
    }

    private static final class WindowContext {
        final Window composeWindow;
        Component focusOwner;
        Component renderHost;
        long handle;
        int width;
        int height;
        int pointerX;
        int pointerY;
        int buttonMask;
        float contentScale = 1f;
        boolean parked;
        boolean embedded;
        boolean retainedFrame;
        boolean rendererCreateQueued;
        long presentedFrames;
        long retainedUpdates;
        int nextTextureId = 1;
        int pictureTextureId;
        final IdentityHashMap<Object, Integer> textureIds =
            new IdentityHashMap<>();
        final ArrayDeque<Object> textureOrder = new ArrayDeque<>();
        final Map<GlyphKey, GlyphEntry> glyphs = new HashMap<>();
        final List<GlyphAtlas> glyphAtlases = new ArrayList<>();

        WindowContext(Window composeWindow) {
            this.composeWindow = composeWindow;
            this.focusOwner = composeWindow == null
                ? null
                : composeWindow.getFocusOwner();
        }
    }

    private static boolean configured;
    private static boolean strict;
    private static boolean active;
    private static int frameWidth;
    private static int frameHeight;
    private static State state;
    private static boolean presentQueued;
    private static boolean inFrame;
    private static boolean firstClearInFrame;
    private static WindowContext currentContext;
    private static final Map<Window, WindowContext> CONTEXTS =
        new WeakHashMap<>();
    private static final ReentrantLock FRAME_LOCK = new ReentrantLock(true);
    private static final AtomicBoolean PRESENT_PROVED = new AtomicBoolean();
    private static final AtomicBoolean RETAIN_PROVED = new AtomicBoolean();
    private static final AtomicBoolean TEXTURE_PROVED = new AtomicBoolean();
    private static final AtomicBoolean GLYPH_ATLAS_PROVED = new AtomicBoolean();
    private static final Deque<State> stack = new ArrayDeque<>();
    private static final List<Command> commands = new ArrayList<>();

    private LiveGpuTakeover() {}

    static synchronized void configure(boolean enabled, boolean strictMode) {
        configured = enabled;
        strict = strictMode;
        active = enabled;
    }

    static synchronized boolean isConfigured() {
        return configured && active;
    }

    static synchronized boolean enabled() {
        return isConfigured() &&
            ComposeCaptureHooks.canvasCallAllowed();
    }

    static void frameStart(int width, int height) {
        frameStart(null, width, height);
    }

    static void frameStart(
        Object layer,
        int width,
        int height
    ) {
        if (!enabled() || width <= 0 || height <= 0) return;
        FRAME_LOCK.lock();
        boolean initialized = false;
        try {
            synchronized (LiveGpuTakeover.class) {
                if (!enabled()) return;

                Window window = resolveComposeWindow(layer);
        if (window != null) {
            currentContext = CONTEXTS.computeIfAbsent(
                window,
                WindowContext::new
            );
            currentContext.width = width;
            currentContext.height = height;
            currentContext.contentScale = contentScale(layer);
            Component renderHost = resolveRenderHost(layer);
            if (renderHost != null) {
                currentContext.renderHost = renderHost;
            }
            Component focus = window.getFocusOwner();
            if (focus != null) currentContext.focusOwner = focus;
            ensureRenderer(currentContext);
        }

                frameWidth = width;
                frameHeight = height;
                state = new State(width, height);
                stack.clear();
                commands.clear();
                firstClearInFrame = true;
                inFrame = true;
                initialized = true;
            }
        } finally {
            if (!initialized && FRAME_LOCK.isHeldByCurrentThread()) {
                FRAME_LOCK.unlock();
            }
        }
    }

    static synchronized void clear(int argb) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;

        /*
         * Skiko clears its recording canvas at the beginning of every
         * incremental frame. After we already own a valid native frame that
         * clear is recorder housekeeping, not an instruction to erase the
         * application. Retain the previous native image and apply only the
         * dirty draws that follow.
         */
        if (firstClearInFrame &&
            currentContext != null &&
            currentContext.retainedFrame) {
            commands.add(new Command(4, 0f, 0f, 0f, 0f, 0x00000000));
        } else {
            /*
             * A real clear later in the frame invalidates every draw that
             * preceded it. The native batch protocol requires CLEAR at index
             * zero, so drop the superseded dirty commands and restart this
             * frame from the clear.
             */
            commands.clear();
            stack.clear();
            commands.add(new Command(1, 0f, 0f, 0f, 0f, argb));
        }
        firstClearInFrame = false;
        requestPresent();
    }

    static synchronized void save() {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        stack.push(new State(state));
    }

    static synchronized void saveLayer(float opacity) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        stack.push(new State(state));
        state.opacity *= Math.max(0f, Math.min(1f, opacity));
    }

    static synchronized void restore() {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null || stack.isEmpty()) return;
        state = stack.pop();
    }

    static synchronized void restoreToCount(int saveCount) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        int targetDepth = Math.max(0, saveCount - 1);
        while (stack.size() > targetDepth) {
            state = stack.pop();
        }
    }

    static synchronized void translate(float dx, float dy) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        state.tx += state.a * dx + state.c * dy;
        state.ty += state.b * dx + state.d * dy;
    }

    static synchronized void scale(float sx, float sy) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        state.a *= sx;
        state.b *= sx;
        state.c *= sy;
        state.d *= sy;
    }

    static synchronized void rotate(float degrees) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        double radians = Math.toRadians(degrees);
        float cs = (float)Math.cos(radians);
        float sn = (float)Math.sin(radians);
        float a = state.a;
        float b = state.b;
        float c = state.c;
        float d = state.d;
        state.a = a * cs + c * sn;
        state.b = b * cs + d * sn;
        state.c = -a * sn + c * cs;
        state.d = -b * sn + d * cs;
    }

    static synchronized void rotate(float degrees, float px, float py) {
        translate(px, py);
        rotate(degrees);
        translate(-px, -py);
    }

    static synchronized void skew(float sx, float sy) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        float kx = sx;
        float ky = sy;
        float a = state.a;
        float b = state.b;
        float c = state.c;
        float d = state.d;
        state.a = a + c * ky;
        state.b = b + d * ky;
        state.c = a * kx + c;
        state.d = b * kx + d;
    }

    static synchronized boolean concat(Object matrix) {
        if (!enabled() || matrix == null) return false;
        ensureFrameState();
        if (state == null) return false;
        try {
            Object raw = invoke(matrix, "getMat");
            if (!(raw instanceof float[] values)) return false;

            float ma;
            float mb;
            float mc;
            float md;
            float mtx;
            float mty;
            if (values.length >= 16) {
                ma = values[0];
                mb = values[1];
                mc = values[4];
                md = values[5];
                mtx = values[12];
                mty = values[13];
            } else if (values.length >= 9) {
                ma = values[0];
                mc = values[1];
                mtx = values[2];
                mb = values[3];
                md = values[4];
                mty = values[5];
            } else {
                return false;
            }

            float a = state.a;
            float b = state.b;
            float cc = state.c;
            float d = state.d;
            float tx = state.tx;
            float ty = state.ty;
            state.a = a * ma + cc * mb;
            state.b = b * ma + d * mb;
            state.c = a * mc + cc * md;
            state.d = b * mc + d * md;
            state.tx = a * mtx + cc * mty + tx;
            state.ty = b * mtx + d * mty + ty;
            return true;
        } catch (Throwable failure) {
            System.err.println("[Kanvas] matrix capture failed: " + failure.getMessage());
            if (strict) Runtime.getRuntime().halt(94);
            return false;
        }
    }

    static void frameEnd() {
        try {
            synchronized (LiveGpuTakeover.class) {
                if (!enabled()) return;
                inFrame = false;
                present();
            }
        } finally {
            if (FRAME_LOCK.isHeldByCurrentThread()) {
                FRAME_LOCK.unlock();
            }
        }
    }

    static synchronized boolean clipPath(Object path) {
        if (!enabled() || path == null) return false;
        ensureFrameState();
        if (state == null) return false;
        try {
            List<List<P>> localContours = readPath(path);
            if (localContours.isEmpty()) return false;

            List<List<P>> deviceContours = new ArrayList<>();
            float left = Float.POSITIVE_INFINITY;
            float top = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY;
            float bottom = Float.NEGATIVE_INFINITY;

            for (List<P> contour : localContours) {
                if (contour.size() < 2) continue;
                List<P> transformed = new ArrayList<>(contour.size());
                for (P point : contour) {
                    P device = transform(point.x, point.y);
                    transformed.add(device);
                    left = Math.min(left, device.x);
                    top = Math.min(top, device.y);
                    right = Math.max(right, device.x);
                    bottom = Math.max(bottom, device.y);
                }
                deviceContours.add(List.copyOf(transformed));
            }

            if (deviceContours.isEmpty() ||
                !Float.isFinite(left) ||
                !Float.isFinite(top) ||
                !Float.isFinite(right) ||
                !Float.isFinite(bottom)) {
                return false;
            }

            intersectClip(left, top, right - left, bottom - top);
            state.pathClip = List.copyOf(deviceContours);
            return true;
        } catch (Throwable failure) {
            System.err.println(
                "[Kanvas] clip path capture failed: " +
                failure.getMessage()
            );
            if (strict) Runtime.getRuntime().halt(95);
            return false;
        }
    }

    static synchronized boolean setMatrix(Object matrix) {
        if (!enabled()) return false;
        resetMatrix();
        return concat(matrix);
    }

    static synchronized void resetMatrix() {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        state.a = 1f;
        state.b = 0f;
        state.c = 0f;
        state.d = 1f;
        state.tx = 0f;
        state.ty = 0f;
    }

    static synchronized void clipRoundRect(
        float left,
        float top,
        float right,
        float bottom,
        float[] radii,
        boolean difference
    ) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;

        if (difference) {
            clipRect(left, top, right, bottom, true);
            return;
        }

        clipRect(left, top, right, bottom, false);
        P p0 = transform(left, top);
        P p1 = transform(right, bottom);
        float x = Math.min(p0.x, p1.x);
        float y = Math.min(p0.y, p1.y);
        float width = Math.abs(p1.x - p0.x);
        float height = Math.abs(p1.y - p0.y);

        float rx = 0f;
        float ry = 0f;
        if (radii != null && radii.length > 0) {
            rx = Math.max(0f, radii[0]);
            ry = Math.max(0f, radii.length > 1 ? radii[1] : radii[0]);
        }

        float scaleX = (float)Math.sqrt(state.a * state.a + state.b * state.b);
        float scaleY = (float)Math.sqrt(state.c * state.c + state.d * state.d);
        rx = Math.min(width / 2f, rx * Math.max(0.0001f, scaleX));
        ry = Math.min(height / 2f, ry * Math.max(0.0001f, scaleY));

        if (rx <= 0f || ry <= 0f) {
            state.hasRoundClip = false;
            return;
        }

        state.hasRoundClip = true;
        state.roundClipX = x;
        state.roundClipY = y;
        state.roundClipW = width;
        state.roundClipH = height;
        state.roundClipRx = rx;
        state.roundClipRy = ry;
    }

    static synchronized void clipRect(float left, float top, float right, float bottom) {
        clipRect(left, top, right, bottom, false);
    }

    static synchronized void clipRect(
        float left,
        float top,
        float right,
        float bottom,
        boolean difference
    ) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        P p0 = transform(left, top);
        P p1 = transform(right, top);
        P p2 = transform(right, bottom);
        P p3 = transform(left, bottom);
        float x = Math.min(Math.min(p0.x, p1.x), Math.min(p2.x, p3.x));
        float y = Math.min(Math.min(p0.y, p1.y), Math.min(p2.y, p3.y));
        float r = Math.max(Math.max(p0.x, p1.x), Math.max(p2.x, p3.x));
        float b = Math.max(Math.max(p0.y, p1.y), Math.max(p2.y, p3.y));

        if (difference) {
            state.hasExclude = true;
            state.excludeX = x;
            state.excludeY = y;
            state.excludeW = Math.max(0f, r - x);
            state.excludeH = Math.max(0f, b - y);
        } else {
            intersectClip(x, y, r - x, b - y);
        }
    }

    static synchronized boolean rectObject(
        Object rect,
        int argb,
        Object paint
    ) {
        if (rect == null) return false;
        try {
            rect(
                number(invoke(rect, "getLeft"), 0f),
                number(invoke(rect, "getTop"), 0f),
                number(invoke(rect, "getRight"), 0f),
                number(invoke(rect, "getBottom"), 0f),
                argb,
                paint
            );
            return true;
        } catch (Throwable failure) {
            return objectGeometryFailure("rect", failure, 101);
        }
    }

    static synchronized boolean ovalObject(
        Object rect,
        int argb,
        Object paint
    ) {
        if (rect == null) return false;
        try {
            oval(
                number(invoke(rect, "getLeft"), 0f),
                number(invoke(rect, "getTop"), 0f),
                number(invoke(rect, "getRight"), 0f),
                number(invoke(rect, "getBottom"), 0f),
                argb,
                paint
            );
            return true;
        } catch (Throwable failure) {
            return objectGeometryFailure("oval", failure, 102);
        }
    }

    static synchronized boolean roundRectObject(
        Object rrect,
        int argb,
        Object paint
    ) {
        if (rrect == null) return false;
        try {
            roundRect(
                number(invoke(rrect, "getLeft"), 0f),
                number(invoke(rrect, "getTop"), 0f),
                number(invoke(rrect, "getRight"), 0f),
                number(invoke(rrect, "getBottom"), 0f),
                (float[])invoke(rrect, "getRadii"),
                argb,
                paint
            );
            return true;
        } catch (Throwable failure) {
            return objectGeometryFailure(
                "rounded rect",
                failure,
                103
            );
        }
    }

    static synchronized boolean clipRectObject(
        Object rect,
        boolean difference
    ) {
        if (rect == null) return false;
        try {
            clipRect(
                number(invoke(rect, "getLeft"), 0f),
                number(invoke(rect, "getTop"), 0f),
                number(invoke(rect, "getRight"), 0f),
                number(invoke(rect, "getBottom"), 0f),
                difference
            );
            return true;
        } catch (Throwable failure) {
            return objectGeometryFailure(
                "rect clip",
                failure,
                104
            );
        }
    }

    static synchronized boolean clipRoundRectObject(
        Object rrect,
        boolean difference
    ) {
        if (rrect == null) return false;
        try {
            clipRoundRect(
                number(invoke(rrect, "getLeft"), 0f),
                number(invoke(rrect, "getTop"), 0f),
                number(invoke(rrect, "getRight"), 0f),
                number(invoke(rrect, "getBottom"), 0f),
                (float[])invoke(rrect, "getRadii"),
                difference
            );
            return true;
        } catch (Throwable failure) {
            return objectGeometryFailure(
                "rounded clip",
                failure,
                105
            );
        }
    }

    static synchronized boolean imageRectObject(
        Object image,
        Object source,
        Object destination,
        Object paint
    ) {
        if (image == null || source == null || destination == null) {
            return false;
        }
        try {
            return imageRect(
                image,
                number(invoke(source, "getLeft"), 0f),
                number(invoke(source, "getTop"), 0f),
                number(invoke(source, "getRight"), 0f),
                number(invoke(source, "getBottom"), 0f),
                number(invoke(destination, "getLeft"), 0f),
                number(invoke(destination, "getTop"), 0f),
                number(invoke(destination, "getRight"), 0f),
                number(invoke(destination, "getBottom"), 0f),
                paint
            );
        } catch (Throwable failure) {
            return objectGeometryFailure(
                "image rect",
                failure,
                106
            );
        }
    }

    private static boolean objectGeometryFailure(
        String operation,
        Throwable failure,
        int haltCode
    ) {
        System.err.println(
            "[Kanvas] " + operation +
            " object capture failed: " + failure.getMessage()
        );
        if (strict) Runtime.getRuntime().halt(haltCode);
        return false;
    }

    static synchronized void clearRect(
        float left,
        float top,
        float right,
        float bottom
    ) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null || right <= left || bottom <= top) return;

        if (Math.abs(state.b) > 0.0001f || Math.abs(state.c) > 0.0001f) {
            fillPolygonClear(
                List.of(
                    new P(left, top),
                    new P(right, top),
                    new P(right, bottom),
                    new P(left, bottom)
                )
            );
            return;
        }

        P p0 = transform(left, top);
        P p1 = transform(right, bottom);
        float x0 = Math.min(p0.x, p1.x);
        float y0 = Math.min(p0.y, p1.y);
        float x1 = Math.max(p0.x, p1.x);
        float y1 = Math.max(p0.y, p1.y);
        emitDeviceKind(x0, y0, x1 - x0, y1 - y0, 3, 0);
        requestPresent();
    }

    static synchronized void rect(float left, float top, float right, float bottom, int argb) {
        rect(left, top, right, bottom, argb, null);
    }

    static synchronized void rect(
        float left,
        float top,
        float right,
        float bottom,
        int argb,
        Object paint
    ) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        ImageFilterCaptureStore.DropShadow shadow =
            ImageFilterCaptureStore.fromPaint(paint);
        if (shadow != null) {
            emitDropShadow(
                left,
                top,
                right - left,
                bottom - top,
                shadow
            );
            if (!shadow.includeSource()) return;
        }

        MaskFilterCaptureStore.Blur blur =
            MaskFilterCaptureStore.fromPaint(paint);
        if (blur != null) {
            emitMaskBlur(
                left,
                top,
                right - left,
                bottom - top,
                blur,
                argb
            );
            if (!maskBlurIncludesSource(blur)) return;
        }

        ShaderCaptureStore.ShaderInfo shader = ShaderCaptureStore.fromPaint(paint);
        if (shader != null) {
            emitShaderRect(
                left,
                top,
                right - left,
                bottom - top,
                shader,
                paintAlpha(paint)
            );
        } else {
            emit(left, top, right - left, bottom - top, argb);
        }
    }

    static synchronized void roundRect(
        float left,
        float top,
        float right,
        float bottom,
        float[] radii,
        int argb
    ) {
        roundRect(left, top, right, bottom, radii, argb, null);
    }

    static synchronized void roundRect(
        float left,
        float top,
        float right,
        float bottom,
        float[] radii,
        int argb,
        Object paint
    ) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        float width = right - left;
        float height = bottom - top;
        ImageFilterCaptureStore.DropShadow shadow =
            ImageFilterCaptureStore.fromPaint(paint);
        if (shadow != null) {
            emitDropShadow(left, top, width, height, shadow);
            if (!shadow.includeSource()) return;
        }
        MaskFilterCaptureStore.Blur blur =
            MaskFilterCaptureStore.fromPaint(paint);
        if (blur != null) {
            emitMaskBlur(left, top, width, height, blur, argb);
            if (!maskBlurIncludesSource(blur)) return;
        }
        ShaderCaptureStore.ShaderInfo shader = ShaderCaptureStore.fromPaint(paint);
        if (width <= 0f || height <= 0f) return;

        float rx = 0f;
        float ry = 0f;
        if (radii != null && radii.length > 0) {
            rx = Math.max(0f, radii[0]);
            ry = Math.max(0f, radii.length > 1 ? radii[1] : radii[0]);
        }
        rx = Math.min(rx, width / 2f);
        ry = Math.min(ry, height / 2f);

        if (rx <= 0f || ry <= 0f) {
            if (shader != null) {
                emitShaderRect(
                    left,
                    top,
                    width,
                    height,
                    shader,
                    paintAlpha(paint)
                );
            }
            else emit(left, top, width, height, argb);
            return;
        }

        int rows = Math.max(1, Math.min(64, (int)Math.ceil(height)));
        for (int row = 0; row < rows; row++) {
            float y0 = top + row * height / rows;
            float y1 = top + (row + 1) * height / rows;
            float midY = (y0 + y1) * 0.5f;
            float topCenter = top + ry;
            float bottomCenter = top + height - ry;
            float inset = 0f;
            if (midY < topCenter) {
                float dy = (midY - topCenter) / ry;
                inset = rx - rx * (float)Math.sqrt(Math.max(0f, 1f - dy * dy));
            } else if (midY > bottomCenter) {
                float dy = (midY - bottomCenter) / ry;
                inset = rx - rx * (float)Math.sqrt(Math.max(0f, 1f - dy * dy));
            }
            float span = Math.max(0f, width - inset * 2f);
            if (shader != null) {
                emitShaderSpan(
                    left + inset,
                    y0,
                    span,
                    y1 - y0,
                    shader,
                    paintAlpha(paint)
                );
            }
            else emit(left + inset, y0, span, y1 - y0, argb);
        }
    }

    static synchronized boolean doubleRoundRect(
        Object outer,
        Object inner,
        Object paint
    ) {
        if (!enabled() || outer == null || inner == null) return false;
        ensureFrameState();
        if (state == null) return false;

        try {
            float outerLeft = number(invoke(outer, "getLeft"), 0f);
            float outerTop = number(invoke(outer, "getTop"), 0f);
            float outerRight = number(invoke(outer, "getRight"), 0f);
            float outerBottom = number(invoke(outer, "getBottom"), 0f);
            float innerLeft = number(invoke(inner, "getLeft"), 0f);
            float innerTop = number(invoke(inner, "getTop"), 0f);
            float innerRight = number(invoke(inner, "getRight"), 0f);
            float innerBottom = number(invoke(inner, "getBottom"), 0f);

            float[] outerRadii = (float[])invoke(outer, "getRadii");
            float[] innerRadii = (float[])invoke(inner, "getRadii");
            float outerRx = radiusX(outerRadii);
            float outerRy = radiusY(outerRadii);
            float innerRx = radiusX(innerRadii);
            float innerRy = radiusY(innerRadii);

            int argb = paintColor(paint);
            ShaderCaptureStore.ShaderInfo shader =
                ShaderCaptureStore.fromPaint(paint);
            float alpha = paintAlpha(paint);

            float height = outerBottom - outerTop;
            if (height <= 0f || outerRight <= outerLeft) return true;

            int rows = Math.max(
                1,
                Math.min(128, (int)Math.ceil(height))
            );

            for (int row = 0; row < rows; row++) {
                float y0 = outerTop + row * height / rows;
                float y1 = outerTop + (row + 1) * height / rows;
                float midY = (y0 + y1) * 0.5f;

                float outerInset = roundInset(
                    midY,
                    outerTop,
                    outerBottom,
                    outerRx,
                    outerRy
                );
                float outerX0 = outerLeft + outerInset;
                float outerX1 = outerRight - outerInset;

                boolean crossesInner =
                    midY >= innerTop && midY < innerBottom;
                if (!crossesInner) {
                    emitStyledSpan(
                        outerX0,
                        y0,
                        outerX1 - outerX0,
                        y1 - y0,
                        argb,
                        shader,
                        alpha
                    );
                    continue;
                }

                float innerInset = roundInset(
                    midY,
                    innerTop,
                    innerBottom,
                    innerRx,
                    innerRy
                );
                float innerX0 = innerLeft + innerInset;
                float innerX1 = innerRight - innerInset;

                emitStyledSpan(
                    outerX0,
                    y0,
                    Math.max(0f, innerX0 - outerX0),
                    y1 - y0,
                    argb,
                    shader,
                    alpha
                );
                emitStyledSpan(
                    Math.max(outerX0, innerX1),
                    y0,
                    Math.max(0f, outerX1 - Math.max(outerX0, innerX1)),
                    y1 - y0,
                    argb,
                    shader,
                    alpha
                );
            }
            return true;
        } catch (Throwable failure) {
            System.err.println(
                "[Kanvas] double rounded rect capture failed: " +
                failure.getMessage()
            );
            if (strict) Runtime.getRuntime().halt(97);
            return false;
        }
    }

    private static float radiusX(float[] radii) {
        return radii == null || radii.length == 0
            ? 0f
            : Math.max(0f, radii[0]);
    }

    private static float radiusY(float[] radii) {
        if (radii == null || radii.length == 0) return 0f;
        return Math.max(0f, radii.length > 1 ? radii[1] : radii[0]);
    }

    private static float roundInset(
        float y,
        float top,
        float bottom,
        float rx,
        float ry
    ) {
        if (rx <= 0f || ry <= 0f) return 0f;
        float centerTop = top + ry;
        float centerBottom = bottom - ry;
        if (y >= centerTop && y <= centerBottom) return 0f;

        float centerY = y < centerTop ? centerTop : centerBottom;
        float dy = (y - centerY) / ry;
        return rx -
            rx * (float)Math.sqrt(Math.max(0f, 1f - dy * dy));
    }

    private static void emitStyledSpan(
        float x,
        float y,
        float width,
        float height,
        int argb,
        ShaderCaptureStore.ShaderInfo shader,
        float opacity
    ) {
        if (width <= 0f || height <= 0f) return;
        if (shader == null) {
            emit(x, y, width, height, argb);
            return;
        }
        emitShaderSpan(x, y, width, height, shader, opacity);
    }

    static synchronized void oval(
        float left,
        float top,
        float right,
        float bottom,
        int argb
    ) {
        oval(left, top, right, bottom, argb, null);
    }

    static synchronized void oval(
        float left,
        float top,
        float right,
        float bottom,
        int argb,
        Object paint
    ) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        float width = right - left;
        float height = bottom - top;
        ShaderCaptureStore.ShaderInfo shader = ShaderCaptureStore.fromPaint(paint);
        if (width <= 0f || height <= 0f) return;
        MaskFilterCaptureStore.Blur blur =
            MaskFilterCaptureStore.fromPaint(paint);
        if (blur != null) {
            emitMaskBlur(left, top, width, height, blur, argb);
            if (!maskBlurIncludesSource(blur)) return;
        }

        float rx = width / 2f;
        float ry = height / 2f;
        float cx = left + rx;
        float cy = top + ry;
        int rows = Math.max(1, Math.min(96, (int)Math.ceil(height)));
        for (int row = 0; row < rows; row++) {
            float y0 = top + row * height / rows;
            float y1 = top + (row + 1) * height / rows;
            float normalized = (((y0 + y1) * 0.5f) - cy) / ry;
            float half = rx * (float)Math.sqrt(Math.max(0f, 1f - normalized * normalized));
            if (shader != null) {
                emitShaderSpan(
                    cx - half,
                    y0,
                    half * 2f,
                    y1 - y0,
                    shader,
                    paintAlpha(paint)
                );
            }
            else emit(cx - half, y0, half * 2f, y1 - y0, argb);
        }
    }

    static synchronized void circle(float x, float y, float radius, int argb) {
        circle(x, y, radius, argb, null);
    }

    static synchronized void circle(
        float x,
        float y,
        float radius,
        int argb,
        Object paint
    ) {
        if (radius <= 0f) return;
        oval(x - radius, y - radius, x + radius, y + radius, argb, paint);
    }

    static synchronized void point(
        float x,
        float y,
        float width,
        int argb,
        Object paint
    ) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        float resolved = Math.max(1f, width);
        ShaderCaptureStore.ShaderInfo shader =
            ShaderCaptureStore.fromPaint(paint);
        if (shader != null) {
            int color = applyOpacity(
                shader.sample(x, y),
                paintAlpha(paint)
            );
            emit(
                x - resolved / 2f,
                y - resolved / 2f,
                resolved,
                resolved,
                color
            );
        } else {
            emit(
                x - resolved / 2f,
                y - resolved / 2f,
                resolved,
                resolved,
                argb
            );
        }
    }

    static synchronized void points(
        float[] coords,
        int mode,
        float width,
        int argb,
        Object paint
    ) {
        if (!enabled() || coords == null || coords.length < 2) return;
        if (mode == 0) {
            for (int i = 0; i + 1 < coords.length; i += 2) {
                point(coords[i], coords[i + 1], width, argb, paint);
            }
            return;
        }

        if (mode == 1) {
            for (int i = 0; i + 3 < coords.length; i += 4) {
                line(
                    coords[i],
                    coords[i + 1],
                    coords[i + 2],
                    coords[i + 3],
                    width,
                    argb,
                    paint
                );
            }
            return;
        }

        for (int i = 0; i + 3 < coords.length; i += 2) {
            line(
                coords[i],
                coords[i + 1],
                coords[i + 2],
                coords[i + 3],
                width,
                argb,
                paint
            );
        }
    }

    static synchronized void paintCanvas(int argb, Object paint) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        ShaderCaptureStore.ShaderInfo shader =
            ShaderCaptureStore.fromPaint(paint);
        if (shader != null) {
            emitShaderRect(
                0f,
                0f,
                frameWidth,
                frameHeight,
                shader,
                paintAlpha(paint)
            );
        } else {
            emit(0f, 0f, frameWidth, frameHeight, argb);
        }
    }

    static synchronized void line(
        float x1,
        float y1,
        float x2,
        float y2,
        float width,
        int argb
    ) {
        line(x1, y1, x2, y2, width, argb, null);
    }

    static synchronized void line(
        float x1,
        float y1,
        float x2,
        float y2,
        float width,
        int argb,
        Object paint
    ) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;

        float dx = x2 - x1;
        float dy = y2 - y1;
        int steps = Math.max(
            1,
            (int)Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)))
        );
        float resolved = Math.max(1f, width);
        ShaderCaptureStore.ShaderInfo shader =
            ShaderCaptureStore.fromPaint(paint);
        MaskFilterCaptureStore.Blur blur =
            MaskFilterCaptureStore.fromPaint(paint);
        if (blur != null) {
            float left = Math.min(x1, x2) - resolved / 2f;
            float top = Math.min(y1, y2) - resolved / 2f;
            float right = Math.max(x1, x2) + resolved / 2f;
            float bottom = Math.max(y1, y2) + resolved / 2f;
            emitMaskBlur(
                left,
                top,
                right - left,
                bottom - top,
                blur,
                argb
            );
            if (!maskBlurIncludesSource(blur)) return;
        }

        PathEffectCaptureStore.Dash dash =
            PathEffectCaptureStore.fromPaint(paint);
        if (dash != null) {
            emitDashedLine(
                x1,
                y1,
                x2,
                y2,
                resolved,
                argb,
                shader,
                paintAlpha(paint),
                dash,
                paintStrokeCap(paint)
            );
            return;
        }

        emitCappedLine(
            x1,
            y1,
            x2,
            y2,
            resolved,
            argb,
            shader,
            paintAlpha(paint),
            paintStrokeCap(paint)
        );
    }

    private static void emitCappedLine(
        float x1,
        float y1,
        float x2,
        float y2,
        float width,
        int argb,
        ShaderCaptureStore.ShaderInfo shader,
        float opacity,
        String cap
    ) {
        float ax = x1;
        float ay = y1;
        float bx = x2;
        float by = y2;
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float)Math.sqrt(dx * dx + dy * dy);

        if ("SQUARE".equalsIgnoreCase(cap) && length > 0f) {
            float extension = width * 0.5f;
            float ux = dx / length;
            float uy = dy / length;
            ax -= ux * extension;
            ay -= uy * extension;
            bx += ux * extension;
            by += uy * extension;
        }

        emitLineSamples(
            ax,
            ay,
            bx,
            by,
            width,
            argb,
            shader,
            opacity
        );

        if ("ROUND".equalsIgnoreCase(cap)) {
            emitStrokeDisk(x1, y1, width * 0.5f, argb, shader, opacity);
            emitStrokeDisk(x2, y2, width * 0.5f, argb, shader, opacity);
        }
    }

    private static void emitStrokeDisk(
        float cx,
        float cy,
        float radius,
        int argb,
        ShaderCaptureStore.ShaderInfo shader,
        float opacity
    ) {
        if (radius <= 0f) return;
        int rows = Math.max(4, Math.min(48, (int)Math.ceil(radius * 2f)));
        for (int row = 0; row < rows && commands.size() < MAX_COMMANDS; row++) {
            float y0 = cy - radius + (2f * radius * row / rows);
            float y1 = cy - radius + (2f * radius * (row + 1) / rows);
            float sampleY = (y0 + y1) * 0.5f;
            float dy = sampleY - cy;
            float half = (float)Math.sqrt(Math.max(0f, radius * radius - dy * dy));
            if (half <= 0f) continue;
            int color = shader == null
                ? argb
                : applyOpacity(shader.sample(cx, sampleY), opacity);
            emit(cx - half, y0, half * 2f, y1 - y0, color);
        }
    }

    private static void emitLineSamples(
        float x1,
        float y1,
        float x2,
        float y2,
        float width,
        int argb,
        ShaderCaptureStore.ShaderInfo shader,
        float opacity
    ) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        int steps = Math.max(
            1,
            (int)Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)))
        );
        for (int i = 0; i <= steps; i++) {
            float t = (float)i / steps;
            float x = x1 + dx * t;
            float y = y1 + dy * t;
            int color = shader == null
                ? argb
                : applyOpacity(shader.sample(x, y), opacity);
            emit(
                x - width / 2f,
                y - width / 2f,
                width,
                width,
                color
            );
        }
    }

    private static void emitDashedLine(
        float x1,
        float y1,
        float x2,
        float y2,
        float width,
        int argb,
        ShaderCaptureStore.ShaderInfo shader,
        float opacity,
        PathEffectCaptureStore.Dash dash,
        String cap
    ) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float)Math.sqrt(dx * dx + dy * dy);
        if (length <= 0f) {
            emitCappedLine(x1, y1, x2, y2, width, argb, shader, opacity, cap);
            return;
        }

        float[] intervals = dash.intervals();
        float cycle = 0f;
        for (float interval : intervals) cycle += interval;
        if (cycle <= 0f) {
            emitCappedLine(x1, y1, x2, y2, width, argb, shader, opacity, cap);
            return;
        }

        float phase = dash.phase() % cycle;
        if (phase < 0f) phase += cycle;
        int index = 0;
        while (phase >= intervals[index]) {
            phase -= intervals[index];
            index = (index + 1) % intervals.length;
        }

        float position = -phase;
        while (position < length) {
            float segment = intervals[index];
            float start = Math.max(0f, position);
            float end = Math.min(length, position + segment);
            if ((index & 1) == 0 && end > start) {
                float t0 = start / length;
                float t1 = end / length;
                emitCappedLine(
                    x1 + dx * t0,
                    y1 + dy * t0,
                    x1 + dx * t1,
                    y1 + dy * t1,
                    width,
                    argb,
                    shader,
                    opacity,
                    cap
                );
            }
            position += segment;
            index = (index + 1) % intervals.length;
        }
    }

    static synchronized void arc(
        float left,
        float top,
        float right,
        float bottom,
        float startAngle,
        float sweepAngle,
        boolean includeCenter,
        int argb
    ) {
        arc(left, top, right, bottom, startAngle, sweepAngle, includeCenter, argb, null);
    }

    static synchronized void arc(
        float left,
        float top,
        float right,
        float bottom,
        float startAngle,
        float sweepAngle,
        boolean includeCenter,
        int argb,
        Object paint
    ) {
        if (!enabled()) return;
        ensureFrameState();
        if (state == null) return;
        float width = right - left;
        float height = bottom - top;
        if (width <= 0f || height <= 0f) return;

        float cx = left + width / 2f;
        float cy = top + height / 2f;
        float rx = width / 2f;
        float ry = height / 2f;
        int steps = Math.max(4, Math.min(64, (int)Math.ceil(Math.abs(sweepAngle) / 6f)));
        List<P> points = new ArrayList<>();
        if (includeCenter) points.add(new P(cx, cy));
        for (int i = 0; i <= steps; i++) {
            float t = (float)i / steps;
            double angle = Math.toRadians(startAngle + sweepAngle * t);
            points.add(new P(
                cx + (float)Math.cos(angle) * rx,
                cy + (float)Math.sin(angle) * ry
            ));
        }
        ShaderCaptureStore.ShaderInfo shader = ShaderCaptureStore.fromPaint(paint);
        if (shader != null) {
            fillPolygon(points, shader, paintAlpha(paint));
        }
        else fillPolygon(points, argb);
    }

    static synchronized boolean path(Object path, Object paint) {
        if (!enabled() || path == null) return false;
        ensureFrameState();
        if (state == null) return false;
        try {
            int color = paintColor(paint);
            float strokeWidth = paintStrokeWidth(paint);
            boolean stroke = paintMode(paint).contains("STROKE");
            ShaderCaptureStore.ShaderInfo shader = ShaderCaptureStore.fromPaint(paint);
            List<List<P>> contours = readPath(path);
            if (contours.isEmpty()) return false;

            MaskFilterCaptureStore.Blur blur =
                MaskFilterCaptureStore.fromPaint(paint);
            for (List<P> contour : contours) {
                if (blur != null && !contour.isEmpty()) {
                    float left = contour.stream().map(P::x).min(Float::compare).orElse(0f);
                    float top = contour.stream().map(P::y).min(Float::compare).orElse(0f);
                    float right = contour.stream().map(P::x).max(Float::compare).orElse(left);
                    float bottom = contour.stream().map(P::y).max(Float::compare).orElse(top);
                    emitMaskBlur(
                        left,
                        top,
                        Math.max(0f, right - left),
                        Math.max(0f, bottom - top),
                        blur,
                        color
                    );
                    if (!maskBlurIncludesSource(blur)) continue;
                }
                if (stroke) {
                    for (int i = 0; i + 1 < contour.size(); i++) {
                        P a = contour.get(i);
                        P b = contour.get(i + 1);
                        line(
                            a.x,
                            a.y,
                            b.x,
                            b.y,
                            strokeWidth,
                            color,
                            paint
                        );
                    }
                } else {
                    if (shader != null) {
                        fillPolygon(
                            contour,
                            shader,
                            paintAlpha(paint)
                        );
                    }
                    else fillPolygon(contour, color);
                }
            }
            return true;
        } catch (Throwable failure) {
            System.err.println("[Kanvas] path capture failed: " + failure.getMessage());
            if (strict) Runtime.getRuntime().halt(91);
            return false;
        }
    }

    private static boolean nativeTextureBackend(
        WindowContext context
    ) {
        if (context == null || context.handle == 0L) return false;
        int backend = NativeGpuBridge.activeBackend(context.handle);
        return backend == NativeGpuBridge.VULKAN ||
            backend == NativeGpuBridge.D3D9 ||
            backend == NativeGpuBridge.OPENGL ||
            backend == NativeGpuBridge.METAL;
    }

    private static int textureIdFor(
        WindowContext context,
        Object image,
        Object pixmap,
        int width,
        int height
    ) throws Exception {
        Integer existing = context.textureIds.get(image);
        if (existing != null) return existing;

        if (context.nextTextureId > 0x00ffffff) {
            context.nextTextureId = 1;
        }
        int id = context.nextTextureId++;

        int[] pixels = new int[Math.multiplyExact(width, height)];
        int output = 0;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                pixels[output++] = ((Number)invoke(
                    pixmap,
                    "getColor",
                    new Class<?>[]{int.class, int.class},
                    x,
                    y
                )).intValue();
            }
        }

        int status = NativeGpuBridge.uploadTexture(
            context.handle,
            id,
            width,
            height,
            pixels
        );
        if (status != NativeGpuBridge.STATUS_OK) return 0;

        context.textureIds.put(image, id);
        context.textureOrder.addLast(image);

        if (TEXTURE_PROVED.compareAndSet(false, true)) {
            String proof =
                "NATIVE_TEXTURE_UPLOADED id=" + id +
                " width=" + width +
                " height=" + height +
                " backend=" +
                backendName(
                    NativeGpuBridge.activeBackend(
                        context.handle
                    )
                );
            System.err.println("[Kanvas] " + proof);
            KanvasAgent.audit(proof);
        }

        // Keep the immutable image cache bounded.
        while (context.textureOrder.size() > 256) {
            Object oldest = context.textureOrder.removeFirst();
            Integer evicted = context.textureIds.remove(oldest);
            if (evicted != null) {
                NativeGpuBridge.releaseTexture(
                    context.handle,
                    evicted
                );
            }
        }

        return id;
    }

    private static int pictureTextureIdForAddress(
        WindowContext context,
        int width,
        int height,
        long address,
        int rowBytes
    ) {
        if (context.pictureTextureId <= 0) {
            if (context.nextTextureId > 0x00ffffff) {
                context.nextTextureId = 1;
            }
            context.pictureTextureId = context.nextTextureId++;
        }

        int status = NativeGpuBridge.uploadTextureAddress(
            context.handle,
            context.pictureTextureId,
            width,
            height,
            address,
            rowBytes
        );
        if (status != NativeGpuBridge.STATUS_OK) return 0;

        if (TEXTURE_PROVED.compareAndSet(false, true)) {
            String proof =
                "NATIVE_TEXTURE_UPLOADED id=" +
                context.pictureTextureId +
                " width=" + width +
                " height=" + height +
                " backend=" +
                backendName(
                    NativeGpuBridge.activeBackend(context.handle)
                );
            System.err.println("[Kanvas] " + proof);
            KanvasAgent.audit(proof);
        }

        return context.pictureTextureId;
    }

    private static boolean emitNativeImage(
        WindowContext context,
        Object image,
        Object pixmap,
        int imageWidth,
        int imageHeight,
        float srcLeft,
        float srcTop,
        float srcRight,
        float srcBottom,
        float dstLeft,
        float dstTop,
        float dstRight,
        float dstBottom
    ) throws Exception {
        if (!nativeTextureBackend(context) ||
            Math.abs(state.b) > 0.0001f ||
            Math.abs(state.c) > 0.0001f ||
            state.hasExclude ||
            state.hasRoundClip ||
            state.pathClip != null) {
            return false;
        }

        int textureId = textureIdFor(
            context,
            image,
            pixmap,
            imageWidth,
            imageHeight
        );
        if (textureId <= 0) return false;

        P p0 = transform(dstLeft, dstTop);
        P p1 = transform(dstRight, dstBottom);
        float left = Math.min(p0.x, p1.x);
        float top = Math.min(p0.y, p1.y);
        float right = Math.max(p0.x, p1.x);
        float bottom = Math.max(p0.y, p1.y);

        float fullW = right - left;
        float fullH = bottom - top;
        if (fullW <= 0f || fullH <= 0f) return true;

        float clippedLeft = Math.max(left, state.clipX);
        float clippedTop = Math.max(top, state.clipY);
        float clippedRight = Math.min(
            right,
            state.clipX + state.clipW
        );
        float clippedBottom = Math.min(
            bottom,
            state.clipY + state.clipH
        );
        if (clippedRight <= clippedLeft ||
            clippedBottom <= clippedTop) {
            return true;
        }

        float du0 = (clippedLeft - left) / fullW;
        float dv0 = (clippedTop - top) / fullH;
        float du1 = (clippedRight - left) / fullW;
        float dv1 = (clippedBottom - top) / fullH;

        float sourceW = Math.max(1f, srcRight - srcLeft);
        float sourceH = Math.max(1f, srcBottom - srcTop);
        float u0 = (srcLeft / imageWidth) +
            (sourceW / imageWidth) * du0;
        float v0 = (srcTop / imageHeight) +
            (sourceH / imageHeight) * dv0;
        float u1 = (srcLeft / imageWidth) +
            (sourceW / imageWidth) * du1;
        float v1 = (srcTop / imageHeight) +
            (sourceH / imageHeight) * dv1;

        if (commands.size() >= MAX_COMMANDS) return false;
        int encodedKind = 5 | (textureId << 8);
        commands.add(Command.image(
            encodedKind,
            clippedLeft,
            clippedTop,
            clippedRight - clippedLeft,
            clippedBottom - clippedTop,
            u0, v0, u1, v1
        ));
        requestPresent();
        return true;
    }

    static synchronized boolean picture(
        Object picture,
        Object matrix,
        Object paint
    ) {
        if (!enabled() || picture == null) return false;
        ensureFrameState();
        if (state == null ||
            currentContext == null ||
            frameWidth <= 0 ||
            frameHeight <= 0) {
            return false;
        }

        // Rasterize picture-backed layers into one reusable texture.
        Object surface = null;
        Object snapshot = null;
        try {
            ClassLoader loader = picture.getClass().getClassLoader();
            Class<?> surfaceClass =
                Class.forName("org.jetbrains.skia.Surface", true, loader);
            Object companion =
                surfaceClass.getField("Companion").get(null);
            java.lang.reflect.Method makeRaster =
                companion.getClass().getMethod(
                    "makeRasterN32Premul",
                    int.class,
                    int.class
                );
            surface = makeRaster.invoke(
                companion,
                frameWidth,
                frameHeight
            );

            Object canvas = invoke(surface, "getCanvas");

            if (matrix != null) {
                java.lang.reflect.Method concat = null;
                for (java.lang.reflect.Method candidate :
                     canvas.getClass().getMethods()) {
                    if (candidate.getName().equals("concat") &&
                        candidate.getParameterCount() == 1 &&
                        candidate.getParameterTypes()[0]
                            .isAssignableFrom(matrix.getClass())) {
                        concat = candidate;
                        break;
                    }
                }
                if (concat != null) {
                    concat.invoke(canvas, matrix);
                }
            }

            // Playback avoids re-entering the Canvas drawPicture hook.
            java.lang.reflect.Method playback = null;
            for (java.lang.reflect.Method candidate :
                 picture.getClass().getMethods()) {
                if (!candidate.getName().equals("playback")) continue;
                if (candidate.getParameterCount() == 2 &&
                    candidate.getParameterTypes()[0]
                        .isAssignableFrom(canvas.getClass())) {
                    playback = candidate;
                    break;
                }
                if (candidate.getParameterCount() == 1 &&
                    candidate.getParameterTypes()[0]
                        .isAssignableFrom(canvas.getClass())) {
                    playback = candidate;
                }
            }
            if (playback == null) return false;
            if (playback.getParameterCount() == 2) {
                playback.invoke(picture, canvas, null);
            } else {
                playback.invoke(picture, canvas);
            }

            snapshot = invoke(surface, "makeImageSnapshot");
            Object pixmap = invoke(snapshot, "peekPixels");
            if (pixmap == null) return false;

            long address =
                ((Number)invoke(pixmap, "getAddr")).longValue();
            int rowBytes =
                ((Number)invoke(pixmap, "getRowBytes")).intValue();
            int textureId = pictureTextureIdForAddress(
                currentContext,
                frameWidth,
                frameHeight,
                address,
                rowBytes
            );
            boolean emitted = textureId > 0;
            if (emitted) {
                if (commands.size() >= MAX_COMMANDS) {
                    emitted = false;
                } else {
                    commands.add(
                        Command.image(
                            5 | (textureId << 8),
                            0f,
                            0f,
                            frameWidth,
                            frameHeight,
                            0f,
                            0f,
                            1f,
                            1f
                        )
                    );
                    requestPresent();
                }
            }
            if (emitted) {
                KanvasAgent.audit(
                    "PICTURE_TEXTURE_CAPTURED width=" +
                    frameWidth + " height=" + frameHeight
                );
            }
            return emitted;
        } catch (Throwable failure) {
            System.err.println(
                "[Kanvas] picture capture failed: " +
                failure.getClass().getName() + ": " +
                failure.getMessage()
            );
            KanvasAgent.audit(
                "PICTURE_CAPTURE_FAILED " +
                failure.getClass().getName() + ": " +
                String.valueOf(failure.getMessage())
            );
            if (strict) Runtime.getRuntime().halt(109);
            return false;
        } finally {
            closeQuietly(snapshot);
            closeQuietly(surface);
        }
    }

    static synchronized boolean imageRect(
        Object image,
        float srcLeft,
        float srcTop,
        float srcRight,
        float srcBottom,
        float dstLeft,
        float dstTop,
        float dstRight,
        float dstBottom,
        Object paint
    ) {
        if (!enabled() || image == null) return false;
        ensureFrameState();
        if (state == null) return false;

        Object temporaryBitmap = null;
        try {
            Object pixmap = invoke(image, "peekPixels");
            if (pixmap == null) {
                temporaryBitmap = bitmapFromImage(image);
                if (temporaryBitmap != null) {
                    pixmap = invoke(temporaryBitmap, "peekPixels");
                }
            }
            if (pixmap == null) {
                if (strict) {
                    System.err.println(
                        "[Kanvas] strict takeover: image pixels are not readable"
                    );
                    Runtime.getRuntime().halt(92);
                }
                return false;
            }

            int width = ((Number)invoke(image, "getWidth")).intValue();
            int height = ((Number)invoke(image, "getHeight")).intValue();
            ColorFilterCaptureStore.FilterInfo colorFilter =
                ColorFilterCaptureStore.fromPaint(paint);
            int paintAlpha = paint == null
                ? 255
                : (paintColor(paint) >>> 24) & 0xff;

            /*
             * The common path: immutable Skia image, no per-draw filter or
             * opacity. Upload once and keep it as a real GPU texture across
             * dirty frames. Complex filtered/rotated/clipped cases retain the
             * compatibility raster fallback below.
             */
            if (colorFilter == null &&
                paintAlpha == 255 &&
                currentContext != null &&
                emitNativeImage(
                    currentContext,
                    image,
                    pixmap,
                    width,
                    height,
                    srcLeft,
                    srcTop,
                    srcRight,
                    srcBottom,
                    dstLeft,
                    dstTop,
                    dstRight,
                    dstBottom
                )) {
                return true;
            }

            int sx0 = Math.max(0, Math.min(width - 1, (int)Math.floor(srcLeft)));
            int sy0 = Math.max(0, Math.min(height - 1, (int)Math.floor(srcTop)));
            int sx1 = Math.max(sx0 + 1, Math.min(width, (int)Math.ceil(srcRight)));
            int sy1 = Math.max(sy0 + 1, Math.min(height, (int)Math.ceil(srcBottom)));

            float sourceW = Math.max(1f, srcRight - srcLeft);
            float sourceH = Math.max(1f, srcBottom - srcTop);
            float destW = dstRight - dstLeft;
            float destH = dstBottom - dstTop;
            int sourcePixels = Math.max(
                1,
                (sx1 - sx0) * (sy1 - sy0)
            );
            int remaining = Math.max(
                1,
                MAX_COMMANDS - commands.size() - 1
            );
            int available = Math.max(
                1,
                Math.min(4096, remaining)
            );
            int stride = Math.max(
                1,
                (int)Math.ceil(
                    Math.sqrt((double)sourcePixels / available)
                )
            );

            for (int sy = sy0; sy < sy1 && commands.size() < MAX_COMMANDS; sy += stride) {
                for (int sx = sx0; sx < sx1 && commands.size() < MAX_COMMANDS; sx += stride) {
                    int sampleX = Math.min(sx1 - 1, sx + stride / 2);
                    int sampleY = Math.min(sy1 - 1, sy + stride / 2);
                    int argb = ((Number)invoke(
                        pixmap,
                        "getColor",
                        new Class<?>[]{int.class, int.class},
                        sampleX,
                        sampleY
                    )).intValue();
                    if (colorFilter != null) {
                        argb = colorFilter.apply(argb);
                    }
                    int pixelAlpha = (argb >>> 24) & 0xff;
                    if (pixelAlpha == 0 || paintAlpha == 0) continue;
                    int resolvedAlpha =
                        (pixelAlpha * paintAlpha + 127) / 255;
                    argb = (resolvedAlpha << 24) | (argb & 0x00ffffff);

                    int bx1 = Math.min(sx1, sx + stride);
                    int by1 = Math.min(sy1, sy + stride);
                    float u0 = (sx - srcLeft) / sourceW;
                    float v0 = (sy - srcTop) / sourceH;
                    float u1 = (bx1 - srcLeft) / sourceW;
                    float v1 = (by1 - srcTop) / sourceH;
                    emit(
                        dstLeft + destW * u0,
                        dstTop + destH * v0,
                        destW * (u1 - u0),
                        destH * (v1 - v0),
                        argb
                    );
                }
            }
            return true;
        } catch (Throwable failure) {
            System.err.println(
                "[Kanvas] image capture failed: " +
                failure.getMessage()
            );
            if (strict) Runtime.getRuntime().halt(92);
            return false;
        } finally {
            closeQuietly(temporaryBitmap);
        }
    }

    static synchronized boolean drawRegion(
        Object region,
        Object paint
    ) {
        if (!enabled() || region == null) return false;
        Object path = null;
        try {
            path = invoke(region, "getBoundaryPath");
            return path != null && path(path, paint);
        } catch (Throwable failure) {
            System.err.println(
                "[Kanvas] region draw capture failed: " +
                failure.getMessage()
            );
            if (strict) Runtime.getRuntime().halt(99);
            return false;
        } finally {
            closeQuietly(path);
        }
    }

    static synchronized boolean clipRegion(Object region) {
        if (!enabled() || region == null) return false;
        Object path = null;
        try {
            path = invoke(region, "getBoundaryPath");
            return path != null && clipPath(path);
        } catch (Throwable failure) {
            System.err.println(
                "[Kanvas] region clip capture failed: " +
                failure.getMessage()
            );
            if (strict) Runtime.getRuntime().halt(100);
            return false;
        } finally {
            closeQuietly(path);
        }
    }

    static synchronized boolean imageNine(
        Object image,
        Object center,
        Object destination,
        Object paint
    ) {
        if (!enabled() || image == null || center == null || destination == null) {
            return false;
        }
        ensureFrameState();
        if (state == null) return false;

        try {
            int imageWidth = ((Number)invoke(image, "getWidth")).intValue();
            int imageHeight = ((Number)invoke(image, "getHeight")).intValue();

            float centerLeft = number(invoke(center, "getLeft"), 0f);
            float centerTop = number(invoke(center, "getTop"), 0f);
            float centerRight = number(invoke(center, "getRight"), imageWidth);
            float centerBottom = number(invoke(center, "getBottom"), imageHeight);

            float dstLeft = number(invoke(destination, "getLeft"), 0f);
            float dstTop = number(invoke(destination, "getTop"), 0f);
            float dstRight = number(invoke(destination, "getRight"), 0f);
            float dstBottom = number(invoke(destination, "getBottom"), 0f);

            float dstWidth = Math.max(0f, dstRight - dstLeft);
            float dstHeight = Math.max(0f, dstBottom - dstTop);
            if (dstWidth <= 0f || dstHeight <= 0f) return true;

            float leftBorder = Math.max(0f, centerLeft);
            float rightBorder = Math.max(0f, imageWidth - centerRight);
            float topBorder = Math.max(0f, centerTop);
            float bottomBorder = Math.max(0f, imageHeight - centerBottom);

            float horizontalBorders = leftBorder + rightBorder;
            if (horizontalBorders > dstWidth && horizontalBorders > 0f) {
                float scale = dstWidth / horizontalBorders;
                leftBorder *= scale;
                rightBorder *= scale;
            }

            float verticalBorders = topBorder + bottomBorder;
            if (verticalBorders > dstHeight && verticalBorders > 0f) {
                float scale = dstHeight / verticalBorders;
                topBorder *= scale;
                bottomBorder *= scale;
            }

            float[] sx = {
                0f,
                centerLeft,
                centerRight,
                imageWidth
            };
            float[] sy = {
                0f,
                centerTop,
                centerBottom,
                imageHeight
            };
            float[] dx = {
                dstLeft,
                dstLeft + leftBorder,
                dstRight - rightBorder,
                dstRight
            };
            float[] dy = {
                dstTop,
                dstTop + topBorder,
                dstBottom - bottomBorder,
                dstBottom
            };

            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    if (sx[column + 1] <= sx[column] ||
                        sy[row + 1] <= sy[row] ||
                        dx[column + 1] <= dx[column] ||
                        dy[row + 1] <= dy[row]) {
                        continue;
                    }

                    if (!imageRect(
                        image,
                        sx[column],
                        sy[row],
                        sx[column + 1],
                        sy[row + 1],
                        dx[column],
                        dy[row],
                        dx[column + 1],
                        dy[row + 1],
                        paint
                    )) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable failure) {
            System.err.println(
                "[Kanvas] nine-slice image capture failed: " +
                failure.getMessage()
            );
            if (strict) Runtime.getRuntime().halt(98);
            return false;
        }
    }

    private static GlyphEntry glyphEntry(
        WindowContext context,
        Font font,
        FontRenderContext frc,
        int glyphCode
    ) {
        if (context == null ||
            !nativeTextureBackend(context)) {
            return null;
        }

        GlyphKey key = new GlyphKey(
            font.getFamily(),
            font.getStyle(),
            Float.floatToIntBits(font.getSize2D()),
            glyphCode
        );
        GlyphEntry existing = context.glyphs.get(key);
        if (existing != null) return existing;

        GlyphVector single = font.createGlyphVector(
            frc,
            new int[]{glyphCode}
        );
        Rectangle2D bounds =
            single.getGlyphVisualBounds(0).getBounds2D();

        int width = Math.max(
            1,
            (int)Math.ceil(bounds.getWidth()) +
                GLYPH_PADDING * 2
        );
        int height = Math.max(
            1,
            (int)Math.ceil(bounds.getHeight()) +
                GLYPH_PADDING * 2
        );

        BufferedImage mask = new BufferedImage(
            width,
            height,
            BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = mask.createGraphics();
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );
            graphics.setRenderingHint(
                RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON
            );
            graphics.setColor(java.awt.Color.WHITE);
            graphics.drawGlyphVector(
                single,
                (float)(GLYPH_PADDING - bounds.getX()),
                (float)(GLYPH_PADDING - bounds.getY())
            );
        } finally {
            graphics.dispose();
        }

        GlyphAtlas chosen = null;
        int[] position = null;
        for (GlyphAtlas atlas : context.glyphAtlases) {
            position = atlas.place(mask);
            if (position != null) {
                chosen = atlas;
                break;
            }
        }

        if (chosen == null) {
            if (context.glyphAtlases.size() >= GLYPH_ATLAS_LIMIT) {
                return null;
            }
            if (context.nextTextureId > 0x00ffffff) {
                return null;
            }
            chosen = new GlyphAtlas(context.nextTextureId++);
            context.glyphAtlases.add(chosen);
            position = chosen.place(mask);
            if (position == null) return null;
        }

        float u0 = (float)position[0] / GLYPH_ATLAS_SIZE;
        float v0 = (float)position[1] / GLYPH_ATLAS_SIZE;
        float u1 =
            (float)(position[0] + width) / GLYPH_ATLAS_SIZE;
        float v1 =
            (float)(position[1] + height) / GLYPH_ATLAS_SIZE;

        GlyphEntry created = new GlyphEntry(
            chosen.textureId,
            u0, v0, u1, v1,
            (float)bounds.getX() - GLYPH_PADDING,
            (float)bounds.getY() - GLYPH_PADDING,
            width,
            height
        );
        context.glyphs.put(key, created);
        return created;
    }

    private static void flushGlyphAtlases(WindowContext context) {
        if (context == null ||
            context.handle == 0L ||
            !nativeTextureBackend(context)) {
            return;
        }

        for (GlyphAtlas atlas : context.glyphAtlases) {
            if (!atlas.dirty) continue;
            int[] pixels = atlas.image.getRGB(
                0, 0,
                GLYPH_ATLAS_SIZE,
                GLYPH_ATLAS_SIZE,
                null,
                0,
                GLYPH_ATLAS_SIZE
            );
            int status = NativeGpuBridge.uploadTexture(
                context.handle,
                atlas.textureId,
                GLYPH_ATLAS_SIZE,
                GLYPH_ATLAS_SIZE,
                pixels
            );
            if (status != NativeGpuBridge.STATUS_OK) {
                continue;
            }
            atlas.dirty = false;

            if (GLYPH_ATLAS_PROVED.compareAndSet(false, true)) {
                String proof =
                    "NATIVE_GLYPH_ATLAS_UPLOADED id=" +
                    atlas.textureId +
                    " size=" + GLYPH_ATLAS_SIZE +
                    " backend=" +
                    backendName(
                        NativeGpuBridge.activeBackend(
                            context.handle
                        )
                    );
                System.err.println(
                    "[Kanvas] " + proof
                );
                KanvasAgent.audit(proof);
            }
        }
    }

    private static Float emitGlyphRun(
        String text,
        Font font,
        FontRenderContext frc,
        float x,
        float baselineY,
        int argb
    ) {
        if (text == null || text.isEmpty() ||
            currentContext == null ||
            !nativeTextureBackend(currentContext) ||
            java.text.Bidi.requiresBidi(
                text.toCharArray(),
                0,
                text.length()
            )) {
            return null;
        }

        char[] chars = text.toCharArray();
        GlyphVector vector = font.layoutGlyphVector(
            frc,
            chars,
            0,
            chars.length,
            Font.LAYOUT_LEFT_TO_RIGHT
        );

        int glyphCount = vector.getNumGlyphs();
        for (int i = 0; i < glyphCount; ++i) {
            GlyphEntry entry = glyphEntry(
                currentContext,
                font,
                frc,
                vector.getGlyphCode(i)
            );
            if (entry == null) return null;

            java.awt.geom.Point2D position =
                vector.getGlyphPosition(i);
            float gx =
                x + (float)position.getX() + entry.offsetX();
            float gy =
                baselineY +
                (float)position.getY() +
                entry.offsetY();

            /*
             * Atlas glyphs currently support the normal axis-aligned text
             * path. Rotated/sheared/path-clipped text retains the existing
             * compatibility raster path for correctness.
             */
            if (Math.abs(state.b) > 0.0001f ||
                Math.abs(state.c) > 0.0001f ||
                state.hasExclude ||
                state.hasRoundClip ||
                state.pathClip != null) {
                return null;
            }

            P p0 = transform(gx, gy);
            P p1 = transform(
                gx + entry.width(),
                gy + entry.height()
            );
            float left = Math.min(p0.x, p1.x);
            float top = Math.min(p0.y, p1.y);
            float right = Math.max(p0.x, p1.x);
            float bottom = Math.max(p0.y, p1.y);

            float clippedLeft = Math.max(left, state.clipX);
            float clippedTop = Math.max(top, state.clipY);
            float clippedRight = Math.min(
                right,
                state.clipX + state.clipW
            );
            float clippedBottom = Math.min(
                bottom,
                state.clipY + state.clipH
            );
            if (clippedRight <= clippedLeft ||
                clippedBottom <= clippedTop) {
                continue;
            }

            float fullW = right - left;
            float fullH = bottom - top;
            float du0 = (clippedLeft - left) / fullW;
            float dv0 = (clippedTop - top) / fullH;
            float du1 = (clippedRight - left) / fullW;
            float dv1 = (clippedBottom - top) / fullH;

            float u0 = entry.u0() +
                (entry.u1() - entry.u0()) * du0;
            float v0 = entry.v0() +
                (entry.v1() - entry.v0()) * dv0;
            float u1 = entry.u0() +
                (entry.u1() - entry.u0()) * du1;
            float v1 = entry.v0() +
                (entry.v1() - entry.v0()) * dv1;

            if (commands.size() >= MAX_COMMANDS) return null;
            int encodedKind =
                6 | (entry.textureId() << 8);
            commands.add(Command.glyph(
                encodedKind,
                clippedLeft,
                clippedTop,
                clippedRight - clippedLeft,
                clippedBottom - clippedTop,
                applyOpacity(argb, state.opacity),
                u0, v0, u1, v1
            ));
        }

        requestPresent();
        return (float)vector.getGlyphPosition(
            glyphCount
        ).getX();
    }

    static synchronized boolean paragraph(Object paragraph, float x, float y) {
        if (!enabled() || paragraph == null) return false;
        ensureFrameState();
        if (state == null) return false;
        try {
            Object raw = invoke(paragraph, "getText");
            String text = raw == null ? "" : raw.toString();
            if (text.isEmpty()) return true;

            ParagraphCaptureStore.ParagraphInfo info =
                ParagraphCaptureStore.get(paragraph);
            if (info != null && !info.runs().isEmpty()) {
                renderCapturedParagraph(paragraph, info, x, y);
                return true;
            }

            float paragraphHeight = number(invoke(paragraph, "getHeight"), 16f);
            int lineCount = Math.max(
                1,
                (int)number(invoke(paragraph, "getLineNumber"), 1f)
            );
            float size = Math.max(
                8f,
                Math.min(96f, (paragraphHeight / lineCount) * 0.82f)
            );

            renderAwtText(text, x, y, size, 0xffffffff);
            return true;
        } catch (Throwable failure) {
            System.err.println(
                "[Kanvas] paragraph capture failed: " +
                failure.getMessage()
            );
            if (strict) Runtime.getRuntime().halt(93);
            return false;
        }
    }

    private static void renderCapturedParagraph(
        Object paragraph,
        ParagraphCaptureStore.ParagraphInfo info,
        float originX,
        float originY
    ) throws Exception {
        Object metricsValue = invoke(paragraph, "getLineMetrics");
        int metricsCount = metricsValue != null && metricsValue.getClass().isArray()
            ? java.lang.reflect.Array.getLength(metricsValue)
            : 0;

        if (metricsCount == 0) {
            float cursorX = originX;
            for (ParagraphCaptureStore.Run run : info.runs()) {
                cursorX += renderStyledRun(
                    run.text(),
                    run.style(),
                    cursorX,
                    originY + run.style().size()
                );
            }
            return;
        }

        int runStart = 0;
        for (int lineIndex = 0; lineIndex < metricsCount; lineIndex++) {
            Object metric = java.lang.reflect.Array.get(metricsValue, lineIndex);
            int lineStart = ((Number)invoke(metric, "getStartIndex")).intValue();
            int lineEnd = ((Number)invoke(metric, "getEndIndex")).intValue();
            float lineLeft = number(invoke(metric, "getLeft"), 0f);
            float baseline = number(invoke(metric, "getBaseline"), 0f);
            float cursorX = originX + lineLeft;

            int textCursor = 0;
            for (ParagraphCaptureStore.Run run : info.runs()) {
                String runText = run.text();
                int runEnd = textCursor + runText.length();
                int segmentStart = Math.max(lineStart, textCursor);
                int segmentEnd = Math.min(lineEnd, runEnd);

                if (segmentEnd > segmentStart) {
                    String segment = runText.substring(
                        segmentStart - textCursor,
                        segmentEnd - textCursor
                    ).replace("\n", "");
                    if (!segment.isEmpty()) {
                        cursorX += renderStyledRun(
                            segment,
                            run.style(),
                            cursorX,
                            originY + baseline
                        );
                    }
                }
                textCursor = runEnd;
                if (textCursor >= lineEnd) break;
            }
            runStart = lineEnd;
        }
    }

    private static float renderStyledRun(
        String text,
        ParagraphCaptureStore.Style style,
        float x,
        float baselineY
    ) {
        if (text == null || text.isEmpty()) return 0f;

        int fontStyle = Font.PLAIN;
        if (style.weight() >= 600) fontStyle |= Font.BOLD;
        if (style.italic()) fontStyle |= Font.ITALIC;

        Font font = new Font(
            style.family(),
            fontStyle,
            1
        ).deriveFont(Math.max(1f, style.size()));
        FontRenderContext frc = new FontRenderContext(null, true, true);
        TextLayout layout = new TextLayout(text, font, frc);

        Float nativeAdvance = emitGlyphRun(
            text,
            font,
            frc,
            x,
            baselineY,
            style.color()
        );
        if (nativeAdvance != null) return layout.getAdvance();

        int width = Math.max(1, (int)Math.ceil(layout.getAdvance()) + 4);
        int height = Math.max(
            1,
            (int)Math.ceil(
                layout.getAscent() +
                layout.getDescent() +
                layout.getLeading()
            ) + 4
        );

        BufferedImage image = new BufferedImage(
            width,
            height,
            BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );
            graphics.setRenderingHint(
                RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON
            );
            graphics.setFont(font);
            graphics.setColor(java.awt.Color.WHITE);
            layout.draw(graphics, 2f, 2f + layout.getAscent());
        } finally {
            graphics.dispose();
        }

        emitMask(
            image,
            x,
            baselineY - layout.getAscent() - 2f,
            style.color()
        );
        return layout.getAdvance();
    }

    private static void renderAwtText(
        String text,
        float x,
        float y,
        float size,
        int argb
    ) {
        String[] lines = text.split("\\n", -1);
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(1, Math.round(size)));
        FontRenderContext frc = new FontRenderContext(null, true, true);

        float cursorY = y;
        for (String line : lines) {
            String drawable = line.isEmpty() ? " " : line;
            TextLayout layout = new TextLayout(drawable, font, frc);
            float baseline =
                cursorY + 2f + layout.getAscent();
            Float nativeAdvance = emitGlyphRun(
                drawable,
                font,
                frc,
                x,
                baseline,
                argb
            );
            if (nativeAdvance != null) {
                cursorY += Math.max(
                    size,
                    layout.getAscent() +
                    layout.getDescent() +
                    layout.getLeading()
                );
                continue;
            }

            int width = Math.max(1, (int)Math.ceil(layout.getAdvance()) + 4);
            int height = Math.max(
                1,
                (int)Math.ceil(layout.getAscent() + layout.getDescent() + layout.getLeading()) + 4
            );

            BufferedImage image = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB
            );
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                );
                graphics.setRenderingHint(
                    RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON
                );
                graphics.setFont(font);
                graphics.setColor(java.awt.Color.WHITE);
                layout.draw(graphics, 2f, 2f + layout.getAscent());
            } finally {
                graphics.dispose();
            }

            emitMask(image, x, cursorY, argb);
            cursorY += Math.max(
                size,
                layout.getAscent() + layout.getDescent() + layout.getLeading()
            );
        }
    }

    private static void emitMask(
        BufferedImage image,
        float x,
        float y,
        int argb
    ) {
        int sourceAlpha = (argb >>> 24) & 0xff;
        int rgb = argb & 0x00ffffff;

        for (int row = 0; row < image.getHeight(); row++) {
            int column = 0;
            while (column < image.getWidth()) {
                int pixel = image.getRGB(column, row);
                int alpha = (pixel >>> 24) & 0xff;
                if (alpha == 0) {
                    column++;
                    continue;
                }

                int quantized = (alpha + 15) / 32;
                int start = column;
                column++;
                while (column < image.getWidth()) {
                    int next = (image.getRGB(column, row) >>> 24) & 0xff;
                    if (next == 0 || (next + 15) / 32 != quantized) break;
                    column++;
                }

                int resolvedAlpha = Math.min(
                    255,
                    sourceAlpha * Math.max(1, quantized * 32) / 255
                );
                emit(
                    x + start,
                    y + row,
                    column - start,
                    1f,
                    (resolvedAlpha << 24) | rgb
                );
            }
        }
    }

    static synchronized void string(
        String text,
        float x,
        float baselineY,
        float size,
        int argb
    ) {
        if (!enabled() || text == null || text.isEmpty()) return;
        ensureFrameState();
        if (state == null) return;

        Font font = new Font(
            Font.SANS_SERIF,
            Font.PLAIN,
            Math.max(1, Math.round(size))
        );
        FontRenderContext frc = new FontRenderContext(null, true, true);
        TextLayout layout = new TextLayout(text, font, frc);

        Float nativeAdvance = emitGlyphRun(
            text,
            font,
            frc,
            x,
            baselineY,
            argb
        );
        if (nativeAdvance != null) return;

        int width = Math.max(1, (int)Math.ceil(layout.getAdvance()) + 4);
        int height = Math.max(
            1,
            (int)Math.ceil(
                layout.getAscent() +
                layout.getDescent() +
                layout.getLeading()
            ) + 4
        );

        BufferedImage image = new BufferedImage(
            width,
            height,
            BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );
            graphics.setRenderingHint(
                RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON
            );
            graphics.setFont(font);
            graphics.setColor(java.awt.Color.WHITE);
            layout.draw(graphics, 2f, 2f + layout.getAscent());
        } finally {
            graphics.dispose();
        }

        emitMask(
            image,
            x,
            baselineY - layout.getAscent() - 2f,
            argb
        );
    }

    static synchronized void requestPresent() {
        if (!enabled() || presentQueued || inFrame) return;
        WindowContext queuedContext = currentContext;
        presentQueued = true;
        EventQueue.invokeLater(() -> {
            synchronized (LiveGpuTakeover.class) {
                presentQueued = false;
                if (inFrame) return;
                if (queuedContext != null) currentContext = queuedContext;
                present();
            }
        });
    }

    static synchronized void present() {
        if (!enabled() || frameWidth <= 0 || frameHeight <= 0) return;
        WindowContext context = currentContext;
        if (context == null) {
            Window window = resolveComposeWindow(null);
            if (window == null) return;
            context = CONTEXTS.computeIfAbsent(window, WindowContext::new);
            currentContext = context;
        }

        try {
            ensureRenderer(context);
            if (context.handle == 0L) return;

            if (!NativeGpuBridge.poll(context.handle)) {
                NativeGpuBridge.destroy(context.handle);
                context.handle = 0L;
                context.retainedFrame = false;
                context.textureIds.clear();
                context.textureOrder.clear();
                context.glyphs.clear();
                context.glyphAtlases.clear();
                CONTEXTS.remove(context.composeWindow);
                requestComposeClose(context);
                return;
            }

            syncComposeSize(context);

            if (commands.isEmpty()) {
                if (context.retainedFrame) return;
                commands.add(0, new Command(1, 0f, 0f, 0f, 0f, 0x00000000));
            } else if (commands.get(0).kind != 1 && commands.get(0).kind != 4) {
                commands.add(
                    0,
                    new Command(
                        context.retainedFrame ? 4 : 1,
                        0f, 0f, 0f, 0f, 0x00000000
                    )
                );
            }

            if (commands.size() == 1 && commands.get(0).kind == 4) {
                commands.clear();
                stack.clear();
                return;
            }

            int count = Math.min(commands.size(), MAX_COMMANDS);
            flushGlyphAtlases(context);

            int[] kinds = new int[count];
            float[] geometry = new float[count * 4];
            float[] colors = new float[count * 4];
            float[] uvs = new float[count * 4];

            for (int i = 0; i < count; i++) {
                Command command = commands.get(i);
                kinds[i] = command.kind;
                float nativeScale = nativeGeometryScale(context);
                geometry[i * 4] = command.x * nativeScale;
                geometry[i * 4 + 1] = command.y * nativeScale;
                geometry[i * 4 + 2] = command.w * nativeScale;
                geometry[i * 4 + 3] = command.h * nativeScale;
                writeColor(colors, i, command.argb);
                uvs[i * 4] = command.u0;
                uvs[i * 4 + 1] = command.v0;
                uvs[i * 4 + 2] = command.u1;
                uvs[i * 4 + 3] = command.v1;
            }

            int status = NativeGpuBridge.present(
                context.handle,
                kinds,
                geometry,
                colors,
                uvs
            );
            if (status == NativeGpuBridge.STATUS_SWAPCHAIN_OUT_OF_DATE) {
                // Minimized/resizing windows can temporarily have no drawable surface.
                commands.clear();
                stack.clear();
                return;
            }
            if (status != NativeGpuBridge.STATUS_OK) {
                System.err.println(
                    "[Kanvas] native takeover present failed: " +
                    status
                );
                if (strict) Runtime.getRuntime().halt(89);
            } else {
                boolean retainedPresent =
                    !commands.isEmpty() &&
                    commands.get(0).kind == 4 &&
                    commands.size() > 1;
                context.retainedFrame = true;
                context.presentedFrames++;
                if (retainedPresent) {
                    context.retainedUpdates++;
                }

                if (context.presentedFrames == 120 ||
                    (context.presentedFrames > 120 &&
                     context.presentedFrames % 600 == 0)) {
                    String dynamicProof =
                        "DYNAMIC_FRAME_PROGRESS frames=" +
                        context.presentedFrames +
                        " retainedUpdates=" +
                        context.retainedUpdates +
                        " backend=" +
                        backendName(
                            NativeGpuBridge.activeBackend(
                                context.handle
                            )
                        );
                    System.err.println(
                        "[Kanvas] " + dynamicProof
                    );
                    KanvasAgent.audit(dynamicProof);
                }

                if (retainedPresent &&
                    RETAIN_PROVED.compareAndSet(false, true)) {
                    String retainedProof =
                        "RETAINED_FRAME_PRESENTED width=" + context.width +
                        " height=" + context.height +
                        " commands=" + count;
                    System.err.println("[Kanvas] " + retainedProof);
                    KanvasAgent.audit(retainedProof);
                }

                if (PRESENT_PROVED.compareAndSet(false, true)) {
                String proof =
                    "TAKEOVER_PRESENTED width=" + context.width +
                    " height=" + context.height +
                    " commands=" + count;
                System.err.println("[Kanvas] " + proof);
                KanvasAgent.audit(proof);
                }
            }
            forwardPointers(context);
            commands.clear();
            stack.clear();
            state = new State(
                Math.max(1, context.width),
                Math.max(1, context.height)
            );
        } catch (Throwable failure) {
            System.err.println("[Kanvas] native takeover failed: " + failure);
            if (context.handle != 0L) {
                try {
                    NativeGpuBridge.destroy(context.handle);
                } catch (Throwable ignored) {
                }
                context.handle = 0L;
            }
            if (strict) Runtime.getRuntime().halt(89);
        }
    }

    static synchronized void close() {
        for (WindowContext context : List.copyOf(CONTEXTS.values())) {
            if (context.handle == 0L) continue;
            try {
                NativeGpuBridge.destroy(context.handle);
            } catch (Throwable ignored) {
            }
            context.handle = 0L;
            context.textureIds.clear();
            context.textureOrder.clear();
            context.glyphs.clear();
            context.glyphAtlases.clear();
        }
        CONTEXTS.clear();
        currentContext = null;
    }

    private static void ensureRenderer(WindowContext context) {
        if (context == null || context.handle != 0L) return;

        /*
         * On macOS Skiko can invoke us from its render thread while AppKit's
         * main thread is synchronously waiting for that render to finish.
         * Native Cocoa/Metal setup must execute on AppKit's main queue, so
         * doing it directly here can deadlock the whole application before
         * either the Compose window or takeover window becomes visible.
         *
         * Hop through AWT's event queue first. From there the native bridge
         * can safely synchronize with AppKit without holding Skiko's render
         * thread hostage. Frames captured before creation completes simply
         * remain pending and the next frame presents normally.
         */
        if (isMac() && !EventQueue.isDispatchThread()) {
            if (!context.rendererCreateQueued) {
                context.rendererCreateQueued = true;
                EventQueue.invokeLater(() -> {
                    synchronized (LiveGpuTakeover.class) {
                        context.rendererCreateQueued = false;
                        try {
                            ensureRenderer(context);
                        } catch (Throwable failure) {
                            System.err.println(
                                "[Kanvas] macOS renderer creation failed: " +
                                failure.getMessage()
                            );
                            KanvasAgent.audit(
                                "MAC_RENDERER_CREATE_FAILED " +
                                failure.getClass().getName() + ": " +
                                String.valueOf(failure.getMessage())
                            );
                            if (strict) Runtime.getRuntime().halt(89);
                        }
                    }
                });
            }
            return;
        }

        NativeGpuBridge.ensureLoaded();

        int deviceWidth = Math.max(
            1,
            context.width > 0 ? context.width : frameWidth
        );
        int deviceHeight = Math.max(
            1,
            context.height > 0 ? context.height : frameHeight
        );
        int width = nativeWindowWidth(context, deviceWidth);
        int height = nativeWindowHeight(context, deviceHeight);
        String title = context.composeWindow == null
            ? "Kanvas | intercepted Compose"
            : "Kanvas | " +
                context.composeWindow.getName();

        if ((isWindows() || isMac() || isLinux()) &&
            context.composeWindow != null) {
            Component host = context.renderHost != null
                ? context.renderHost
                : context.composeWindow;

            /*
             * macOS JAWT platformInfo points at the peer's AWTSurfaceLayers
             * object. Once AWT teardown starts that peer can already be gone
             * while a queued final Skiko draw still reaches us. Calling
             * setLayer: on that stale object crashes the whole JVM in native
             * code, so never attempt a fresh attachment to a disposed peer.
             */
            if (!host.isDisplayable() ||
                !context.composeWindow.isDisplayable()) {
                context.rendererCreateQueued = false;
                return;
            }

            context.handle = NativeGpuBridge.createAttached(
                host,
                width,
                height,
                backendId()
            );
            context.embedded = context.handle != 0L;
            if (context.embedded) {
                String hostProof =
                    "EMBEDDED_HOST class=" + host.getClass().getName();
                System.err.println("[Kanvas] " + hostProof);
                KanvasAgent.audit(hostProof);
            }
        }

        if (context.handle == 0L) {
            context.handle = NativeGpuBridge.create(
                width,
                height,
                title,
                backendId()
            );
            context.embedded = false;
        }

        if (context.handle == 0L) {
            throw new IllegalStateException(
                "Could not create Kanvas native renderer"
            );
        }

        if (!context.embedded) {
            parkComposeWindow(context);
        }

        int activeBackend = NativeGpuBridge.activeBackend(context.handle);
        String backendProof =
            "ACTIVE_BACKEND id=" + activeBackend +
            " name=" + backendName(activeBackend);
        System.err.println("[Kanvas] " + backendProof);
        KanvasAgent.audit(backendProof);

        if (strict && activeBackend == NativeGpuBridge.GDI) {
            throw new IllegalStateException(
                "Strict takeover requires a GPU backend; AUTO resolved to GDI"
            );
        }

        System.err.println(
            "[Kanvas] live GPU takeover active (" +
            (context.embedded ? "embedded" : "standalone") +
            ") for " + context.composeWindow
        );

        /*
         * Renderer creation on macOS is intentionally queued onto AWT before
         * crossing to AppKit. The draw that caused creation may therefore have
         * finished before a native handle existed. Force one more AWT paint so
         * SkiaLayer schedules a fresh replay and the takeover can capture it.
         */
        if (isMac() && context.composeWindow != null) {
            Component host = context.renderHost != null
                ? context.renderHost
                : context.composeWindow;
            if (host.isDisplayable()) {
                host.repaint();
                context.composeWindow.repaint();
            }
        }
    }

    private static String backendName(int backend) {
        return switch (backend) {
            case NativeGpuBridge.VULKAN -> "vulkan";
            case NativeGpuBridge.METAL -> "metal";
            case NativeGpuBridge.OPENGL -> "opengl";
            case NativeGpuBridge.D3D9 -> "d3d9";
            case NativeGpuBridge.GDI -> "gdi";
            default -> "unknown";
        };
    }

    private static int backendId() {
        String requested = System.getProperty(
            "kanvas.backend",
            System.getProperty(
                "kotlin.display.backend",
                System.getenv().getOrDefault(
                    "KANVAS_BACKEND",
                    System.getenv().getOrDefault("KD_BACKEND", "auto")
                )
            )
        ).toLowerCase();
        if (requested.equals("metal")) return NativeGpuBridge.METAL;
        if (requested.equals("vulkan")) return NativeGpuBridge.VULKAN;
        if (requested.equals("opengl")) return NativeGpuBridge.OPENGL;
        if (requested.equals("d3d9")) return NativeGpuBridge.D3D9;
        if (requested.equals("gdi")) return NativeGpuBridge.GDI;
        return NativeGpuBridge.AUTO;
    }

    private static void forwardPointers(WindowContext context) {
        if (context == null || context.handle == 0L) return;
        int[] event = new int[3];
        while (NativeGpuBridge.nextPointer(context.handle, event)) {
            int kind = event[0];
            int x = event[1];
            int y = event[2];
            EventQueue.invokeLater(() ->
                dispatchPointer(context, kind, x, y)
            );
        }
    }

    private static void dispatchPointer(
        WindowContext context,
        int kind,
        int x,
        int y
    ) {
        Window target = context == null ? null : context.composeWindow;
        if (target == null) return;

        if (kind == 12 || kind == 13) {
            Component component = keyboardTarget(context);
            if (kind == 12) {
                target.dispatchEvent(
                    new WindowEvent(target, WindowEvent.WINDOW_GAINED_FOCUS)
                );
                if (component != null) {
                    component.dispatchEvent(
                        new FocusEvent(component, FocusEvent.FOCUS_GAINED)
                    );
                }
            } else {
                if (component != null) {
                    component.dispatchEvent(
                        new FocusEvent(component, FocusEvent.FOCUS_LOST)
                    );
                }
                target.dispatchEvent(
                    new WindowEvent(target, WindowEvent.WINDOW_LOST_FOCUS)
                );
            }
            return;
        }

        if (kind == 5 || kind == 6 || kind == 7) {
            Component component = keyboardTarget(context);
            if (component == null) return;
            int id = kind == 5
                ? KeyEvent.KEY_PRESSED
                : kind == 6 ? KeyEvent.KEY_RELEASED : KeyEvent.KEY_TYPED;
            int keyCode = kind == 7 ? KeyEvent.VK_UNDEFINED : x;
            char keyChar = kind == 7 ? (char)x : KeyEvent.CHAR_UNDEFINED;
            component.dispatchEvent(new KeyEvent(
                component,
                id,
                System.currentTimeMillis(),
                awtModifiers(y),
                keyCode,
                keyChar
            ));
            return;
        }

        if (kind == 14) {
            Component component = target.getComponentAt(
                context.pointerX,
                context.pointerY
            );
            if (component == null) component = target;
            double precise = x / 120.0;
            int rotation = precise == 0.0
                ? 0
                : (precise > 0.0 ? Math.max(1, (int)Math.round(precise)) :
                    Math.min(-1, (int)Math.round(precise)));
            component.dispatchEvent(new MouseWheelEvent(
                component,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                InputEvent.SHIFT_DOWN_MASK,
                context.pointerX,
                context.pointerY,
                context.pointerX,
                context.pointerY,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                3,
                rotation,
                precise
            ));
            return;
        }

        if (kind == 4) {
            Component component = target.getComponentAt(
                context.pointerX,
                context.pointerY
            );
            if (component == null) component = target;
            double precise = y / 120.0;
            int rotation = precise == 0.0
                ? 0
                : (precise > 0.0 ? Math.max(1, (int)Math.round(precise)) :
                    Math.min(-1, (int)Math.round(precise)));
            component.dispatchEvent(new MouseWheelEvent(
                component,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                0,
                context.pointerX,
                context.pointerY,
                context.pointerX,
                context.pointerY,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                3,
                rotation,
                precise
            ));
            return;
        }

        int logicalX = inputLogicalCoordinate(context, x);
        int logicalY = inputLogicalCoordinate(context, y);
        if (kind == 16 && x == 0 && y == 0) {
            logicalX = context.pointerX;
            logicalY = context.pointerY;
        } else {
            context.pointerX = logicalX;
            context.pointerY = logicalY;
        }

        int id;
        int button;
        boolean popupTrigger = false;
        switch (kind) {
            case 1 -> {
                context.buttonMask |= InputEvent.BUTTON1_DOWN_MASK;
                id = MouseEvent.MOUSE_PRESSED;
                button = MouseEvent.BUTTON1;
            }
            case 2 -> {
                id = MouseEvent.MOUSE_RELEASED;
                button = MouseEvent.BUTTON1;
            }
            case 3 -> {
                id = context.buttonMask == 0
                    ? MouseEvent.MOUSE_MOVED
                    : MouseEvent.MOUSE_DRAGGED;
                button = MouseEvent.NOBUTTON;
            }
            case 8 -> {
                context.buttonMask |= InputEvent.BUTTON3_DOWN_MASK;
                id = MouseEvent.MOUSE_PRESSED;
                button = MouseEvent.BUTTON3;
                popupTrigger = true;
            }
            case 9 -> {
                id = MouseEvent.MOUSE_RELEASED;
                button = MouseEvent.BUTTON3;
                popupTrigger = true;
            }
            case 10 -> {
                context.buttonMask |= InputEvent.BUTTON2_DOWN_MASK;
                id = MouseEvent.MOUSE_PRESSED;
                button = MouseEvent.BUTTON2;
            }
            case 11 -> {
                id = MouseEvent.MOUSE_RELEASED;
                button = MouseEvent.BUTTON2;
            }
            case 15 -> {
                id = MouseEvent.MOUSE_ENTERED;
                button = MouseEvent.NOBUTTON;
            }
            case 16 -> {
                id = MouseEvent.MOUSE_EXITED;
                button = MouseEvent.NOBUTTON;
            }
            default -> {
                return;
            }
        }

        int modifiers = context.buttonMask;
        Component component = target.getComponentAt(logicalX, logicalY);
        if (component == null) component = target;
        component.dispatchEvent(new MouseEvent(
            component,
            id,
            System.currentTimeMillis(),
            modifiers,
            logicalX,
            logicalY,
            (kind == 3 || kind == 15 || kind == 16) ? 0 : 1,
            popupTrigger,
            button
        ));

        if (kind == 2) {
            context.buttonMask &= ~InputEvent.BUTTON1_DOWN_MASK;
        } else if (kind == 9) {
            context.buttonMask &= ~InputEvent.BUTTON3_DOWN_MASK;
        } else if (kind == 11) {
            context.buttonMask &= ~InputEvent.BUTTON2_DOWN_MASK;
        }
    }

    private static void syncComposeSize(WindowContext context) {
        if (context == null ||
            context.handle == 0L ||
            context.composeWindow == null) return;

        if (context.embedded) {
            int width = nativeWindowWidth(
                context,
                Math.max(1, context.width)
            );
            int height = nativeWindowHeight(
                context,
                Math.max(1, context.height)
            );

            long packed = NativeGpuBridge.size(context.handle);
            int currentWidth = (int)(packed >>> 32);
            int currentHeight = (int)(packed & 0xffffffffL);
            if (currentWidth != width || currentHeight != height) {
                context.retainedFrame = false;
                NativeGpuBridge.resize(context.handle, width, height);
            }

            if (context == currentContext) {
                frameWidth = context.width;
                frameHeight = context.height;
            }
            return;
        }

        long packed = NativeGpuBridge.size(context.handle);
        int nativeWidth = (int)(packed >>> 32);
        int nativeHeight = (int)(packed & 0xffffffffL);
        if (nativeWidth <= 0 || nativeHeight <= 0) return;

        float scale = Math.max(0.0001f, context.contentScale);
        int deviceWidth = isMac()
            ? Math.max(1, Math.round(nativeWidth * scale))
            : nativeWidth;
        int deviceHeight = isMac()
            ? Math.max(1, Math.round(nativeHeight * scale))
            : nativeHeight;
        int logicalWidth = Math.max(1, Math.round(deviceWidth / scale));
        int logicalHeight = Math.max(1, Math.round(deviceHeight / scale));

        context.width = deviceWidth;
        context.height = deviceHeight;
        if (context.composeWindow.getWidth() != logicalWidth ||
            context.composeWindow.getHeight() != logicalHeight) {
            context.composeWindow.setSize(logicalWidth, logicalHeight);
        }

        if (context == currentContext) {
            frameWidth = deviceWidth;
            frameHeight = deviceHeight;
        }
    }

    private static void parkComposeWindow(WindowContext context) {
        if (context == null ||
            context.composeWindow == null ||
            context.parked) return;

        Component focus = context.composeWindow.getFocusOwner();
        if (focus != null) context.focusOwner = focus;
        context.composeWindow.setLocation(-20000, -20000);
        context.parked = true;
    }

    private static int awtModifiers(int nativeModifiers) {
        int modifiers = 0;
        if ((nativeModifiers & 1) != 0) modifiers |= InputEvent.SHIFT_DOWN_MASK;
        if ((nativeModifiers & 2) != 0) modifiers |= InputEvent.CTRL_DOWN_MASK;
        if ((nativeModifiers & 4) != 0) modifiers |= InputEvent.ALT_DOWN_MASK;
        if ((nativeModifiers & 8) != 0) modifiers |= InputEvent.META_DOWN_MASK;
        return modifiers;
    }

    private static Component keyboardTarget(WindowContext context) {
        if (context == null || context.composeWindow == null) return null;
        Component focus = context.composeWindow.getFocusOwner();
        if (focus != null) context.focusOwner = focus;
        if (context.focusOwner != null) return context.focusOwner;

        Component[] children = context.composeWindow.getComponents();
        return children.length == 0
            ? context.composeWindow
            : children[0];
    }

    private static void requestComposeClose(WindowContext context) {
        Window target = context == null ? null : context.composeWindow;
        if (target == null) return;
        EventQueue.invokeLater(() ->
            target.dispatchEvent(
                new WindowEvent(target, WindowEvent.WINDOW_CLOSING)
            )
        );
    }

    private static Component resolveRenderHost(Object layer) {
        if (layer == null) return null;

        /*
         * SkiaLayer is itself a Swing component, but its actual Direct3D/
         * OpenGL surface is the heavyweight HardwareLayer Canvas exposed by
         * getCanvas()/getComponent(). Embedding next to that Canvas at the
         * JFrame level lets Skiko's peer cover us with its (now suppressed)
         * black surface. Embed inside the HardwareLayer instead.
         */
        for (String getter : new String[]{"getCanvas", "getComponent"}) {
            try {
                Method method = layer.getClass().getMethod(getter);
                method.setAccessible(true);
                Object value = method.invoke(layer);
                if (value instanceof Component component) {
                    return component;
                }
            } catch (Throwable ignored) {
            }
        }

        return layer instanceof Component component ? component : null;
    }

    private static float contentScale(Object layer) {
        if (layer == null) return 1f;
        try {
            Object value = invoke(layer, "getContentScale");
            float scale = number(value, 1f);
            return Float.isFinite(scale) && scale > 0f ? scale : 1f;
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase()
            .contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "")
            .toLowerCase()
            .contains("mac");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "")
            .toLowerCase()
            .contains("linux");
    }

    private static int inputLogicalCoordinate(
        WindowContext context,
        int nativeCoordinate
    ) {
        if (context == null || isMac()) return nativeCoordinate;
        float scale = Math.max(0.0001f, context.contentScale);
        return Math.round(nativeCoordinate / scale);
    }

    private static float nativeGeometryScale(WindowContext context) {
        if (!isMac() || context == null) return 1f;
        return 1f / Math.max(0.0001f, context.contentScale);
    }

    private static int nativeWindowWidth(
        WindowContext context,
        int deviceWidth
    ) {
        if (!isMac() || context == null) return deviceWidth;
        return Math.max(
            1,
            Math.round(
                deviceWidth /
                Math.max(0.0001f, context.contentScale)
            )
        );
    }

    private static int nativeWindowHeight(
        WindowContext context,
        int deviceHeight
    ) {
        if (!isMac() || context == null) return deviceHeight;
        return Math.max(
            1,
            Math.round(
                deviceHeight /
                Math.max(0.0001f, context.contentScale)
            )
        );
    }

    private static Window resolveComposeWindow(Object layer) {
        if (layer instanceof Component component) {
            Window owner = SwingUtilities.getWindowAncestor(component);
            if (owner != null) return owner;
        }

        for (Window window : Window.getWindows()) {
            if (window.isVisible() && window.getX() > -10000) return window;
        }
        for (Window window : Window.getWindows()) {
            if (window.isVisible()) return window;
        }
        return null;
    }

    private static void emit(float x, float y, float width, float height, int argb) {
        if (state == null || width <= 0f || height <= 0f) return;
        if (commands.size() >= MAX_COMMANDS) {
            if (strict) Runtime.getRuntime().halt(90);
            return;
        }

        if (Math.abs(state.b) > 0.0001f || Math.abs(state.c) > 0.0001f) {
            fillPolygon(
                List.of(
                    new P(x, y),
                    new P(x + width, y),
                    new P(x + width, y + height),
                    new P(x, y + height)
                ),
                argb
            );
            return;
        }

        P p0 = transform(x, y);
        P p1 = transform(x + width, y + height);
        float left = Math.min(p0.x, p1.x);
        float top = Math.min(p0.y, p1.y);
        float right = Math.max(p0.x, p1.x);
        float bottom = Math.max(p0.y, p1.y);

        emitDevice(
            left,
            top,
            right - left,
            bottom - top,
            argb
        );
        requestPresent();
    }

    private static void ensureFrameState() {
        if (state != null) return;

        WindowContext context = currentContext;
        if (context == null) {
            Window window = resolveComposeWindow(null);
            if (window != null) {
                context = CONTEXTS.computeIfAbsent(
                    window,
                    WindowContext::new
                );
                currentContext = context;
            }
        }
        if (context == null || context.composeWindow == null) return;

        int width = Math.max(
            1,
            context.width > 0
                ? context.width
                : context.composeWindow.getWidth()
        );
        int height = Math.max(
            1,
            context.height > 0
                ? context.height
                : context.composeWindow.getHeight()
        );

        context.width = width;
        context.height = height;
        frameWidth = width;
        frameHeight = height;
        state = new State(width, height);
    }

    private static P transform(float x, float y) {
        return new P(
            state.a * x + state.c * y + state.tx,
            state.b * x + state.d * y + state.ty
        );
    }

    private static void intersectClip(float x, float y, float w, float h) {
        float left = Math.max(state.clipX, x);
        float top = Math.max(state.clipY, y);
        float right = Math.min(state.clipX + state.clipW, x + w);
        float bottom = Math.min(state.clipY + state.clipH, y + h);
        state.clipX = left;
        state.clipY = top;
        state.clipW = Math.max(0f, right - left);
        state.clipH = Math.max(0f, bottom - top);
    }

    private static boolean maskBlurIncludesSource(
        MaskFilterCaptureStore.Blur blur
    ) {
        if (blur == null || blur.mode() == null) return true;
        return !blur.mode().equalsIgnoreCase("OUTER");
    }

    private static void emitMaskBlur(
        float x,
        float y,
        float width,
        float height,
        MaskFilterCaptureStore.Blur blur,
        int argb
    ) {
        if (blur == null || width <= 0f || height <= 0f) return;

        float sigma = Math.max(0.5f, blur.sigma());
        float radius = Math.max(1f, sigma * 3f);
        int layers = Math.max(
            3,
            Math.min(18, (int)Math.ceil(radius))
        );

        int sourceAlpha = (argb >>> 24) & 0xff;
        int rgb = argb & 0x00ffffff;
        String mode = blur.mode() == null ? "NORMAL" : blur.mode();

        if (mode.equalsIgnoreCase("INNER")) {
            for (int layer = 0; layer < layers; layer++) {
                float t = layer / (float)layers;
                float inset = radius * t;
                float w = width - inset * 2f;
                float h = height - inset * 2f;
                if (w <= 0f || h <= 0f) break;
                float weight = (1f - t) / layers * 1.8f;
                int alpha = Math.max(
                    1,
                    Math.min(255, Math.round(sourceAlpha * weight))
                );
                emit(
                    x + inset,
                    y + inset,
                    w,
                    h,
                    (alpha << 24) | rgb
                );
            }
            return;
        }

        for (int layer = layers; layer >= 1; layer--) {
            float t = layer / (float)layers;
            float expansion = radius * t;
            float weight = (1f - t * 0.80f) / layers * 2.5f;
            int alpha = Math.max(
                1,
                Math.min(255, Math.round(sourceAlpha * weight))
            );
            emit(
                x - expansion,
                y - expansion,
                width + expansion * 2f,
                height + expansion * 2f,
                (alpha << 24) | rgb
            );
        }
    }

    private static void emitDropShadow(
        float x,
        float y,
        float width,
        float height,
        ImageFilterCaptureStore.DropShadow shadow
    ) {
        if (shadow == null || width <= 0f || height <= 0f) return;

        float sigma = Math.max(
            0.5f,
            Math.max(shadow.sigmaX(), shadow.sigmaY())
        );
        float radius = Math.max(1f, sigma * 3f);
        int layers = Math.max(
            3,
            Math.min(18, (int)Math.ceil(radius))
        );

        int source = shadow.color();
        int sourceAlpha = (source >>> 24) & 0xff;
        int rgb = source & 0x00ffffff;
        float cx = x + shadow.dx();
        float cy = y + shadow.dy();

        for (int layer = layers; layer >= 1; layer--) {
            float t = layer / (float)layers;
            float expansion = radius * t;
            float weight = (1f - t * 0.78f) / layers * 2.6f;
            int alpha = Math.max(
                1,
                Math.min(255, Math.round(sourceAlpha * weight))
            );
            emit(
                cx - expansion,
                cy - expansion,
                width + expansion * 2f,
                height + expansion * 2f,
                (alpha << 24) | rgb
            );
        }
    }

    private static void emitShaderRect(
        float x,
        float y,
        float width,
        float height,
        ShaderCaptureStore.ShaderInfo shader,
        float opacity
    ) {
        if (shader == null || width <= 0f || height <= 0f) return;
        int columns = Math.max(1, Math.min(64, (int)Math.ceil(width / 8f)));
        int rows = Math.max(1, Math.min(64, (int)Math.ceil(height / 8f)));
        for (int row = 0; row < rows && commands.size() < MAX_COMMANDS; row++) {
            float y0 = y + row * height / rows;
            float y1 = y + (row + 1) * height / rows;
            for (int column = 0; column < columns && commands.size() < MAX_COMMANDS; column++) {
                float x0 = x + column * width / columns;
                float x1 = x + (column + 1) * width / columns;
                int color = applyOpacity(
                    shader.sample(
                        (x0 + x1) * 0.5f,
                        (y0 + y1) * 0.5f
                    ),
                    opacity
                );
                emit(x0, y0, x1 - x0, y1 - y0, color);
            }
        }
    }

    private static void emitShaderSpan(
        float x,
        float y,
        float width,
        float height,
        ShaderCaptureStore.ShaderInfo shader,
        float opacity
    ) {
        if (shader == null || width <= 0f || height <= 0f) return;
        int columns = Math.max(1, Math.min(32, (int)Math.ceil(width / 8f)));
        for (int column = 0; column < columns && commands.size() < MAX_COMMANDS; column++) {
            float x0 = x + column * width / columns;
            float x1 = x + (column + 1) * width / columns;
            int color = applyOpacity(
                shader.sample(
                    (x0 + x1) * 0.5f,
                    y + height * 0.5f
                ),
                opacity
            );
            emit(x0, y, x1 - x0, height, color);
        }
    }

    private static void fillPolygon(
        List<P> local,
        ShaderCaptureStore.ShaderInfo shader,
        float opacity
    ) {
        if (local.size() < 3 || shader == null || state == null) return;
        List<P> points = new ArrayList<>(local.size());
        for (P point : local) points.add(transform(point.x, point.y));

        int minY = (int)Math.floor(points.stream().mapToDouble(P::y).min().orElse(0));
        int maxY = (int)Math.ceil(points.stream().mapToDouble(P::y).max().orElse(0));
        int totalRows = Math.max(1, maxY - minY);
        int rowStep = Math.max(1, (int)Math.ceil(totalRows / 96.0));

        for (int row = minY; row < maxY && commands.size() < MAX_COMMANDS; row += rowStep) {
            float sampleY = row + rowStep * 0.5f;
            List<Float> xs = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                P a = points.get(i);
                P b = points.get((i + 1) % points.size());
                if (a.y == b.y) continue;
                float low = Math.min(a.y, b.y);
                float high = Math.max(a.y, b.y);
                if (sampleY < low || sampleY >= high) continue;
                float t = (sampleY - a.y) / (b.y - a.y);
                xs.add(a.x + (b.x - a.x) * t);
            }
            xs.sort(Float::compare);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                float left = xs.get(i);
                float right = xs.get(i + 1);
                int columns = Math.max(1, Math.min(32, (int)Math.ceil((right - left) / 8f)));
                for (int column = 0; column < columns && commands.size() < MAX_COMMANDS; column++) {
                    float x0 = left + column * (right - left) / columns;
                    float x1 = left + (column + 1) * (right - left) / columns;
                    // Shader coordinates are sampled approximately in device space here.
                    int color = applyOpacity(
                        shader.sample(
                            (x0 + x1) * 0.5f,
                            sampleY
                        ),
                        opacity
                    );
                    emitDevice(
                        x0,
                        row,
                        x1 - x0,
                        Math.min(rowStep, maxY - row),
                        color
                    );
                }
            }
        }
        requestPresent();
    }

    private static void fillPolygonClear(List<P> local) {
        if (local.size() < 3 || state == null) return;
        List<P> points = new ArrayList<>(local.size());
        for (P point : local) points.add(transform(point.x, point.y));

        int minY = (int)Math.floor(points.stream().mapToDouble(P::y).min().orElse(0));
        int maxY = (int)Math.ceil(points.stream().mapToDouble(P::y).max().orElse(0));
        for (int row = minY; row < maxY && commands.size() < MAX_COMMANDS; row++) {
            float sampleY = row + 0.5f;
            List<Float> xs = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                P a = points.get(i);
                P b = points.get((i + 1) % points.size());
                if (a.y == b.y) continue;
                float low = Math.min(a.y, b.y);
                float high = Math.max(a.y, b.y);
                if (sampleY < low || sampleY >= high) continue;
                float t = (sampleY - a.y) / (b.y - a.y);
                xs.add(a.x + (b.x - a.x) * t);
            }
            xs.sort(Float::compare);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                emitDeviceKind(xs.get(i), row, xs.get(i + 1) - xs.get(i), 1f, 3, 0);
            }
        }
        requestPresent();
    }

    private static void fillPolygon(List<P> local, int argb) {
        if (local.size() < 3 || state == null) return;
        List<P> points = new ArrayList<>(local.size());
        for (P point : local) {
            points.add(transform(point.x, point.y));
        }
        int minY = (int)Math.floor(points.stream().mapToDouble(P::y).min().orElse(0));
        int maxY = (int)Math.ceil(points.stream().mapToDouble(P::y).max().orElse(0));
        for (int row = minY; row < maxY && commands.size() < MAX_COMMANDS; row++) {
            float sampleY = row + 0.5f;
            List<Float> xs = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                P a = points.get(i);
                P b = points.get((i + 1) % points.size());
                if (a.y == b.y) continue;
                float low = Math.min(a.y, b.y);
                float high = Math.max(a.y, b.y);
                if (sampleY < low || sampleY >= high) continue;
                float t = (sampleY - a.y) / (b.y - a.y);
                xs.add(a.x + (b.x - a.x) * t);
            }
            xs.sort(Float::compare);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                float left = xs.get(i);
                float right = xs.get(i + 1);
                emitDevice(left, row, right - left, 1f, argb);
            }
        }
        requestPresent();
    }

    private static void emitDevice(float x, float y, float w, float h, int argb) {
        emitDeviceKind(x, y, w, h, 2, argb);
    }

    private static void emitDeviceKind(
        float x, float y, float w, float h, int kind, int argb
    ) {
        if (state == null || w <= 0f || h <= 0f || commands.size() >= MAX_COMMANDS) return;
        if (kind == 2 && state.opacity < 0.9999f) argb = applyOpacity(argb, state.opacity);

        float left = Math.max(x, state.clipX);
        float top = Math.max(y, state.clipY);
        float right = Math.min(x + w, state.clipX + state.clipW);
        float bottom = Math.min(y + h, state.clipY + state.clipH);
        if (right <= left || bottom <= top) return;

        if (!state.hasExclude) {
            addClippedDeviceRectKind(left, top, right, bottom, kind, argb);
            return;
        }

        float exLeft = state.excludeX;
        float exTop = state.excludeY;
        float exRight = exLeft + state.excludeW;
        float exBottom = exTop + state.excludeH;
        float ix0 = Math.max(left, exLeft);
        float iy0 = Math.max(top, exTop);
        float ix1 = Math.min(right, exRight);
        float iy1 = Math.min(bottom, exBottom);

        if (ix1 <= ix0 || iy1 <= iy0) {
            addClippedDeviceRectKind(left, top, right, bottom, kind, argb);
            return;
        }

        addClippedDeviceRectKind(left, top, right, iy0, kind, argb);
        addClippedDeviceRectKind(left, iy1, right, bottom, kind, argb);
        addClippedDeviceRectKind(left, iy0, ix0, iy1, kind, argb);
        addClippedDeviceRectKind(ix1, iy0, right, iy1, kind, argb);
    }

    private static void addClippedDeviceRect(
        float left, float top, float right, float bottom, int argb
    ) {
        addClippedDeviceRectKind(left, top, right, bottom, 2, argb);
    }

    private static void addClippedDeviceRectKind(
        float left,
        float top,
        float right,
        float bottom,
        int kind,
        int argb
    ) {
        if (!state.hasRoundClip) {
            addPathClippedDeviceRectKind(left, top, right, bottom, kind, argb);
            return;
        }

        float clipTop = state.roundClipY;
        float clipBottom = clipTop + state.roundClipH;
        float y0 = Math.max(top, clipTop);
        float y1 = Math.min(bottom, clipBottom);
        if (y1 <= y0) return;

        int rows = Math.max(
            1,
            Math.min(64, (int)Math.ceil(y1 - y0))
        );
        for (int row = 0; row < rows; row++) {
            float rowTop = y0 + row * (y1 - y0) / rows;
            float rowBottom = y0 + (row + 1) * (y1 - y0) / rows;
            float midY = (rowTop + rowBottom) * 0.5f;

            float inset = 0f;
            float topCenter = state.roundClipY + state.roundClipRy;
            float bottomCenter =
                state.roundClipY + state.roundClipH - state.roundClipRy;

            if (midY < topCenter) {
                float dy = (midY - topCenter) / state.roundClipRy;
                inset = state.roundClipRx -
                    state.roundClipRx *
                    (float)Math.sqrt(Math.max(0f, 1f - dy * dy));
            } else if (midY > bottomCenter) {
                float dy = (midY - bottomCenter) / state.roundClipRy;
                inset = state.roundClipRx -
                    state.roundClipRx *
                    (float)Math.sqrt(Math.max(0f, 1f - dy * dy));
            }

            float allowedLeft = state.roundClipX + inset;
            float allowedRight =
                state.roundClipX + state.roundClipW - inset;
            addPathClippedDeviceRectKind(
                Math.max(left, allowedLeft),
                rowTop,
                Math.min(right, allowedRight),
                rowBottom,
                kind,
                argb
            );
        }
    }

    private static void addPathClippedDeviceRect(
        float left, float top, float right, float bottom, int argb
    ) {
        addPathClippedDeviceRectKind(left, top, right, bottom, 2, argb);
    }

    private static void addPathClippedDeviceRectKind(
        float left,
        float top,
        float right,
        float bottom,
        int kind,
        int argb
    ) {
        if (state.pathClip == null || state.pathClip.isEmpty()) {
            addDeviceRectKind(left, top, right, bottom, kind, argb);
            return;
        }
        if (right <= left || bottom <= top) return;

        int rows = Math.max(
            1,
            Math.min(96, (int)Math.ceil(bottom - top))
        );
        for (int row = 0; row < rows && commands.size() < MAX_COMMANDS; row++) {
            float rowTop = top + row * (bottom - top) / rows;
            float rowBottom = top + (row + 1) * (bottom - top) / rows;
            float sampleY = (rowTop + rowBottom) * 0.5f;
            List<Float> xs = new ArrayList<>();

            for (List<P> contour : state.pathClip) {
                for (int i = 0; i < contour.size(); i++) {
                    P a = contour.get(i);
                    P b = contour.get((i + 1) % contour.size());
                    if (a.y == b.y) continue;
                    float low = Math.min(a.y, b.y);
                    float high = Math.max(a.y, b.y);
                    if (sampleY < low || sampleY >= high) continue;
                    float t = (sampleY - a.y) / (b.y - a.y);
                    xs.add(a.x + (b.x - a.x) * t);
                }
            }

            xs.sort(Float::compare);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                float x0 = Math.max(left, xs.get(i));
                float x1 = Math.min(right, xs.get(i + 1));
                addDeviceRectKind(x0, rowTop, x1, rowBottom, kind, argb);
            }
        }
    }

    private static void addDeviceRect(
        float left, float top, float right, float bottom, int argb
    ) {
        addDeviceRectKind(left, top, right, bottom, 2, argb);
    }

    private static void addDeviceRectKind(
        float left,
        float top,
        float right,
        float bottom,
        int kind,
        int argb
    ) {
        if (right <= left || bottom <= top || commands.size() >= MAX_COMMANDS) return;
        commands.add(
            new Command(
                kind,
                left,
                top,
                right - left,
                bottom - top,
                argb
            )
        );
    }

    private static List<List<P>> readPath(Object path) throws Exception {
        List<List<P>> contours = new ArrayList<>();
        List<P> current = null;
        P cursor = new P(0f, 0f);
        P start = cursor;
        for (Object segment : (Iterable<?>)path) {
            if (segment == null) continue;
            String verb = String.valueOf(invoke(segment, "getVerb"));
            if (verb.equals("DONE")) break;
            if (verb.equals("MOVE")) {
                current = new ArrayList<>();
                contours.add(current);
                cursor = point(invoke(segment, "getP0"));
                start = cursor;
                current.add(cursor);
            } else if (verb.equals("LINE")) {
                if (current == null) {
                    current = new ArrayList<>();
                    contours.add(current);
                    current.add(cursor);
                }
                cursor = point(invoke(segment, "getP1"));
                current.add(cursor);
            } else if (verb.equals("QUAD") || verb.equals("CONIC")) {
                if (current == null) {
                    current = new ArrayList<>();
                    contours.add(current);
                    current.add(cursor);
                }
                P from = point(invoke(segment, "getP0"));
                P control = point(invoke(segment, "getP1"));
                P end = point(invoke(segment, "getP2"));
                for (int i = 1; i <= 16; i++) {
                    float t = i / 16f;
                    float mt = 1f - t;
                    current.add(new P(
                        mt * mt * from.x + 2f * mt * t * control.x + t * t * end.x,
                        mt * mt * from.y + 2f * mt * t * control.y + t * t * end.y
                    ));
                }
                cursor = end;
            } else if (verb.equals("CUBIC")) {
                if (current == null) {
                    current = new ArrayList<>();
                    contours.add(current);
                    current.add(cursor);
                }
                P from = point(invoke(segment, "getP0"));
                P c1 = point(invoke(segment, "getP1"));
                P c2 = point(invoke(segment, "getP2"));
                P end = point(invoke(segment, "getP3"));
                for (int i = 1; i <= 16; i++) {
                    float t = i / 16f;
                    float mt = 1f - t;
                    current.add(new P(
                        mt * mt * mt * from.x + 3f * mt * mt * t * c1.x +
                            3f * mt * t * t * c2.x + t * t * t * end.x,
                        mt * mt * mt * from.y + 3f * mt * mt * t * c1.y +
                            3f * mt * t * t * c2.y + t * t * t * end.y
                    ));
                }
                cursor = end;
            } else if (verb.equals("CLOSE") && current != null && !current.isEmpty()) {
                if (!current.get(current.size() - 1).equals(start)) current.add(start);
                cursor = start;
            }
        }
        return contours;
    }

    private static P point(Object value) throws Exception {
        if (value == null) return new P(0f, 0f);
        float x = number(invoke(value, "getX"), 0f);
        float y = number(invoke(value, "getY"), 0f);
        return new P(x, y);
    }

    private static Object bitmapFromImage(Object image) {
        if (image == null) return null;
        try {
            ClassLoader loader = image.getClass().getClassLoader();
            Class<?> bitmapClass = Class.forName(
                "org.jetbrains.skia.Bitmap",
                true,
                loader
            );
            Class<?> companionClass = Class.forName(
                "org.jetbrains.skia.Bitmap$Companion",
                true,
                loader
            );
            Object companion = bitmapClass.getField("Companion").get(null);
            java.lang.reflect.Method make = companionClass.getMethod(
                "makeFromImage",
                image.getClass()
            );
            return make.invoke(companion, image);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void closeQuietly(Object value) {
        if (value == null) return;
        try {
            java.lang.reflect.Method close = value.getClass().getMethod("close");
            close.invoke(value);
        } catch (Throwable ignored) {
        }
    }

    private static float paintAlpha(Object paint) {
        if (paint == null) return 1f;
        int color = paintColor(paint);
        return ((color >>> 24) & 0xff) / 255f;
    }

    private static int paintColor(Object paint) {
        if (paint == null) return 0xffffffff;
        try {
            Object value = invoke(paint, "getColor");
            return value instanceof Number ? ((Number)value).intValue() : 0xffffffff;
        } catch (Throwable ignored) {
            return 0xffffffff;
        }
    }

    private static String paintStrokeCap(Object paint) {
        if (paint == null) return "BUTT";
        try {
            Object value = invoke(paint, "getStrokeCap");
            return value == null ? "BUTT" : value.toString();
        } catch (Throwable ignored) {
            return "BUTT";
        }
    }

    private static float paintStrokeWidth(Object paint) {
        if (paint == null) return 1f;
        try {
            return Math.max(1f, number(invoke(paint, "getStrokeWidth"), 1f));
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    private static String paintMode(Object paint) {
        if (paint == null) return "FILL";
        try {
            return String.valueOf(invoke(paint, "getMode")).toUpperCase();
        } catch (Throwable ignored) {
            return "FILL";
        }
    }

    private static float number(Object value, float fallback) {
        return value instanceof Number ? ((Number)value).floatValue() : fallback;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
        throws Exception {
        java.lang.reflect.Method method = target.getClass().getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object invoke(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }

    private static int applyOpacity(int argb, float opacity) {
        int alpha = (argb >>> 24) & 0xff;
        int scaled = Math.max(
            0,
            Math.min(255, Math.round(alpha * opacity))
        );
        return (scaled << 24) | (argb & 0x00ffffff);
    }

    private static void writeColor(float[] target, int index, int argb) {
        target[index * 4] = ((argb >>> 16) & 0xff) / 255f;
        target[index * 4 + 1] = ((argb >>> 8) & 0xff) / 255f;
        target[index * 4 + 2] = (argb & 0xff) / 255f;
        target[index * 4 + 3] = ((argb >>> 24) & 0xff) / 255f;
    }

    private static String[] glyph(char c) {
        return switch (c) {
            case 'A' -> new String[]{"010","101","111","101","101"};
            case 'B' -> new String[]{"110","101","110","101","110"};
            case 'C' -> new String[]{"011","100","100","100","011"};
            case 'D' -> new String[]{"110","101","101","101","110"};
            case 'E' -> new String[]{"111","100","110","100","111"};
            case 'F' -> new String[]{"111","100","110","100","100"};
            case 'G' -> new String[]{"011","100","101","101","011"};
            case 'H' -> new String[]{"101","101","111","101","101"};
            case 'I' -> new String[]{"111","010","010","010","111"};
            case 'J' -> new String[]{"001","001","001","101","010"};
            case 'K' -> new String[]{"101","101","110","101","101"};
            case 'L' -> new String[]{"100","100","100","100","111"};
            case 'M' -> new String[]{"101","111","111","101","101"};
            case 'N' -> new String[]{"101","111","111","111","101"};
            case 'O' -> new String[]{"010","101","101","101","010"};
            case 'P' -> new String[]{"110","101","110","100","100"};
            case 'Q' -> new String[]{"010","101","101","111","011"};
            case 'R' -> new String[]{"110","101","110","101","101"};
            case 'S' -> new String[]{"011","100","010","001","110"};
            case 'T' -> new String[]{"111","010","010","010","010"};
            case 'U' -> new String[]{"101","101","101","101","111"};
            case 'V' -> new String[]{"101","101","101","101","010"};
            case 'W' -> new String[]{"101","101","111","111","101"};
            case 'X' -> new String[]{"101","101","010","101","101"};
            case 'Y' -> new String[]{"101","101","010","010","010"};
            case 'Z' -> new String[]{"111","001","010","100","111"};
            case '0' -> new String[]{"111","101","101","101","111"};
            case '1' -> new String[]{"010","110","010","010","111"};
            case '2' -> new String[]{"110","001","010","100","111"};
            case '3' -> new String[]{"110","001","010","001","110"};
            case '4' -> new String[]{"101","101","111","001","001"};
            case '5' -> new String[]{"111","100","110","001","110"};
            case '6' -> new String[]{"011","100","110","101","010"};
            case '7' -> new String[]{"111","001","010","010","010"};
            case '8' -> new String[]{"010","101","010","101","010"};
            case '9' -> new String[]{"010","101","011","001","110"};
            case ' ' -> new String[]{"000","000","000","000","000"};
            case '.', ':' -> new String[]{"000","010","000","010","000"};
            case '-', '_' -> new String[]{"000","000","111","000","000"};
            default -> null;
        };
    }
}
