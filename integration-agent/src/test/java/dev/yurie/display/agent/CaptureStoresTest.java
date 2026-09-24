package dev.yurie.display.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CaptureStoresTest {
    @Test
    void capturesParagraphStyleRuns() {
        Object builder = new Object();
        FakeTextStyle base = new FakeTextStyle(
            0xff112233,
            15f,
            new String[]{"Inter"},
            new FakeFontStyle(400, "UPRIGHT")
        );
        FakeTextStyle bold = new FakeTextStyle(
            0xffabcdef,
            20f,
            new String[]{"Inter"},
            new FakeFontStyle(700, "ITALIC")
        );

        ParagraphCaptureStore.builderCreated(
            builder,
            new FakeParagraphStyle(base)
        );
        ParagraphCaptureStore.addText(builder, "hello ");
        ParagraphCaptureStore.pushStyle(builder, bold);
        ParagraphCaptureStore.addText(builder, "world");
        ParagraphCaptureStore.popStyle(builder);

        Object paragraph = new Object();
        ParagraphCaptureStore.built(paragraph, builder);
        ParagraphCaptureStore.ParagraphInfo info =
            ParagraphCaptureStore.get(paragraph);

        assertNotNull(info);
        assertEquals(2, info.runs().size());
        assertEquals("hello ", info.runs().get(0).text());
        assertEquals(0xff112233, info.runs().get(0).style().color());
        assertEquals("world", info.runs().get(1).text());
        assertEquals(700, info.runs().get(1).style().weight());
        assertTrue(info.runs().get(1).style().italic());
    }

    @Test
    void samplesCapturedLinearGradient() {
        Object shader = new Object();
        FakeGradient gradient = new FakeGradient(
            new FakeColors(
                new FakeColor[]{
                    new FakeColor(1f, 0f, 0f, 1f),
                    new FakeColor(0f, 0f, 1f, 0.5f),
                },
                new float[]{0f, 1f},
                "CLAMP"
            )
        );

        ShaderCaptureStore.linear(
            shader,
            0f,
            0f,
            100f,
            0f,
            gradient
        );

        FakePaint paint = new FakePaint(shader, null);
        ShaderCaptureStore.ShaderInfo info =
            ShaderCaptureStore.fromPaint(paint);

        assertNotNull(info);
        assertEquals(0xffff0000, info.sample(0f, 0f));

        int middle = info.sample(50f, 0f);
        int alpha = (middle >>> 24) & 0xff;
        int red = (middle >>> 16) & 0xff;
        int blue = middle & 0xff;

        assertTrue(alpha > 180 && alpha < 205);
        assertTrue(red > 120 && red < 136);
        assertTrue(blue > 120 && blue < 136);
    }

    @Test
    void recognizesCapturedDropShadowOnly() {
        Object filter = new Object();
        ImageFilterCaptureStore.dropShadow(
            filter,
            2f,
            3f,
            4f,
            5f,
            0x80112233,
            false
        );

        FakePaint paint = new FakePaint(null, filter);
        ImageFilterCaptureStore.DropShadow shadow =
            ImageFilterCaptureStore.fromPaint(paint);

        assertNotNull(shadow);
        assertEquals(2f, shadow.dx());
        assertEquals(5f, shadow.sigmaY());
        assertEquals(0x80112233, shadow.color());
        assertFalse(shadow.includeSource());
        assertFalse(
            ImageFilterCaptureStore.hasUnsupportedImageFilter(paint)
        );

        assertTrue(
            ImageFilterCaptureStore.hasUnsupportedImageFilter(
                new FakePaint(null, new Object())
            )
        );
    }

    static final class FakeParagraphStyle {
        private final FakeTextStyle textStyle;

        FakeParagraphStyle(FakeTextStyle textStyle) {
            this.textStyle = textStyle;
        }

        public FakeTextStyle getTextStyle() {
            return textStyle;
        }
    }

    static final class FakeTextStyle {
        private final int color;
        private final float fontSize;
        private final String[] fontFamilies;
        private final FakeFontStyle fontStyle;

        FakeTextStyle(
            int color,
            float fontSize,
            String[] fontFamilies,
            FakeFontStyle fontStyle
        ) {
            this.color = color;
            this.fontSize = fontSize;
            this.fontFamilies = fontFamilies;
            this.fontStyle = fontStyle;
        }

        public int getColor() {
            return color;
        }

        public float getFontSize() {
            return fontSize;
        }

        public String[] getFontFamilies() {
            return fontFamilies;
        }

        public FakeFontStyle getFontStyle() {
            return fontStyle;
        }
    }

    static final class FakeFontStyle {
        private final int weight;
        private final String slant;

        FakeFontStyle(int weight, String slant) {
            this.weight = weight;
            this.slant = slant;
        }

        public int getWeight() {
            return weight;
        }

        public String getSlant() {
            return slant;
        }
    }

    static final class FakeGradient {
        private final FakeColors colors;

        FakeGradient(FakeColors colors) {
            this.colors = colors;
        }

        public FakeColors getColors() {
            return colors;
        }
    }

    static final class FakeColors {
        private final FakeColor[] colors;
        private final float[] positions;
        private final String tileMode;

        FakeColors(
            FakeColor[] colors,
            float[] positions,
            String tileMode
        ) {
            this.colors = colors;
            this.positions = positions;
            this.tileMode = tileMode;
        }

        public FakeColor[] getColors() {
            return colors;
        }

        public float[] getPositions() {
            return positions;
        }

        public String getTileMode() {
            return tileMode;
        }
    }

    static final class FakeColor {
        private final float r;
        private final float g;
        private final float b;
        private final float a;

        FakeColor(float r, float g, float b, float a) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }

        public float getR() {
            return r;
        }

        public float getG() {
            return g;
        }

        public float getB() {
            return b;
        }

        public float getA() {
            return a;
        }
    }

    static final class FakePaint {
        private final Object shader;
        private final Object imageFilter;

        FakePaint(Object shader, Object imageFilter) {
            this.shader = shader;
            this.imageFilter = imageFilter;
        }

        public Object getShader() {
            return shader;
        }

        public Object getImageFilter() {
            return imageFilter;
        }
    }
}
