package dev.yurie.display.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

final class CanvasRoutingTest {
    @AfterEach
    void reset() {
        LiveGpuTakeover.configure(false, false);
    }

    @Test
    void capturesOnlyFirstCanvasWithinFrame() {
        LiveGpuTakeover.configure(true, false);

        Object primary = new Object();
        Object offscreen = new Object();

        ComposeCaptureHooks.frameStart(null, 320, 200, 1L);
        try {
            assertTrue(ComposeCaptureHooks.captureCanvas(primary));
            assertTrue(ComposeCaptureHooks.captureCanvas(primary));
            assertFalse(ComposeCaptureHooks.captureCanvas(offscreen));
            assertFalse(ComposeCaptureHooks.canvasCallAllowed());

            ComposeCaptureHooks.markCanvasCall(primary);
            assertTrue(ComposeCaptureHooks.canvasCallAllowed());
        } finally {
            ComposeCaptureHooks.frameEnd();
        }

        assertFalse(ComposeCaptureHooks.captureCanvas(primary));
    }

    @Test
    void pictureReplayIsBypassedOnlyOutsideCapturedFrame() {
        LiveGpuTakeover.configure(true, false);

        assertTrue(ComposeCaptureHooks.bypassPictureReplay());

        Object primary = new Object();
        ComposeCaptureHooks.frameStart(null, 100, 100, 1L);
        try {
            ComposeCaptureHooks.markCanvasCall(primary);
            assertFalse(ComposeCaptureHooks.bypassPictureReplay());
        } finally {
            ComposeCaptureHooks.frameEnd();
        }

        assertTrue(ComposeCaptureHooks.bypassPictureReplay());
    }

    @Test
    void glyphAtlasPacksAndWrapsRowsWithoutOverlap() {
        LiveGpuTakeover.GlyphAtlas atlas =
            new LiveGpuTakeover.GlyphAtlas(9);

        BufferedImage first = new BufferedImage(
            600, 20, BufferedImage.TYPE_INT_ARGB
        );
        BufferedImage second = new BufferedImage(
            500, 30, BufferedImage.TYPE_INT_ARGB
        );

        int[] a = atlas.place(first);
        int[] b = atlas.place(second);

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(1, a[0]);
        assertEquals(1, a[1]);
        assertEquals(1, b[0]);
        assertTrue(b[1] > a[1]);
        assertTrue(atlas.dirty);
    }

    @Test
    void glyphAtlasRejectsOversizedGlyph() {
        LiveGpuTakeover.GlyphAtlas atlas =
            new LiveGpuTakeover.GlyphAtlas(3);

        BufferedImage tooLarge = new BufferedImage(
            1024, 1, BufferedImage.TYPE_INT_ARGB
        );

        assertNull(atlas.place(tooLarge));
    }
}
