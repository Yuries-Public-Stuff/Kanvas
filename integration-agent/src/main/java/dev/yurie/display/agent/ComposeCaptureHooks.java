package dev.yurie.display.agent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Captures Skia Canvas calls without a Skiko compile dependency.
public final class ComposeCaptureHooks {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong FRAME = new AtomicLong();
    private static final ConcurrentHashMap<Class<?>, PaintAccess> PAINT_ACCESS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, FontAccess> FONT_ACCESS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> FRAME_CAPTURE_ACTIVE =
        ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Object> FRAME_CANVAS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CANVAS_CALL_ALLOWED =
        new ThreadLocal<>();
    private static final ThreadLocal<Boolean> MIRROR_ORIGINAL_CANVAS =
        ThreadLocal.withInitial(() -> false);

    private static volatile String capturePath = "";
    private static volatile boolean enabled;
    private static volatile boolean strictTakeover;
    private static volatile boolean requestedTakeover;
    private static volatile boolean requestedStrict;
    private static volatile boolean canvasTransformerReady;
    private static volatile boolean frameTransformerReady;
    private static volatile boolean modernSkikoDrawBoundary;

    private ComposeCaptureHooks() {}

    static void configure(String path, boolean on, boolean takeover, boolean strict) {
        capturePath = path == null ? "" : path;
        enabled = on && !capturePath.isBlank();
        strictTakeover = takeover && strict;
        requestedTakeover = takeover;
        requestedStrict = strict;
        canvasTransformerReady = false;
        frameTransformerReady = false;
        LiveGpuTakeover.configure(false, strict);
        if (enabled) {
            write("CAPTURE_START " + Instant.now());
        }
    }

    static synchronized void markCanvasTransformerReady() {
        canvasTransformerReady = true;
        armTakeoverIfReady();
    }

    static synchronized void markFrameTransformerReady() {
        frameTransformerReady = true;
        armTakeoverIfReady();
    }

    static synchronized void markModernSkikoDrawBoundary() {
        modernSkikoDrawBoundary = true;
    }

    public static boolean suppressSkikoPresentationPass() {
        return LiveGpuTakeover.enabled() && !modernSkikoDrawBoundary;
    }

    static synchronized void markFrameTransformerFailed(String detail) {
        frameTransformerReady = false;
        KanvasAgent.audit("FRAME_TRANSFORMER_UNAVAILABLE " + detail);
        if (requestedTakeover && requestedStrict) {
            System.err.println(
                "[Kanvas] strict takeover cannot establish frame boundaries: " +
                detail
            );
            Runtime.getRuntime().halt(88);
        }
    }

