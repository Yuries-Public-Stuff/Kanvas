package dev.yurie.display

import dev.yurie.display.scene.DisplayCommand
import dev.yurie.display.scene.GpuPrimitiveLowerer
import dev.yurie.display.scene.ImageResource
import dev.yurie.display.scene.PathResource
import dev.yurie.display.scene.PathVerb
import dev.yurie.display.scene.displayList
import kotlin.test.Test
import kotlin.test.assertTrue

class GpuPrimitiveLowererTest {
    @Test
    fun lowersComposePrimitivesIntoGpuRectangleAbi() {
        val list = displayList(96, 96) {
            clear(Rgba(0.05f, 0.05f, 0.05f, 1f))

            val image = image(
                ImageResource(
                    width = 2,
                    height = 2,
                    rgba8 = byteArrayOf(
                        255.toByte(), 0, 0, 255.toByte(),
                        0, 255.toByte(), 0, 255.toByte(),
                        0, 0, 255.toByte(), 255.toByte(),
                        255.toByte(), 255.toByte(), 255.toByte(), 128.toByte(),
                    ),
                )
            )
            val triangle = path(
                PathResource(
                    listOf(
                        PathVerb.MoveTo(5f, 5f),
                        PathVerb.LineTo(30f, 5f),
                        PathVerb.LineTo(18f, 28f),
                        PathVerb.Close,
                    )
                )
            )

            saved {
                translate(8f, 6f)
                rotate(12f)
                fillPath(triangle, Rgba(1f, 0.2f, 0.2f, 0.7f))
            }

            drawImage(
                imageId = image,
                source = Rect(0f, 0f, 2f, 2f),
                destination = Rect(45f, 8f, 32f, 32f),
                opacity = 0.8f,
            )

            layer(0.5f) {
                fillCircle(32f, 65f, 12f, Rgba(0.2f, 0.7f, 1f, 0.8f))
                fillArc(
                    Rect(55f, 52f, 28f, 24f),
                    0f,
                    220f,
                    true,
                    Rgba(1f, 0.8f, 0.1f, 0.9f),
                )
            }
        }

        val lowered = GpuPrimitiveLowerer.lower(list)

        assertTrue(lowered.unsupported.isEmpty())
        assertTrue(lowered.frame.commands.first() is DrawCommand.Clear)
        assertTrue(lowered.frame.commands.count { it is DrawCommand.FillRect } > 8)
        assertTrue(
            lowered.frame.commands
                .filterIsInstance<DrawCommand.FillRect>()
                .any { it.color.alpha < 1f }
        )
    }
}
