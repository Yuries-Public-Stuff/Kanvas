package dev.yurie.display

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanvasTest {
    @Test fun commandsPreserveOrder() {
        val image = frame(640, 480) {
            clear(Rgba(0f, 0f, 0f))
            fillRect(20f, 30f, 100f, 50f, Rgba(1f, 0f, 0f))
            fillRect(40f, 60f, 30f, 20f, Rgba(0f, 1f, 0f))
        }
        assertEquals(3, image.commands.size)
        assertEquals(DrawCommand.Clear(Rgba(0f, 0f, 0f)), image.commands[0])
        assertEquals(DrawCommand.FillRect(Rect(20f, 30f, 100f, 50f), Rgba(1f, 0f, 0f)), image.commands[1])
    }

    @Test fun snapshotsDoNotChange() {
        val canvas = Canvas(200, 100)
        canvas.clear(Rgba(1f, 0f, 0f))
        val first = canvas.frame()
        canvas.fillRect(0f, 0f, 10f, 10f, Rgba(0f, 1f, 0f))
        assertEquals(1, first.commands.size)
        assertEquals(2, canvas.frame().commands.size)
        canvas.clear(Rgba(0f, 0f, 1f))
        assertEquals(1, canvas.frame().commands.size)
    }

    @Test fun invalidInputIsRejected() {
        assertFailsWith<IllegalArgumentException> { Canvas(0, 480) }
        assertFailsWith<IllegalArgumentException> {
            frame(640, 480) { fillRect(0f, 0f, -1f, 20f, Rgba(1f, 1f, 1f)) }
        }
    }
}
