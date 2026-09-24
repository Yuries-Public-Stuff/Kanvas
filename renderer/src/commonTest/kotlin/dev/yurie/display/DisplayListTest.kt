package dev.yurie.display

import dev.yurie.display.scene.DisplayCommand
import dev.yurie.display.scene.FontResource
import dev.yurie.display.scene.LegacyFrameLowerer
import dev.yurie.display.scene.displayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayListTest {
    @Test
    fun lowersTransformsAndClipsIntoLegacyRectangles() {
        val list = displayList(200, 100) {
            clear(Rgba(0f, 0f, 0f, 1f))
            saved {
                translate(10f, 5f)
                scale(2f, 2f)
                clipRect(Rect(0f, 0f, 20f, 20f))
                fillRect(Rect(5f, 5f, 30f, 30f), Rgba(1f, 0f, 0f, 1f))
            }
        }

        val frame = LegacyFrameLowerer.lower(list)
        assertEquals(2, frame.commands.size)
        val rect = frame.commands[1] as DrawCommand.FillRect
        assertEquals(Rect(20f, 15f, 30f, 30f), rect.bounds)
    }

    @Test
    fun reportsNativePrimitivesBeforeLowering() {
        val list = displayList(100, 100) {
            val font = font(FontResource("Inter", 16f))
            drawGlyphRun(font, intArrayOf(1, 2), floatArrayOf(0f, 0f, 10f, 0f), Rgba(1f, 1f, 1f))
        }

        val compatibility = LegacyFrameLowerer.compatibility(list)
        assertFalse(compatibility.supported)
        assertTrue("DrawGlyphRun" in compatibility.unsupportedCommands)
    }

    @Test
    fun validatesBalancedStateAndLayers() {
        val failed = runCatching {
            dev.yurie.display.scene.DisplayList(
                100,
                100,
                listOf(DisplayCommand.Restore),
            )
        }
        assertTrue(failed.isFailure)
    }
}