    public static void frameStartLayerRecording(
        Object layer,
        long nanoTime,
        Object forcedSize
    ) {
        MIRROR_ORIGINAL_CANVAS.set(true);
        if (layer == null) return;

        if (forcedSize != null) {
            try {
                Method getWidth = method(forcedSize.getClass(), "getWidth");
                Method getHeight = method(forcedSize.getClass(), "getHeight");
                if (getWidth != null && getHeight != null) {
                    int width = Math.max(
                        0,
                        (int)Math.round(
                            ((Number)getWidth.invoke(forcedSize)).doubleValue()
                        )
                    );
                    int height = Math.max(
                        0,
                        (int)Math.round(
                            ((Number)getHeight.invoke(forcedSize)).doubleValue()
                        )
                    );
                    frameStart(layer, width, height, nanoTime);
                    return;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to layer-based sizing.
            }
        }

        frameStartLayer(layer, nanoTime);
    }

    private static void armTakeoverIfReady() {
        if (requestedTakeover && canvasTransformerReady && frameTransformerReady) {
            LiveGpuTakeover.configure(true, requestedStrict);
            KanvasAgent.audit("TAKEOVER_ARMED");
            System.err.println("[Kanvas] takeover armed");
        }
    }

    public static boolean takeoverEnabled() {
        return LiveGpuTakeover.enabled();
    }

    public static boolean suppressOriginalCanvasCalls() {
        return LiveGpuTakeover.enabled() && !MIRROR_ORIGINAL_CANVAS.get();
    }

    public static void frameStartLayer(Object layer, long nanoTime) {
        MIRROR_ORIGINAL_CANVAS.set(false);
        if (layer == null) return;
        try {
            Method getWidth = method(layer.getClass(), "getWidth");
            Method getHeight = method(layer.getClass(), "getHeight");
            Method getContentScale = method(layer.getClass(), "getContentScale");
            if (getWidth == null || getHeight == null) return;

            int width = ((Number)getWidth.invoke(layer)).intValue();
            int height = ((Number)getHeight.invoke(layer)).intValue();
            float scale = 1f;
            if (getContentScale != null) {
                Object raw = getContentScale.invoke(layer);
                if (raw instanceof Number number) {
                    scale = number.floatValue();
                }
            }
            if (!Float.isFinite(scale) || scale <= 0f) scale = 1f;

            frameStart(
                layer,
                Math.max(0, Math.round(width * scale)),
                Math.max(0, Math.round(height * scale)),
                nanoTime
            );
        } catch (ReflectiveOperationException failure) {
            KanvasAgent.audit(
                "FRAME_LAYER_INSPECTION_FAILED " +
                failure.getClass().getName() + ": " +
                String.valueOf(failure.getMessage())
            );
        }
    }

    public static void frameStart(
        Object layer,
        int width,
        int height,
        long nanoTime
    ) {
        FRAME_CAPTURE_ACTIVE.set(true);
        FRAME_CANVAS.remove();
        CANVAS_CALL_ALLOWED.remove();
        LiveGpuTakeover.frameStart(layer, width, height);
        if (!enabled) return;
        long frame = FRAME.incrementAndGet();
        write(
            "FRAME_BEGIN " + frame +
            " width=" + width +
            " height=" + height +
            " time=" + nanoTime +
            " layer=" + describe(layer)
        );
    }

    public static void frameEnd() {
        try {
            // Reset routing before presenting the frame.
            CANVAS_CALL_ALLOWED.remove();
            LiveGpuTakeover.frameEnd();
            if (enabled) write("FRAME_END " + FRAME.get());
        } finally {
            CANVAS_CALL_ALLOWED.remove();
            FRAME_CANVAS.remove();
            FRAME_CAPTURE_ACTIVE.set(false);
            MIRROR_ORIGINAL_CANVAS.remove();
        }
    }

    // Legacy direct-draw mode captures one primary canvas. Modern Skiko
    // recording mode uses nested PictureRecorder canvases for real Compose
    // content, so every canvas participating while the frame is active must
    // be mirrored into Kanvas.
    public static boolean captureCanvas(Object canvas) {
        if (canvas == null ||
            !FRAME_CAPTURE_ACTIVE.get() ||
            !LiveGpuTakeover.isConfigured()) {
            CANVAS_CALL_ALLOWED.set(false);
            return false;
        }

        if (MIRROR_ORIGINAL_CANVAS.get()) {
            CANVAS_CALL_ALLOWED.set(true);
            return true;
        }

        Object key = canvasIdentity(canvas);
        Object primary = FRAME_CANVAS.get();
        boolean allowed;
        if (primary == null) {
            FRAME_CANVAS.set(key);
            allowed = true;
        } else {
            allowed = primary.equals(key);
        }
        CANVAS_CALL_ALLOWED.set(allowed);
        return allowed;
    }

    private static Object canvasIdentity(Object canvas) {
        /*
         * Skiko may create more than one JVM Canvas wrapper for the same
         * native SkCanvas during a frame (notably on macOS). Java object
         * identity then incorrectly classifies real frame draws as offscreen.
         * Prefer the stable native pointer when it is available, and fall
         * back to the wrapper object for versions where it is not exposed.
         */
        Class<?> type = canvas.getClass();
        while (type != null) {
            for (String name : new String[]{"_ptr", "ptr", "nativePtr"}) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    Object value = field.get(canvas);
                    if (value instanceof Number number) {
                        return Long.valueOf(number.longValue());
                    }
                } catch (ReflectiveOperationException |
                         RuntimeException ignored) {
                    // Try the next known pointer spelling/base class.
                }
            }
            type = type.getSuperclass();
        }

        for (String name : new String[]{"getPtr", "get_ptr"}) {
            try {
                Method getter = canvas.getClass().getMethod(name);
                Object value = getter.invoke(canvas);
                if (value instanceof Number number) {
                    return Long.valueOf(number.longValue());
                }
            } catch (ReflectiveOperationException |
                     RuntimeException ignored) {
                // Fall through to object identity.
            }
        }

