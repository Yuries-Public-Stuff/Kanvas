package dev.yurie.display.agent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class PathEffectCaptureStore {
    record Dash(float[] intervals, float phase) {}

    private static final Map<Object, Dash> EFFECTS = new WeakHashMap<>();

    private PathEffectCaptureStore() {}

    public static synchronized void dash(
        Object effect,
        float[] intervals,
        float phase
    ) {
        if (effect == null || intervals == null || intervals.length < 2) return;
        float total = 0f;
        for (float interval : intervals) {
            if (!Float.isFinite(interval) || interval <= 0f) return;
            total += interval;
        }
        if (total <= 0f) return;
        EFFECTS.put(effect, new Dash(intervals.clone(), phase));
    }

    static synchronized Dash get(Object effect) {
        return effect == null ? null : EFFECTS.get(effect);
    }

    static Dash fromPaint(Object paint) {
        if (paint == null) return null;
        try {
            return get(invoke(paint, "getPathEffect"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean hasUnsupportedPathEffect(Object paint) {
        if (paint == null) return false;
        try {
            Object effect = invoke(paint, "getPathEffect");
            return effect != null && get(effect) == null;
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
