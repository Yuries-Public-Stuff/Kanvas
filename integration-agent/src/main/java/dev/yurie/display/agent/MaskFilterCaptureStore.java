package dev.yurie.display.agent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class MaskFilterCaptureStore {
    record Blur(String mode, float sigma, boolean respectCtm) {}

    private static final Map<Object, Blur> FILTERS = new WeakHashMap<>();

    private MaskFilterCaptureStore() {}

    public static synchronized void blur(
        Object filter,
        Object mode,
        float sigma,
        boolean respectCtm
    ) {
        if (filter == null) return;
        FILTERS.put(
            filter,
            new Blur(
                mode == null ? "NORMAL" : mode.toString(),
                sigma,
                respectCtm
            )
        );
    }

    static synchronized Blur get(Object filter) {
        return filter == null ? null : FILTERS.get(filter);
    }

    static Blur fromPaint(Object paint) {
        if (paint == null) return null;
        try {
            Object filter = invoke(paint, "getMaskFilter");
            return get(filter);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean hasUnsupportedMaskFilter(Object paint) {
        if (paint == null) return false;
        try {
            Object filter = invoke(paint, "getMaskFilter");
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
