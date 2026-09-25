package dev.yurie.display.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class ParagraphCaptureStore {
    record Style(
        int color,
        float size,
        String family,
        int weight,
        boolean italic
    ) {}

    record Run(String text, Style style) {}

    record ParagraphInfo(List<Run> runs) {}

    private static final Style DEFAULT =
        new Style(0xffffffff, 14f, "SansSerif", 400, false);

    private static final class BuilderInfo {
        final Deque<Style> styles = new ArrayDeque<>();
        final List<Run> runs = new ArrayList<>();
        Style defaultStyle = DEFAULT;

        Style current() {
            return styles.isEmpty() ? defaultStyle : styles.peek();
        }
    }

    private static final Map<Object, BuilderInfo> BUILDERS = new WeakHashMap<>();
    private static final Map<Object, ParagraphInfo> PARAGRAPHS = new WeakHashMap<>();

    private ParagraphCaptureStore() {}

    public static synchronized void builderCreated(Object builder, Object paragraphStyle) {
        if (builder == null) return;
        BuilderInfo info = BUILDERS.computeIfAbsent(builder, ignored -> new BuilderInfo());
        if (paragraphStyle == null) return;
        try {
            Object textStyle = invoke(paragraphStyle, "getTextStyle");
            if (textStyle != null) info.defaultStyle = readStyle(textStyle);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void pushStyle(Object builder, Object style) {
        if (builder == null) return;
        BuilderInfo info = BUILDERS.computeIfAbsent(builder, ignored -> new BuilderInfo());
        info.styles.push(style == null ? info.current() : readStyle(style));
    }

    public static synchronized void popStyle(Object builder) {
        BuilderInfo info = BUILDERS.get(builder);
        if (info != null && !info.styles.isEmpty()) info.styles.pop();
    }

    public static synchronized void addText(Object builder, String text) {
        if (builder == null || text == null || text.isEmpty()) return;
        BuilderInfo info = BUILDERS.computeIfAbsent(builder, ignored -> new BuilderInfo());
        Style style = info.current();
        if (!info.runs.isEmpty()) {
            Run last = info.runs.get(info.runs.size() - 1);
            if (last.style().equals(style)) {
                info.runs.set(
                    info.runs.size() - 1,
                    new Run(last.text() + text, style)
                );
                return;
            }
        }
        info.runs.add(new Run(text, style));
    }

    public static synchronized void built(Object paragraph, Object builder) {
        if (paragraph == null || builder == null) return;
        BuilderInfo info = BUILDERS.remove(builder);
        if (info == null || info.runs.isEmpty()) return;
        PARAGRAPHS.put(
            paragraph,
            new ParagraphInfo(List.copyOf(info.runs))
        );
    }

    static synchronized ParagraphInfo get(Object paragraph) {
        return paragraph == null ? null : PARAGRAPHS.get(paragraph);
    }

    private static Style readStyle(Object style) {
        if (style == null) return DEFAULT;
        try {
            int color = ((Number)invoke(style, "getColor")).intValue();
            float size = ((Number)invoke(style, "getFontSize")).floatValue();
            String family = firstFamily(invoke(style, "getFontFamilies"));
            Object fontStyle = invoke(style, "getFontStyle");
            int weight = readFontWeight(fontStyle);
            boolean italic = readItalic(fontStyle);
            return new Style(
                color,
                Float.isFinite(size) && size > 0f ? size : DEFAULT.size(),
                family == null || family.isBlank() ? DEFAULT.family() : family,
                weight,
                italic
            );
        } catch (Throwable ignored) {
            return DEFAULT;
        }
    }

    private static String firstFamily(Object value) {
        if (value == null || !value.getClass().isArray() || Array.getLength(value) == 0) {
            return null;
        }
        Object first = Array.get(value, 0);
        return first == null ? null : first.toString();
    }

    private static int readFontWeight(Object fontStyle) {
        if (fontStyle == null) return 400;
        try {
            Object weight = invoke(fontStyle, "getWeight");
            if (weight instanceof Number number) {
                return Math.max(1, Math.min(1000, number.intValue()));
            }
        } catch (Throwable ignored) {
        }
        return 400;
    }

    private static boolean readItalic(Object fontStyle) {
        if (fontStyle == null) return false;
        try {
            Object slant = invoke(fontStyle, "getSlant");
            return slant != null && !slant.toString().equalsIgnoreCase("UPRIGHT");
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
