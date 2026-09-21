package dev.yurie.display

import dev.yurie.display.ui.uiScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WireframePreviewTest {
    @Test fun emitsClippedOrderedModelCommands() {
        val frame = uiScene(240, 140) { wireframe(160f, 96f, 0.6f) }.frame()
        assertTrue(frame.commands.first() is DrawCommand.Clear)
        assertTrue(frame.commands.size in 100..4096)
        frame.commands.drop(1).forEach { item ->
            val bounds = (item as DrawCommand.FillRect).bounds
            assertTrue(bounds.x >= 0f && bounds.y >= 0f)
            assertTrue(bounds.x + bounds.width <= 160f)
            assertTrue(bounds.y + bounds.height <= 96f)
        }
    }

    @Test fun angleChangesProjectionWithoutChangingBackendContract() {
        val first = uiScene(240, 140) { wireframe(160f, 96f, 0f) }.frame()
        val second = uiScene(240, 140) { wireframe(160f, 96f, 1f) }.frame()
        assertNotEquals(first.commands, second.commands)
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
    }

    @Test fun rejectsInvalidAngles() {
        assertFailsWith<IllegalArgumentException> {
            uiScene(240, 140) { wireframe(160f, 96f, Float.NaN) }
        }
    }
}
