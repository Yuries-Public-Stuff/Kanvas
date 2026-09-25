package dev.yurie.display.agent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class ColorFilterCaptureStore {
    sealed interface FilterInfo permits MatrixFilter, BlendFilter {
        int apply(int argb);
    }

    record MatrixFilter(float[] matrix) implements FilterInfo {
        @Override
        public int apply(int argb) {
            float a = ((argb >>> 24) & 0xff) / 255f;
            float r = ((argb >>> 16) & 0xff) / 255f;
            float g = ((argb >>> 8) & 0xff) / 255f;
            float b = (argb & 0xff) / 255f;
            float[] m = matrix;
            return pack(
                m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19],
                m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4],
                m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9],
                m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14]
            );
        }
    }

    record BlendFilter(int color, String mode) implements FilterInfo {
        @Override
        public int apply(int destination) {
            return blend(color, destination, mode);
        }
    }

    private static final Map<Object, FilterInfo> FILTERS = new WeakHashMap<>();

    private ColorFilterCaptureStore() {}

    public static synchronized void matrix(Object filter, float[] matrix) {
        if (filter == null || matrix == null || matrix.length != 20) return;
        FILTERS.put(filter, new MatrixFilter(matrix.clone()));
    }

    public static synchronized void blend(Object filter, int color, Object mode) {
        if (filter == null || mode == null) return;
        String name = mode.toString();
        if (!supportedBlend(name)) return;
        FILTERS.put(filter, new BlendFilter(color, name));
    }

    static synchronized FilterInfo get(Object filter) {
        return filter == null ? null : FILTERS.get(filter);
    }

    static FilterInfo fromPaint(Object paint) {
        if (paint == null) return null;
        try {
            return get(invoke(paint, "getColorFilter"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean hasUnsupportedColorFilter(Object paint) {
        if (paint == null) return false;
        try {
            Object filter = invoke(paint, "getColorFilter");
            return filter != null && get(filter) == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int applyPaint(Object paint, int argb) {
        FilterInfo filter = fromPaint(paint);
        return filter == null ? argb : filter.apply(argb);
    }

    private static boolean supportedBlend(String mode) {
        return switch (mode) {
            case "CLEAR", "SRC", "DST", "SRC_OVER", "DST_OVER",
                 "SRC_IN", "DST_IN", "SRC_OUT", "DST_OUT",
                 "SRC_ATOP", "DST_ATOP", "XOR", "PLUS",
                 "MODULATE", "MULTIPLY", "SCREEN" -> true;
            default -> false;
        };
    }

    private static int blend(int source, int destination, String mode) {
        float sa = alpha(source);
        float sr = red(source);
        float sg = green(source);
        float sb = blue(source);
        float da = alpha(destination);
        float dr = red(destination);
        float dg = green(destination);
        float db = blue(destination);

        return switch (mode) {
            case "CLEAR" -> 0;
            case "SRC" -> source;
            case "DST" -> destination;
            case "SRC_IN" -> pack(sa * da, sr * da, sg * da, sb * da);
            case "DST_IN" -> pack(da * sa, dr * sa, dg * sa, db * sa);
            case "SRC_OUT" -> pack(sa * (1f - da), sr * (1f - da), sg * (1f - da), sb * (1f - da));
            case "DST_OUT" -> pack(da * (1f - sa), dr * (1f - sa), dg * (1f - sa), db * (1f - sa));
            case "SRC_ATOP" -> composite(sr, sg, sb, sa, dr, dg, db, da, da, 1f - sa);
            case "DST_ATOP" -> composite(sr, sg, sb, sa, dr, dg, db, da, 1f - da, sa);
            case "XOR" -> composite(sr, sg, sb, sa, dr, dg, db, da, 1f - da, 1f - sa);
            case "DST_OVER" -> composite(sr, sg, sb, sa, dr, dg, db, da, 1f - da, 1f);
            case "PLUS" -> pack(
                Math.min(1f, sa + da),
                Math.min(1f, sr * sa + dr * da),
                Math.min(1f, sg * sa + dg * da),
                Math.min(1f, sb * sa + db * da)
            );
            case "MODULATE" -> pack(sa * da, sr * dr, sg * dg, sb * db);
            case "MULTIPLY" -> blendSeparable(sr * dr, sg * dg, sb * db, sa, dr, dg, db, da);
            case "SCREEN" -> blendSeparable(
                sr + dr - sr * dr,
                sg + dg - sg * dg,
                sb + db - sb * db,
                sa, dr, dg, db, da
            );
            default -> composite(sr, sg, sb, sa, dr, dg, db, da, 1f, 1f - sa);
        };
    }

    private static int blendSeparable(
        float br, float bg, float bb,
        float sa,
        float dr, float dg, float db,
        float da
    ) {
        float outA = sa + da - sa * da;
        if (outA <= 0f) return 0;
        float r = (br * sa * da + dr * da * (1f - sa)) / outA;
        float g = (bg * sa * da + dg * da * (1f - sa)) / outA;
        float b = (bb * sa * da + db * da * (1f - sa)) / outA;
        return pack(outA, r, g, b);
    }

    private static int composite(
        float sr, float sg, float sb, float sa,
        float dr, float dg, float db, float da,
        float sourceFactor, float destinationFactor
    ) {
        float outA = sa * sourceFactor + da * destinationFactor;
        if (outA <= 0f) return 0;
        float r = (sr * sa * sourceFactor + dr * da * destinationFactor) / outA;
        float g = (sg * sa * sourceFactor + dg * da * destinationFactor) / outA;
        float b = (sb * sa * sourceFactor + db * da * destinationFactor) / outA;
        return pack(outA, r, g, b);
    }

    private static float alpha(int value) { return ((value >>> 24) & 0xff) / 255f; }
    private static float red(int value) { return ((value >>> 16) & 0xff) / 255f; }
    private static float green(int value) { return ((value >>> 8) & 0xff) / 255f; }
    private static float blue(int value) { return (value & 0xff) / 255f; }

    private static int pack(float a, float r, float g, float b) {
        return (channel(a) << 24) |
            (channel(r) << 16) |
            (channel(g) << 8) |
            channel(b);
    }

    private static int channel(float value) {
        if (!Float.isFinite(value)) return 0;
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
