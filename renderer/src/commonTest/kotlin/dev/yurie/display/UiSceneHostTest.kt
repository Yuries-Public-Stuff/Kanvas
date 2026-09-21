package dev.yurie.display

import dev.yurie.display.ui.UiSceneHost
import dev.yurie.display.ui.uiScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UiSceneHostTest {
    @Test fun cachesFrameUntilInvalidated() {
        var builds = 0
        val host = UiSceneHost { width, height ->
            builds++
            uiScene(width, height) { box(20f, 20f, Rgba(1f, 0f, 0f)) }
        }
        val first = host.frame(100, 80)
        assertSame(first, host.frame(100, 80))
        assertEquals(1, builds)
        host.invalidate()
        val next = host.frame(100, 80)
        assertEquals(2, builds)
        assertEquals(first.commands, next.commands)
    }

    @Test fun clickRebuildsFromUpdatedState() {
        var active = false
        var builds = 0
        val host = UiSceneHost { width, height ->
            builds++
            uiScene(width, height) {
                button(30f, 20f, if (active) Rgba(0f, 1f, 0f) else Rgba(1f, 0f, 0f)) {
                    active = !active
                }
            }
        }
        assertEquals(Rgba(1f, 0f, 0f), (host.frame(100, 80).commands.last() as DrawCommand.FillRect).color)
        assertFalse(host.click(90f, 70f))
        assertEquals(1, builds)
        assertTrue(host.click(10f, 10f))
        assertEquals(Rgba(0f, 1f, 0f), (host.frame(100, 80).commands.last() as DrawCommand.FillRect).color)
        assertEquals(2, builds)
    }

    @Test fun resizeRebuildsAndValidatesSize() {
        var builds = 0
        val host = UiSceneHost { width, height ->
            builds++
            uiScene(width, height) {}
        }
        assertEquals(100, host.frame(100, 80).width)
        assertEquals(120, host.frame(120, 80).width)
        assertEquals(2, builds)
        assertFailsWith<IllegalArgumentException> { host.frame(0, 80) }
    }

    @Test fun refusesInputBeforeLayoutAndMismatchedScenes() {
        val host = UiSceneHost { _, _ -> uiScene(10, 10) {} }
        assertFailsWith<IllegalStateException> { host.click(1f, 1f) }
        assertFailsWith<IllegalArgumentException> { host.frame(100, 100) }
    }
}