        return canvas;
    }

    public static void markCanvasCall(Object canvas) {
        captureCanvas(canvas);
    }

    public static boolean canvasCallAllowed() {
        Boolean allowed = CANVAS_CALL_ALLOWED.get();
        return allowed == null || allowed;
    }

    public static boolean bypassPictureReplay() {
        return LiveGpuTakeover.enabled() && !FRAME_CAPTURE_ACTIVE.get();
    }

    public static void clear(int color) {
        LiveGpuTakeover.clear(color);
        if (!enabled) return;
        write("CLEAR " + SEQUENCE.incrementAndGet() + " color=" + unsigned(color));
    }

    public static void drawRect(
        float left, float top, float right, float bottom, Object paint
    ) {
        String blendMode = paintBlendMode(paint);
        validatePaint(paint, "drawRect");
        if ("CLEAR".equalsIgnoreCase(blendMode)) {
            LiveGpuTakeover.clearRect(left, top, right, bottom);
            if (enabled) {
                write(
                    "CLEAR_RECT " + SEQUENCE.incrementAndGet() +
                    " l=" + left + " t=" + top + " r=" + right + " b=" + bottom
                );
            }
            return;
        }

        PaintSnapshot p = paintSnapshot(paint);
        LiveGpuTakeover.rect(
            left,
            top,
            right,
            bottom,
            filteredColor(paint, p.color()),
            paint
        );
        if (!enabled) return;
        write(
            "RECT " + SEQUENCE.incrementAndGet() +
            " l=" + left + " t=" + top + " r=" + right + " b=" + bottom +
            p.asFields()
        );
    }

    public static void drawRRect(
        float left, float top, float right, float bottom, float[] radii, Object paint
    ) {
        validatePaint(paint, "drawRRect");
        PaintSnapshot p = paintSnapshot(paint);
        LiveGpuTakeover.roundRect(
            left,
            top,
            right,
            bottom,
            radii,
            filteredColor(paint, p.color()),
            paint
        );
        if (!enabled) return;
        write(
            "RRECT " + SEQUENCE.incrementAndGet() +
            " l=" + left + " t=" + top + " r=" + right + " b=" + bottom +
            " radii=" + array(radii) + p.asFields()
        );
    }

    public static void save() {
        LiveGpuTakeover.save();
        if (!enabled) return;
        write("SAVE " + SEQUENCE.incrementAndGet());
    }

    public static void restore() {
        LiveGpuTakeover.restore();
        if (!enabled) return;
        write("RESTORE " + SEQUENCE.incrementAndGet());
    }

    public static void translate(float dx, float dy) {
        LiveGpuTakeover.translate(dx, dy);
        if (!enabled) return;
        write("TRANSLATE " + SEQUENCE.incrementAndGet() + " dx=" + dx + " dy=" + dy);
    }

    public static void scale(float sx, float sy) {
        LiveGpuTakeover.scale(sx, sy);
        if (!enabled) return;
        write("SCALE " + SEQUENCE.incrementAndGet() + " sx=" + sx + " sy=" + sy);
    }

    public static void rotate(float degrees) {
        LiveGpuTakeover.rotate(degrees);
        if (enabled) {
            write("ROTATE " + SEQUENCE.incrementAndGet() + " degrees=" + degrees);
        }
    }

    public static void rotateAround(float degrees, float x, float y) {
        LiveGpuTakeover.rotate(degrees, x, y);
        if (enabled) {
            write(
                "ROTATE_AROUND " + SEQUENCE.incrementAndGet() +
                " degrees=" + degrees + " x=" + x + " y=" + y
            );
        }
    }

    public static void skew(float sx, float sy) {
        LiveGpuTakeover.skew(sx, sy);
        if (enabled) {
            write("SKEW " + SEQUENCE.incrementAndGet() + " sx=" + sx + " sy=" + sy);
        }
    }

    public static boolean concat(Object matrix) {
        boolean captured = LiveGpuTakeover.concat(matrix);
        if (enabled) {
            write("CONCAT " + SEQUENCE.incrementAndGet() + " captured=" + captured);
        }
        return captured;
    }

    public static void saveLayerRec(Object layerRec) {
        Object paint = null;
        Object backdrop = null;
        if (layerRec != null) {
            try {
                Method getPaint = method(layerRec.getClass(), "getPaint");
                if (getPaint != null) paint = getPaint.invoke(layerRec);
                Method getBackdrop = method(layerRec.getClass(), "getBackdrop");
                if (getBackdrop != null) backdrop = getBackdrop.invoke(layerRec);
            } catch (ReflectiveOperationException failure) {
                if (strictTakeover) unsupportedPaint("saveLayerRec", "Inspection");
            }
        }

        if (backdrop != null && strictTakeover) {
            unsupportedPaint("saveLayerRec", "BackdropFilter");
        }

        validatePaint(paint, "saveLayerRec");
        PaintSnapshot snapshot = paintSnapshot(paint);
        int color = parseColor(snapshot.color());
        float opacity = ((color >>> 24) & 0xff) / 255f;
        LiveGpuTakeover.saveLayer(opacity);
        if (enabled) {
            write(
                "SAVE_LAYER_REC " + SEQUENCE.incrementAndGet() +
                " opacity=" + opacity
            );
        }
    }

    public static void restoreToCount(int saveCount) {
        LiveGpuTakeover.restoreToCount(saveCount);
        if (enabled) {
            write(
                "RESTORE_TO_COUNT " + SEQUENCE.incrementAndGet() +
                " count=" + saveCount
            );
        }
    }

    public static void saveLayer(Object paint) {
        validatePaint(paint, "saveLayer");
        PaintSnapshot snapshot = paintSnapshot(paint);
        int color = parseColor(snapshot.color());
        float opacity = ((color >>> 24) & 0xff) / 255f;
        LiveGpuTakeover.saveLayer(opacity);
        if (enabled) {
            write(
                "SAVE_LAYER " + SEQUENCE.incrementAndGet() +
                " opacity=" + opacity
            );
        }
    }

    public static boolean clipPath(Object path, Object mode, boolean antiAlias) {
        boolean difference =
            mode != null && mode.toString().equalsIgnoreCase("DIFFERENCE");
        if (difference && strictTakeover) {
            generic("clipPath(DIFFERENCE)");
            return false;
        }
        boolean captured = difference
            ? false
            : LiveGpuTakeover.clipPath(path);
        if (enabled) {
            write(
                "CLIP_PATH " + SEQUENCE.incrementAndGet() +
                " mode=" + string(mode) + " aa=" + antiAlias +
                " captured=" + captured
            );
        }
        return captured;
    }

    public static boolean setMatrix(Object matrix) {
        boolean captured = LiveGpuTakeover.setMatrix(matrix);
        if (enabled) {
            write("SET_MATRIX " + SEQUENCE.incrementAndGet() + " captured=" + captured);
        }
        return captured;
    }

    public static void clipRRect(
        float left,
        float top,
        float right,
        float bottom,
        float[] radii,
        Object mode,
        boolean antiAlias
    ) {
        boolean difference =
            mode != null && mode.toString().equalsIgnoreCase("DIFFERENCE");
        LiveGpuTakeover.clipRoundRect(
            left,
            top,
            right,
            bottom,
            radii,
            difference
        );
        if (enabled) {
            write(
                "CLIP_RRECT " + SEQUENCE.incrementAndGet() +
                " l=" + left + " t=" + top + " r=" + right + " b=" + bottom +
                " mode=" + string(mode) + " aa=" + antiAlias
            );
        }
    }

    public static void resetMatrix() {
        LiveGpuTakeover.resetMatrix();
        if (enabled) {
            write("RESET_MATRIX " + SEQUENCE.incrementAndGet());
        }
    }

    public static void clipRect(
        float left, float top, float right, float bottom, Object mode, boolean antiAlias
    ) {
        boolean difference =
            mode != null && mode.toString().equalsIgnoreCase("DIFFERENCE");
        LiveGpuTakeover.clipRect(left, top, right, bottom, difference);
        if (!enabled) return;
        write(
            "CLIP_RECT " + SEQUENCE.incrementAndGet() +
            " l=" + left + " t=" + top + " r=" + right + " b=" + bottom +
            " mode=" + string(mode) + " aa=" + antiAlias
        );
    }

    public static boolean drawDRRect(
        Object outer,
        Object inner,
        Object paint
    ) {
        validatePaint(paint, "drawDRRect");
        boolean captured =
            LiveGpuTakeover.doubleRoundRect(outer, inner, paint);
        if (enabled) {
            write(
                "DRRECT " + SEQUENCE.incrementAndGet() +
                " captured=" + captured
            );
        }
        return captured;
    }

    public static void drawOval(
        float left, float top, float right, float bottom, Object paint
    ) {
        validatePaint(paint, "drawOval");
        PaintSnapshot p = paintSnapshot(paint);
        LiveGpuTakeover.oval(
            left,
            top,
            right,
            bottom,
            filteredColor(paint, p.color()),
            paint
        );
        if (!enabled) return;
        write(
            "OVAL " + SEQUENCE.incrementAndGet() +
            " l=" + left + " t=" + top + " r=" + right + " b=" + bottom +
            p.asFields()
        );
    }

    public static void drawCircle(float x, float y, float radius, Object paint) {
        validatePaint(paint, "drawCircle");
        PaintSnapshot p = paintSnapshot(paint);
        LiveGpuTakeover.circle(
            x,
            y,
            radius,
            filteredColor(paint, p.color()),
            paint
        );
        if (!enabled) return;
        write(
            "CIRCLE " + SEQUENCE.incrementAndGet() +
            " x=" + x + " y=" + y + " radius=" + radius +
            p.asFields()
        );
    }

    public static void drawPoint(float x, float y, Object paint) {
        validatePaint(paint, "drawPoint");
        PaintSnapshot p = paintSnapshot(paint);
        float width = parseFloat(p.strokeWidth(), 1f);
        LiveGpuTakeover.point(
            x,
            y,
            width,
            filteredColor(paint, p.color()),
            paint
        );
        if (enabled) {
            write(
                "POINT " + SEQUENCE.incrementAndGet() +
                " x=" + x + " y=" + y +
                " width=" + width + p.asFields()
            );
        }
    }

    public static void drawPoints(float[] coords, Object paint) {
        drawPointArray(coords, paint, 0, "POINTS");
    }

    public static void drawLines(float[] coords, Object paint) {
        drawPointArray(coords, paint, 1, "LINES");
    }

    public static void drawPolygon(float[] coords, Object paint) {
        drawPointArray(coords, paint, 2, "POLYGON");
    }

    private static void drawPointArray(
        float[] coords,
        Object paint,
        int mode,
        String name
    ) {
        validatePaint(paint, name);
        PaintSnapshot p = paintSnapshot(paint);
        float width = parseFloat(p.strokeWidth(), 1f);
        LiveGpuTakeover.points(
            coords,
            mode,
            width,
            filteredColor(paint, p.color()),
            paint
        );
        if (enabled) {
            write(
                name + " " + SEQUENCE.incrementAndGet() +
                " count=" + (coords == null ? 0 : coords.length / 2) +
                " width=" + width + p.asFields()
            );
        }
    }

    public static void drawPaint(Object paint) {
        validatePaint(paint, "drawPaint");
        PaintSnapshot p = paintSnapshot(paint);
        LiveGpuTakeover.paintCanvas(filteredColor(paint, p.color()), paint);
        if (enabled) {
            write("PAINT " + SEQUENCE.incrementAndGet() + p.asFields());
        }
    }

    public static void drawLine(
        float x1, float y1, float x2, float y2, Object paint
    ) {
        validatePaint(paint, "drawLine");
        PaintSnapshot p = paintSnapshot(paint);
        float width = parseFloat(p.strokeWidth(), 1f);
        LiveGpuTakeover.line(x1, y1, x2, y2, width, filteredColor(paint, p.color()));
        if (!enabled) return;
        write(
            "LINE " + SEQUENCE.incrementAndGet() +
            " x1=" + x1 + " y1=" + y1 + " x2=" + x2 + " y2=" + y2 +
            " width=" + width + p.asFields()
        );
    }

    public static boolean drawArc(
        float left,
        float top,
        float right,
        float bottom,
        float startAngle,
        float sweepAngle,
        boolean includeCenter,
        Object paint
    ) {
        if (!LiveGpuTakeover.enabled()) return false;
        validatePaint(paint, "drawArc");
        PaintSnapshot p = paintSnapshot(paint);
        LiveGpuTakeover.arc(
            left,
            top,
            right,
            bottom,
            startAngle,
            sweepAngle,
            includeCenter,
            filteredColor(paint, p.color()),
            paint
        );
        if (enabled) {
            write(
                "ARC " + SEQUENCE.incrementAndGet() +
                " l=" + left + " t=" + top + " r=" + right + " b=" + bottom +
                " start=" + startAngle + " sweep=" + sweepAngle +
                " center=" + includeCenter + p.asFields()
            );
        }
        return true;
    }

    public static boolean drawPath(Object path, Object paint) {
        validatePaint(paint, "drawPath");
        boolean captured = LiveGpuTakeover.path(path, paint);
        if (enabled) {
            write("PATH " + SEQUENCE.incrementAndGet() + " captured=" + captured);
        }
        return captured;
    }

    public static boolean drawRegion(
        Object region,
        Object paint
    ) {
        validatePaint(paint, "drawRegion");
        boolean captured = LiveGpuTakeover.drawRegion(region, paint);
        if (enabled) {
            write(
                "REGION " + SEQUENCE.incrementAndGet() +
                " captured=" + captured
            );
        }
        return captured;
    }

    public static boolean clipRegion(Object region, Object mode) {
        boolean difference =
            mode != null && mode.toString().equalsIgnoreCase("DIFFERENCE");
        if (difference && strictTakeover) {
            generic("clipRegion(DIFFERENCE)");
            return false;
        }
        boolean captured = difference
            ? false
            : LiveGpuTakeover.clipRegion(region);
        if (enabled) {
            write(
                "CLIP_REGION " + SEQUENCE.incrementAndGet() +
                " mode=" + string(mode) +
                " captured=" + captured
            );
        }
        return captured;
    }

    public static boolean drawImageNine(
        Object image,
        Object center,
        Object destination,
        Object filterMode,
        Object paint
    ) {
        validatePaint(paint, "drawImageNine");
        boolean captured = LiveGpuTakeover.imageNine(
            image,
            center,
            destination,
            paint
        );
        if (enabled) {
            write(
                "IMAGE_NINE " + SEQUENCE.incrementAndGet() +
                " captured=" + captured
            );
        }
        return captured;
    }

    public static boolean drawPicture(
        Object picture,
        Object matrix,
        Object paint
    ) {
        boolean captured =
            LiveGpuTakeover.picture(picture, matrix, paint);
        if (enabled) {
            write(
                "PICTURE " + SEQUENCE.incrementAndGet() +
                " captured=" + captured
            );
        }
        return captured;
    }

    public static boolean drawImageRect(
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
        validatePaint(paint, "drawImageRect");
        boolean captured = LiveGpuTakeover.imageRect(
            image,
            srcLeft,
            srcTop,
            srcRight,
            srcBottom,
            dstLeft,
            dstTop,
            dstRight,
            dstBottom,
            paint
        );
        if (enabled) {
            write(
                "IMAGE " + SEQUENCE.incrementAndGet() +
                " sl=" + srcLeft + " st=" + srcTop + " sr=" + srcRight + " sb=" + srcBottom +
                " dl=" + dstLeft + " dt=" + dstTop + " dr=" + dstRight + " db=" + dstBottom +
                " captured=" + captured
            );
        }
        return captured;
    }

    public static boolean drawParagraph(
        Object paragraph,
        Object canvas,
        float x,
        float y
    ) {
        if (!captureCanvas(canvas)) return false;
        boolean captured = LiveGpuTakeover.paragraph(paragraph, x, y);
        if (enabled) {
            write(
                "PARAGRAPH " + SEQUENCE.incrementAndGet() +
                " x=" + x + " y=" + y + " captured=" + captured
            );
        }
        return captured;
    }

    public static void drawString(String text, float x, float y, Object font, Object paint) {
        validatePaint(paint, "drawString");
        PaintSnapshot p = paintSnapshot(paint);
        FontSnapshot f = fontSnapshot(font);
        LiveGpuTakeover.string(text, x, y, f.size(), filteredColor(paint, p.color()));
        if (!enabled) return;
        write(
            "STRING " + SEQUENCE.incrementAndGet() +
            " x=" + x + " y=" + y +
            " text=" + quote(text) +
            " family=" + quote(f.family()) +
            " size=" + f.size() + p.asFields()
        );
    }

    public static void generic(String operation) {
        if (enabled) write("OP " + SEQUENCE.incrementAndGet() + " name=" + operation);
        if (strictTakeover && LiveGpuTakeover.enabled()) {
            System.err.println("[Kanvas] strict takeover unsupported operation: " + operation);
            Runtime.getRuntime().halt(87);
        }
    }

    static void finish() {
        LiveGpuTakeover.close();
        if (enabled) write("CAPTURE_END frames=" + FRAME.get() + " operations=" + SEQUENCE.get());
    }

    private static void validatePaint(Object paint, String operation) {
        if (!strictTakeover ||
            paint == null ||
            !LiveGpuTakeover.enabled()) return;
        try {
            if (ShaderCaptureStore.hasUnsupportedShader(paint)) {
                unsupportedPaint(operation, "Shader");
            }

            if (ImageFilterCaptureStore.hasUnsupportedImageFilter(paint)) {
                unsupportedPaint(operation, "ImageFilter");
            }
            if (MaskFilterCaptureStore.hasUnsupportedMaskFilter(paint)) {
                unsupportedPaint(operation, "MaskFilter");
            }
            if (ColorFilterCaptureStore.hasUnsupportedColorFilter(paint)) {
                unsupportedPaint(operation, "ColorFilter");
            }
            if (PathEffectCaptureStore.hasUnsupportedPathEffect(paint)) {
                unsupportedPaint(operation, "PathEffect");
            }
            if (ColorFilterCaptureStore.fromPaint(paint) != null) {
                Method shaderMethod = method(paint.getClass(), "getShader");
                if (shaderMethod != null && shaderMethod.invoke(paint) != null) {
                    unsupportedPaint(operation, "Shader+ColorFilter");
                }
            }

            Object mode = null;
            Method blendMode = method(paint.getClass(), "getBlendMode");
            if (blendMode != null) {
                mode = blendMode.invoke(paint);
                if (mode != null &&
                    !mode.toString().equalsIgnoreCase("SRC_OVER") &&
                    !(operation.equals("drawRect") &&
                      mode.toString().equalsIgnoreCase("CLEAR"))) {
                    unsupportedPaint(operation, "BlendMode=" + mode);
                }
            }

            Method blenderMethod = method(paint.getClass(), "getBlender");
            if (blenderMethod != null) {
                Object blender = blenderMethod.invoke(paint);
                boolean supportedMode =
                    mode != null &&
                    (mode.toString().equalsIgnoreCase("SRC_OVER") ||
                     (operation.equals("drawRect") &&
                      mode.toString().equalsIgnoreCase("CLEAR")));
                if (blender != null && !supportedMode) {
                    unsupportedPaint(operation, "Blender");
                }
            }
        } catch (ReflectiveOperationException failure) {
            unsupportedPaint(operation, "PaintInspection");
        }
    }

    private static String paintBlendMode(Object paint) {
        if (paint == null) return "SRC_OVER";
        try {
            Method blendMode = method(paint.getClass(), "getBlendMode");
            if (blendMode == null) return "SRC_OVER";
            Object mode = blendMode.invoke(paint);
            return mode == null ? "SRC_OVER" : mode.toString();
        } catch (ReflectiveOperationException ignored) {
            return "UNKNOWN";
        }
    }

    private static void unsupportedPaint(String operation, String feature) {
        /*
         * Unsupported paint only matters on the primary Compose frame canvas.
         * Offscreen Skia canvases are intentionally left to Skia so arbitrary
         * repos can use caches/effects/masks internally without poisoning or
         * terminating native takeover.
         */
        if (!LiveGpuTakeover.enabled()) return;
        System.err.println(
            "[Kanvas] strict takeover unsupported paint: " +
            operation + " " + feature
        );
        Runtime.getRuntime().halt(96);
    }

    private static Method method(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static PaintSnapshot paintSnapshot(Object paint) {
        if (paint == null) return PaintSnapshot.EMPTY;
        try {
            PaintAccess access = PAINT_ACCESS.computeIfAbsent(paint.getClass(), PaintAccess::new);
            return access.read(paint);
        } catch (Throwable ignored) {
            return new PaintSnapshot("unknown", "unknown", "unknown");
        }
    }

    private static FontSnapshot fontSnapshot(Object font) {
        if (font == null) return FontSnapshot.DEFAULT;
        try {
            FontAccess access = FONT_ACCESS.computeIfAbsent(font.getClass(), FontAccess::new);
            return access.read(font);
        } catch (Throwable ignored) {
            return FontSnapshot.DEFAULT;
        }
    }

    private static void write(String line) {
        String pathText = capturePath;
        if (pathText == null || pathText.isBlank()) return;
        try {
            Path path = Path.of(pathText);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(
                path,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.err.println("[Kanvas] capture write failed: " + e.getMessage());
        }
    }

    private static String unsigned(int value) {
        return Long.toUnsignedString(Integer.toUnsignedLong(value));
    }

    private static float parseFloat(String raw, float fallback) {
        if (raw == null || raw.equals("null") || raw.equals("unknown")) return fallback;
        try {
            float value = Float.parseFloat(raw);
            return Float.isFinite(value) ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int filteredColor(Object paint, String raw) {
        return ColorFilterCaptureStore.applyPaint(
            paint,
            parseColor(raw)
        );
    }

    private static int parseColor(String raw) {
        if (raw == null || raw.equals("null") || raw.equals("unknown")) return 0xffffffff;
        try {
            return (int)Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return 0xffffffff;
        }
    }

    private static String array(float[] value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < value.length; i++) {
            if (i != 0) out.append(',');
            out.append(value[i]);
        }
        return out.append(']').toString();
    }

    private static String describe(Object value) {
        if (value == null) return "null";
        return value.getClass().getName();
    }

    private static String string(Object value) {
        return value == null ? "null" : value.toString().replace(' ', '_');
    }

    private static String quote(String text) {
        if (text == null) return "null";
        String escaped = text
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private static final class FontAccess {
        private final Method size;
        private final Method typeface;

        FontAccess(Class<?> type) {
            size = method(type, "getSize");
            typeface = method(type, "getTypeface");
        }

        FontSnapshot read(Object font) {
            float resolvedSize = 14f;
            String family = "Default";
            try {
                if (size != null) {
                    Object raw = size.invoke(font);
                    if (raw instanceof Number number) resolvedSize = number.floatValue();
                }
                if (typeface != null) {
                    Object face = typeface.invoke(font);
                    if (face != null) {
                        Method familyName = method(face.getClass(), "getFamilyName");
                        if (familyName != null) {
                            Object raw = familyName.invoke(face);
                            if (raw != null && !raw.toString().isBlank()) family = raw.toString();
                        }
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
            if (!Float.isFinite(resolvedSize) || resolvedSize <= 0f) resolvedSize = 14f;
            return new FontSnapshot(family, resolvedSize);
        }

        private static Method method(Class<?> type, String name) {
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
    }

    private record FontSnapshot(String family, float size) {
        static final FontSnapshot DEFAULT = new FontSnapshot("Default", 14f);
    }

    private static final class PaintAccess {
        private final Method color;
        private final Method mode;
        private final Method strokeWidth;

        PaintAccess(Class<?> type) {
            color = method(type, "getColor");
            mode = method(type, "getMode");
            strokeWidth = method(type, "getStrokeWidth");
        }

        PaintSnapshot read(Object paint) {
            return new PaintSnapshot(
                invoke(color, paint),
                invoke(mode, paint),
                invoke(strokeWidth, paint)
            );
        }

        private static Method method(Class<?> type, String name) {
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        private static String invoke(Method method, Object target) {
            if (method == null) return "unknown";
            try {
                Object value = method.invoke(target);
                return String.valueOf(value).replace(' ', '_');
            } catch (ReflectiveOperationException ignored) {
                return "unknown";
            }
        }
    }

    private record PaintSnapshot(String color, String mode, String strokeWidth) {
        static final PaintSnapshot EMPTY = new PaintSnapshot("null", "null", "null");

        String asFields() {
            return " color=" + color + " mode=" + mode + " stroke=" + strokeWidth;
        }
    }
}
