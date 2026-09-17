package dev.yurie.display

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RendererTest {
    @Test fun vulkanIsPreferred() {
        assertEquals(GraphicsApi.VULKAN, BackendSelector.select(
            BackendAvailability(vulkan = true, openGl = true, direct3d12 = true)))
    }

    @Test fun openGlFallback() {
        assertEquals(GraphicsApi.OPENGL, BackendSelector.select(
            BackendAvailability(openGl = true, direct3d12 = true)))
    }

    @Test fun direct3dCanBeExplicitlySelected() {
        assertEquals(GraphicsApi.DIRECT3D12, BackendSelector.select(
            BackendAvailability(vulkan = true, direct3d12 = true), GraphicsApi.DIRECT3D12))
    }

    @Test fun noFakeDriverFallback() {
        assertFailsWith<IllegalStateException> { BackendSelector.select(BackendAvailability()) }
    }

    @Test fun recordingCopiesFrameAndRejectsReuseAfterClose() {
        val commands = mutableListOf<DrawCommand>(DrawCommand.Clear(Rgba(0f, 0f, 0f)))
        val renderer = RecordingRenderer()
        renderer.render(Frame(800, 600, commands))
        commands.clear()
        assertEquals(1, renderer.frames.single().commands.size)
        renderer.close()
        assertFailsWith<IllegalStateException> {
            renderer.render(Frame(800, 600, emptyList()))
        }
    }

    @Test fun invalidGeometryRejected() {
        assertFailsWith<IllegalArgumentException> { Rect(0f, 0f, -1f, 20f) }
        assertFailsWith<IllegalArgumentException> { Frame(0, 10, emptyList()) }
        assertFailsWith<IllegalArgumentException> { Rgba(Float.NaN, 0f, 0f) }
    }
}
