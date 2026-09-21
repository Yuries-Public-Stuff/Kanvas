package dev.yurie.display

import dev.yurie.display.ui.UiSceneHost
import dev.yurie.display.ui.UiState
import dev.yurie.display.ui.uiScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UiStateTest {
    @Test fun stateChangesInvalidateCachedFrame() {
        val active = UiState(false)
        var builds = 0
        val host = UiSceneHost { width, height ->
            builds++
            uiScene(width, height) {
                box(20f, 20f, if (active.value) Rgba(0f, 1f, 0f) else Rgba(1f, 0f, 0f))
            }
        }.watch(active)
        val original = host.frame(100, 80)
        active.value = false
        assertSame(original, host.frame(100, 80))
        assertEquals(1, builds)
        active.update { !it }
        val changed = host.frame(100, 80)
        assertEquals(2, builds)
        assertEquals(Rgba(0f, 1f, 0f), (changed.commands.last() as DrawCommand.FillRect).color)
        host.close()
        active.value = false
        assertFailsWith<IllegalStateException> { host.frame(100, 80) }
        assertFailsWith<IllegalStateException> { host.watch(active) }
    }

    @Test fun observersCanUnsubscribeAndMutationDuringNotificationIsSafe() {
        val state = UiState(0)
        val seen = mutableListOf<Int>()
        lateinit var unsubscribe: () -> Unit
        unsubscribe = state.observe {
            seen += state.value
            unsubscribe()
        }
        state.update { it + 1 }
        state.update { it + 1 }
        assertEquals(listOf(1), seen)
    }

    @Test fun hostSupportsMultipleObservedStates() {
        val a = UiState(1)
        val b = UiState(2)
        var builds = 0
        val host = UiSceneHost { width, height ->
            builds++
            uiScene(width, height) { box((a.value + b.value).toFloat(), 10f, Rgba(1f, 1f, 1f)) }
        }.watch(a).watch(b)
        host.frame(100, 100)
        a.value = 3
        b.value = 4
        val result = host.frame(100, 100)
        assertEquals(2, builds)
        assertEquals(7f, (result.commands.last() as DrawCommand.FillRect).bounds.width)
        host.close()
        host.close()
    }

    @Test fun queuedClicksUseRebuiltHitRegions() {
        val shifted = UiState(false)
        val host = UiSceneHost { width, height ->
            uiScene(width, height) {
                box(100f, if (shifted.value) 30f else 0f)
                button(20f, 20f, Rgba(1f, 0f, 0f)) { shifted.value = true }
            }
        }.watch(shifted)
        host.frame(100, 100)
        assertTrue(host.click(10f, 10f))
        host.frame(100, 100)
        assertFalse(host.click(10f, 10f))
        assertTrue(host.click(10f, 35f))
        host.close()
    }
}
