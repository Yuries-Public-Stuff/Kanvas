package dev.yurie.display.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class ShaderCaptureStore {
    sealed interface ShaderInfo permits Linear, Radial, Sweep {
        int sample(float x, float y);
    }

    record Stop(float position, float r, float g, float b, float a) {}

    record Linear(
        float x0,
        float y0,
        float x1,
        float y1,
        Stop[] stops,
        String tileMode
    ) implements ShaderInfo {
        @Override
        public int sample(float x, float y) {
            float dx = x1 - x0;
            float dy = y1 - y0;
            float length2 = dx * dx + dy * dy;
            float t = length2 <= 0f ? 0f :
                ((x - x0) * dx + (y - y0) * dy) / length2;
            return sampleStops(stops, tile(t, tileMode));
        }
    }

    record Radial(
        float x,
        float y,
        float radius,
        Stop[] stops,
        String tileMode
    ) implements ShaderInfo {
        @Override
        public int sample(float px, float py) {
            float dx = px - x;
            float dy = py - y;
            float t = radius <= 0f ? 0f :
                (float)Math.sqrt(dx * dx + dy * dy) / radius;
            return sampleStops(stops, tile(t, tileMode));
        }
    }

    record Sweep(
        float x,
        float y,
        float startAngle,
        float endAngle,
        Stop[] stops,
        String tileMode
    ) implements ShaderInfo {
        @Override
        public int sample(float px, float py) {
            double angle = Math.toDegrees(Math.atan2(py - y, px - x));
            if (angle < 0.0) angle += 360.0;
            float span = endAngle - startAngle;
            float t = span == 0f ? 0f : ((float)angle - startAngle) / span;
            return sampleStops(stops, tile(t, tileMode));
        }
    }

    private static final Map<Object, ShaderInfo> SHADERS = new WeakHashMap<>();

    private ShaderCaptureStore() {}

    public static synchronized void linearArgb(
        Object shader,
        float x0,
        float y0,
        float x1,
        float y1,
        int[] colors,
        float[] positions,
        Object style
    ) {
        GradientData data = gradient(colors, positions, style);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Linear(x0, y0, x1, y1, data.stops, data.tileMode)
            );
        }
    }

    public static synchronized void radialArgb(
        Object shader,
        float x,
        float y,
        float radius,
        int[] colors,
        float[] positions,
        Object style
    ) {
        GradientData data = gradient(colors, positions, style);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Radial(x, y, radius, data.stops, data.tileMode)
            );
        }
    }

    public static synchronized void sweepArgb(
        Object shader,
        float x,
        float y,
        float startAngle,
        float endAngle,
        int[] colors,
        float[] positions,
        Object style
    ) {
        GradientData data = gradient(colors, positions, style);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Sweep(
                    x,
                    y,
                    startAngle,
                    endAngle,
                    data.stops,
                    data.tileMode
                )
            );
        }
    }

    public static synchronized void linearColor4f(
        Object shader,
        float x0,
        float y0,
        float x1,
        float y1,
        Object[] colors,
        float[] positions,
        Object style
    ) {
        GradientData data = gradient(colors, positions, style);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Linear(x0, y0, x1, y1, data.stops, data.tileMode)
            );
        }
    }

    public static synchronized void radialColor4f(
        Object shader,
        float x,
        float y,
        float radius,
        Object[] colors,
        float[] positions,
        Object style
    ) {
        GradientData data = gradient(colors, positions, style);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Radial(x, y, radius, data.stops, data.tileMode)
            );
        }
    }

    public static synchronized void sweepColor4f(
        Object shader,
        float x,
        float y,
        float startAngle,
        float endAngle,
        Object[] colors,
        float[] positions,
        Object style
    ) {
        GradientData data = gradient(colors, positions, style);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Sweep(
                    x,
                    y,
                    startAngle,
                    endAngle,
                    data.stops,
                    data.tileMode
                )
            );
        }
    }

    public static synchronized void linear(
        Object shader,
        float x0,
        float y0,
        float x1,
        float y1,
        Object gradient
    ) {
        GradientData data = gradient(gradient);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Linear(x0, y0, x1, y1, data.stops, data.tileMode)
            );
        }
    }

    public static synchronized void linearLegacy(
        Object shader,
        float x0,
        float y0,
        float x1,
        float y1,
        int[] colors,
        float[] positions,
        Object style
    ) {
        LegacyGradient data = legacyGradient(colors, positions, style);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Linear(x0, y0, x1, y1, data.stops, data.tileMode)
            );
        }
    }

    public static synchronized void radialLegacy(
        Object shader,
        float x,
        float y,
        float radius,
        int[] colors,
        float[] positions,
        Object style
    ) {
        LegacyGradient data = legacyGradient(colors, positions, style);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Radial(x, y, radius, data.stops, data.tileMode)
            );
        }
    }

    public static synchronized void sweepLegacy(
        Object shader,
        float x,
        float y,
        float startAngle,
        float endAngle,
        int[] colors,
        float[] positions,
        Object style
    ) {
        LegacyGradient data = legacyGradient(colors, positions, style);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Sweep(
                    x,
                    y,
                    startAngle,
                    endAngle,
                    data.stops,
                    data.tileMode
                )
            );
        }
    }

    public static synchronized void radial(
        Object shader,
        float x,
        float y,
        float radius,
        Object gradient
    ) {
        GradientData data = gradient(gradient);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Radial(x, y, radius, data.stops, data.tileMode)
            );
        }
    }

    public static synchronized void sweep(
        Object shader,
        float x,
        float y,
        float startAngle,
        float endAngle,
        Object gradient
    ) {
        GradientData data = gradient(gradient);
        if (shader != null && data != null) {
            SHADERS.put(
                shader,
                new Sweep(
                    x,
                    y,
                    startAngle,
                    endAngle,
                    data.stops,
                    data.tileMode
                )
            );
        }
    }

    static synchronized ShaderInfo get(Object shader) {
        return shader == null ? null : SHADERS.get(shader);
    }

    static ShaderInfo fromPaint(Object paint) {
        if (paint == null) return null;
        try {
            Object shader = invoke(paint, "getShader");
            return get(shader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean hasUnsupportedShader(Object paint) {
        if (paint == null) return false;
        try {
            Object shader = invoke(paint, "getShader");
            return shader != null && get(shader) == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private record GradientData(Stop[] stops, String tileMode) {}
    private record LegacyGradient(Stop[] stops, String tileMode) {}

    private static LegacyGradient legacyGradient(
        int[] colors,
        float[] positions,
        Object style
    ) {
        if (colors == null || colors.length == 0) return null;
        if (positions != null && positions.length != colors.length) return null;

        try {
            if (style != null) {
                Object matrix = invoke(style, "getLocalMatrix");
                if (matrix != null) return null;
            }
        } catch (Throwable ignored) {
        }

        Stop[] stops = new Stop[colors.length];
        for (int i = 0; i < colors.length; i++) {
            int color = colors[i];
            float position = positions == null
                ? (colors.length == 1 ? 0f : (float)i / (colors.length - 1))
                : positions[i];
            stops[i] = new Stop(
                position,
                ((color >>> 16) & 0xff) / 255f,
                ((color >>> 8) & 0xff) / 255f,
                (color & 0xff) / 255f,
                ((color >>> 24) & 0xff) / 255f
            );
        }

        String tileMode = "CLAMP";
        if (style != null) {
            try {
                Object value = invoke(style, "getTileMode");
                if (value != null) tileMode = value.toString();
            } catch (Throwable ignored) {
            }
        }
        return new LegacyGradient(stops, tileMode);
    }



    private static GradientData gradient(
        int[] colors,
        float[] positions,
        Object style
    ) {
        if (colors == null || colors.length == 0) return null;
        if (!supportedStyle(style)) return null;

        Stop[] stops = new Stop[colors.length];
        for (int i = 0; i < colors.length; i++) {
            int color = colors[i];
            stops[i] = new Stop(
                position(i, colors.length, positions),
                ((color >>> 16) & 0xff) / 255f,
                ((color >>> 8) & 0xff) / 255f,
                (color & 0xff) / 255f,
                ((color >>> 24) & 0xff) / 255f
            );
        }
        return new GradientData(stops, tileMode(style));
    }

    private static GradientData gradient(
        Object[] colors,
        float[] positions,
        Object style
    ) {
        if (colors == null || colors.length == 0) return null;
        if (!supportedStyle(style)) return null;

        Stop[] stops = new Stop[colors.length];
        try {
            for (int i = 0; i < colors.length; i++) {
                Object color = colors[i];
                stops[i] = new Stop(
                    position(i, colors.length, positions),
                    number(invoke(color, "getR")),
                    number(invoke(color, "getG")),
                    number(invoke(color, "getB")),
                    number(invoke(color, "getA"))
                );
            }
            return new GradientData(stops, tileMode(style));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float position(
        int index,
        int count,
        float[] positions
    ) {
        if (positions != null && index < positions.length) {
            return positions[index];
        }
        return count == 1 ? 0f : (float)index / (count - 1);
    }

    private static boolean supportedStyle(Object style) {
        if (style == null) return true;
        try {
            Object localMatrix = invoke(style, "getLocalMatrix");
            return localMatrix == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String tileMode(Object style) {
        if (style == null) return "CLAMP";
        try {
            Object mode = invoke(style, "getTileMode");
            return mode == null ? "CLAMP" : mode.toString();
        } catch (Throwable ignored) {
            return "CLAMP";
        }
    }

    private static GradientData gradient(Object gradient) {
        if (gradient == null) return null;
        try {
            Object colorsSpec = invoke(gradient, "getColors");
            Object colors = invoke(colorsSpec, "getColors");
            Object positions = invoke(colorsSpec, "getPositions");
            Object tileMode = invoke(colorsSpec, "getTileMode");
            int count = Array.getLength(colors);
            if (count == 0) return null;

            Stop[] stops = new Stop[count];
            for (int i = 0; i < count; i++) {
                Object color = Array.get(colors, i);
                float position = positions == null
                    ? (count == 1 ? 0f : (float)i / (count - 1))
                    : Array.getFloat(positions, i);
                stops[i] = new Stop(
                    position,
                    number(invoke(color, "getR")),
                    number(invoke(color, "getG")),
                    number(invoke(color, "getB")),
                    number(invoke(color, "getA"))
                );
            }
            return new GradientData(
                stops,
                tileMode == null ? "CLAMP" : tileMode.toString()
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float tile(float value, String mode) {
        if (mode == null || mode.equalsIgnoreCase("CLAMP")) {
            return Math.max(0f, Math.min(1f, value));
        }
        if (mode.equalsIgnoreCase("REPEAT")) {
            return value - (float)Math.floor(value);
        }
        if (mode.equalsIgnoreCase("MIRROR")) {
            float repeated = value - (float)Math.floor(value / 2f) * 2f;
            return repeated <= 1f ? repeated : 2f - repeated;
        }
        if (mode.equalsIgnoreCase("DECAL")) {
            return Math.max(0f, Math.min(1f, value));
        }
        return Math.max(0f, Math.min(1f, value));
    }

    private static int sampleStops(Stop[] stops, float t) {
        if (stops.length == 1 || t <= stops[0].position) return argb(stops[0]);
        Stop last = stops[stops.length - 1];
        if (t >= last.position) return argb(last);

        for (int i = 0; i + 1 < stops.length; i++) {
            Stop a = stops[i];
            Stop b = stops[i + 1];
            if (t < a.position || t > b.position) continue;
            float span = b.position - a.position;
            float local = span <= 0f ? 0f : (t - a.position) / span;
            return argb(
                lerp(a.a, b.a, local),
                lerp(a.r, b.r, local),
                lerp(a.g, b.g, local),
                lerp(a.b, b.b, local)
            );
        }
        return argb(last);
    }

    private static int argb(Stop stop) {
        return argb(stop.a, stop.r, stop.g, stop.b);
    }

    private static int argb(float a, float r, float g, float b) {
        int aa = channel(a);
        int rr = channel(r);
        int gg = channel(g);
        int bb = channel(b);
        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float number(Object value) {
        return value instanceof Number number ? number.floatValue() : 0f;
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
