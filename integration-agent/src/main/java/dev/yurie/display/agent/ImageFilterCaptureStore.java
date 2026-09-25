package dev.yurie.display.agent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class ImageFilterCaptureStore {
    record DropShadow(
        float dx,
        float dy,
        float sigmaX,
        float sigmaY,
        int color,
        boolean includeSource
    ) {}

    private static final Map<Object, DropShadow> FILTERS = new WeakHashMap<>();

    private ImageFilterCaptureStore() {}

    public static synchronized void dropShadow(
        Object filter,
        float dx,
        float dy,
        float sigmaX,
        float sigmaY,
        int color,
        boolean includeSource
    ) {
        if (filter == null) return;
        FILTERS.put(
            filter,
            new DropShadow(dx, dy, sigmaX, sigmaY, color, includeSource)
        );
    }

    static synchronized DropShadow get(Object filter) {
        return filter == null ? null : FILTERS.get(filter);
    }

    static DropShadow fromPaint(Object paint) {
        if (paint == null) return null;
        try {
            Object filter = invoke(paint, "getImageFilter");
            return get(filter);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean hasUnsupportedImageFilter(Object paint) {
        if (paint == null) return false;
        try {
            Object filter = invoke(paint, "getImageFilter");
            return filter != null && get(filter) == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
