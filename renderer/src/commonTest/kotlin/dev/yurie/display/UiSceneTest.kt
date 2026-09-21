package dev.yurie.display

import dev.yurie.display.ui.uiScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UiSceneTest {
    @Test fun rowUsesDensityPaddingAndSpacing() {
        val red = Rgba(1f, 0f, 0f)
        val green = Rgba(0f, 1f, 0f)
        val scene = uiScene(200, 100, density = 2f) {
            row(100f, 50f, padding = 2f, spacing = 3f) {
                button(20f, 10f, red) {}
                button(20f, 10f, green) {}
            }
        }
        val commands = scene.frame().commands
        assertEquals(3, commands.size)
        assertEquals(Rect(4f, 4f, 40f, 20f), (commands[1] as DrawCommand.FillRect).bounds)
        assertEquals(Rect(50f, 4f, 40f, 20f), (commands[2] as DrawCommand.FillRect).bounds)
    }

    @Test fun clickRespectsPixelDensityAndGaps() {
        var presses = 0
        val scene = uiScene(200, 100, density = 2f) {
            row(100f, 50f, padding = 2f, spacing = 3f) {
                button(20f, 10f, Rgba(1f, 0f, 0f)) { presses++ }
                button(20f, 10f, Rgba(0f, 1f, 0f)) { presses += 10 }
            }
        }
        assertTrue(scene.click(5f, 5f))
        assertTrue(scene.click(51f, 5f))
        assertFalse(scene.click(49f, 5f))
        assertFalse(scene.click(180f, 90f))
        assertEquals(11, presses)
    }

    @Test fun panelsClipDrawingAndHitRegions() {
        var presses = 0
        val scene = uiScene(30, 30) {
            box(10f, 10f, Rgba(1f, 1f, 1f)) {
                button(20f, 20f, Rgba(1f, 0f, 0f)) { presses++ }
            }
        }
        val commands = scene.frame().commands
        assertEquals(Rect(0f, 0f, 10f, 10f), (commands.last() as DrawCommand.FillRect).bounds)
        assertFalse(scene.click(15f, 5f))
        assertTrue(scene.click(5f, 5f))
        assertEquals(1, presses)
    }

    @Test fun invalidDimensionsAndClicksAreRejected() {
        assertFailsWith<IllegalArgumentException> { uiScene(0, 50) {} }
        assertFailsWith<IllegalArgumentException> { uiScene(50, 50, density = Float.NaN) {} }
        assertFailsWith<IllegalArgumentException> { uiScene(50, 50) { box(-1f, 20f) } }
        assertFailsWith<IllegalArgumentException> { uiScene(50, 50) { column(20f, 20f, spacing = -1f) {} } }
        assertFailsWith<IllegalArgumentException> { uiScene(50, 50) {}.click(Float.NaN, 0f) }
    }
}
