package dev.yurie.display

import dev.yurie.display.ui.UiSceneHost
import dev.yurie.display.ui.UiState
import dev.yurie.display.ui.uiScene
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contracts selected from the first real-app parity target:
 * Wake's Compose Desktop client. These tests do not claim full Wake parity.
 */
class WakeFeatureParityTest {
    @Test
    fun sceneHostCachesStableUiAndInvalidatesObservedState() {
        val selected = UiState(false)
        var builds = 0
        val host = UiSceneHost { width, height ->
            builds++
            uiScene(width, height) {
                button(120f, 40f, Rgba(0.2f, 0.4f, 0.8f), "TOGGLE") {
                    selected.update { !it }
                }
            }
        }.watch(selected)

        val first = host.frame(640, 480)
        val cached = host.frame(640, 480)
        assertSame(first, cached)
        assertTrue(builds == 1)

        selected.value = true
        val rebuilt = host.frame(640, 480)
        assertNotSame(first, rebuilt)
        assertTrue(builds == 2)
        host.close()
    }

    @Test
    fun pointerClickMutatesStateAndCausesNextFrameRebuild() {
        val playing = UiState(false)
        val host = UiSceneHost { width, height ->
            uiScene(width, height) {
                button(160f, 64f, Rgba(0.12f, 0.55f, 0.36f), "PLAY") {
                    playing.update { !it }
                }
            }
        }.watch(playing)

        val before = host.frame(320, 200)
        assertFalse(playing.value)
        assertTrue(host.click(20f, 20f))
        assertTrue(playing.value)
        val after = host.frame(320, 200)
        assertNotSame(before, after)
        host.close()
    }

    @Test
    fun outOfBoundsPointerDoesNotConsumeOrInvalidate() {
        val state = UiState(0)
        val host = UiSceneHost { width, height ->
            uiScene(width, height) {
                button(100f, 40f, Rgba(0.7f, 0.2f, 0.2f), "NEXT") {
                    state.update { it + 1 }
                }
            }
        }.watch(state)

        val before = host.frame(300, 180)
        assertFalse(host.click(250f, 150f))
        assertTrue(state.value == 0)
        assertSame(before, host.frame(300, 180))
        host.close()
    }

    @Test
    fun viewportResizeRebuildsScene() {
        val host = UiSceneHost { width, height ->
            uiScene(width, height) {
                text(180f, 30f, "WAKE PARITY")
            }
        }

        val first = host.frame(640, 480)
        val resized = host.frame(800, 600)
        assertNotSame(first, resized)
        host.close()
    }

    @Test
    fun densityAwareHitTestingMatchesLogicalLayout() {
        var hits = 0
        val scene = uiScene(400, 240, density = 2f) {
            button(100f, 40f, Rgba(0.3f, 0.3f, 0.9f), "QUEUE") {
                hits++
            }
        }

        scene.frame()
        assertTrue(scene.click(150f, 50f))
        assertTrue(hits == 1)
        assertFalse(scene.click(250f, 50f))
        assertTrue(hits == 1)
    }
}
