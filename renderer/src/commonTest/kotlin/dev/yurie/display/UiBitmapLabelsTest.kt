package dev.yurie.display

import dev.yurie.display.ui.uiScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiBitmapLabelsTest {
    @Test fun labelProducesOrderedOpaqueGeometry() {
        val white = Rgba(1f, 1f, 1f)
        val scene = uiScene(100, 60) {
            text(50f, 20f, "HI", white, scale = 2f)
        }
        val commands = scene.frame().commands
        assertTrue(commands.first() is DrawCommand.Clear)
        assertTrue(commands.size > 1)
        assertTrue(commands.drop(1).all { it is DrawCommand.FillRect && it.color == white })
        assertTrue(commands.size < 50)
    }

    @Test fun labelsCannotEscapeTheirClippingBox() {
        val scene = uiScene(120, 80) {
            box(12f, 12f) { text(90f, 20f, "KOTLIN", scale = 3f) }
        }
        val bounds = scene.frame().commands.drop(1).map { (it as DrawCommand.FillRect).bounds }
        assertTrue(bounds.isNotEmpty())
        assertTrue(bounds.all { it.x >= 0f && it.y >= 0f && it.x + it.width <= 12f && it.y + it.height <= 12f })
    }

    @Test fun labelButtonRemainsClickable() {
        var clicks = 0
        val scene = uiScene(200, 100) {
            button(100f, 32f, Rgba(0.2f, 0.3f, 0.4f), label = "GO") { clicks++ }
        }
        assertTrue(scene.click(30f, 15f))
        assertFalse(scene.click(105f, 15f))
        assertEquals(1, clicks)
        assertTrue(scene.frame().commands.size > 2)
    }

    @Test fun unsupportedCharactersAndScalesFailClearly() {
        assertFailsWith<IllegalArgumentException> {
            uiScene(100, 100) { text(60f, 20f, "🙂") }
        }
        assertFailsWith<IllegalArgumentException> {
            uiScene(100, 100) { text(60f, 20f, "ABC", scale = Float.NaN) }
        }
        assertFailsWith<IllegalArgumentException> {
            uiScene(100, 100) { text(60f, 20f, "ABC", scale = 0f) }
        }
    }
}
