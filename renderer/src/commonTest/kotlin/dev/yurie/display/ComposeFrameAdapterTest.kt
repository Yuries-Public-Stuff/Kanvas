package dev.yurie.display

import dev.yurie.display.compose.composeFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComposeFrameAdapterTest {
    private val red = Rgba(1f, 0f, 0f)

    @Test fun scaledAndClippedRect() {
        val result = composeFrame(400, 200, density = 2f) {
            clear(Rgba(0f, 0f, 0f))
            translate(10f, 5f) {
                clipRect(0f, 0f, 40f, 20f) {
                    drawRect(-5f, -5f, 30f, 30f, red)
                }
            }
        }
        assertEquals(2, result.commands.size)
        assertEquals(Rect(20f, 10f, 50f, 40f),
            (result.commands[1] as DrawCommand.FillRect).bounds)
    }

    @Test fun nestedClipRestoresOuterClip() {
        val result = composeFrame(100, 100) {
            clipRect(0f, 0f, 20f, 20f) {
                clipRect(0f, 0f, 5f, 5f) {
                    drawRect(0f, 0f, 10f, 10f, red)
                }
                drawRect(0f, 0f, 10f, 10f, red)
            }
            drawRect(0f, 0f, 30f, 30f, red)
        }
        assertEquals(listOf(Rect(0f, 0f, 5f, 5f), Rect(0f, 0f, 10f, 10f),
            Rect(0f, 0f, 30f, 30f)), result.commands.map { (it as DrawCommand.FillRect).bounds })
    }

    @Test fun scopeRestoresAfterFailure() {
        val adapter = dev.yurie.display.compose.ComposeFrameAdapter(100, 100)
        assertFailsWith<IllegalStateException> {
            adapter.translate(20f, 20f) { throw IllegalStateException("test") }
        }
        adapter.drawRect(0f, 0f, 10f, 10f, red)
        assertEquals(Rect(0f, 0f, 10f, 10f),
            (adapter.build().commands.single() as DrawCommand.FillRect).bounds)
    }

    @Test fun invalidCommandsFailExplicitly() {
        assertFailsWith<IllegalArgumentException> { composeFrame(100, 100, 0f) {} }
        assertFailsWith<IllegalStateException> {
            composeFrame(100, 100) {
                drawRect(0f, 0f, 10f, 10f, red)
                clear(red)
            }
        }
    }

    @Test fun legacyDirect3dIsWindowsOnly() {
        val availability = BackendAvailability(direct3d9 = true)
        assertEquals(GraphicsApi.DIRECT3D9,
            PlatformBackendSelector.select(HostPlatform.WINDOWS, availability))
        assertFailsWith<IllegalStateException> {
            PlatformBackendSelector.select(HostPlatform.LINUX, availability)
        }
        assertFailsWith<IllegalStateException> {
            PlatformBackendSelector.select(HostPlatform.MACOS, availability, GraphicsApi.DIRECT3D9)
        }
    }
}
