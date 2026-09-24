package dev.yurie.display.composebridge

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.Paint
import dev.yurie.display.scene.DisplayCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposeDisplayListCanvasTest {
    @Test
    fun capturesSolidComposeRect() {
        val canvas = ComposeDisplayListCanvas(320, 200)
        val paint = Paint().apply {
            color = Color(0xFF3366CC)
        }

        canvas.drawRect(10f, 20f, 110f, 70f, paint)
        val list = canvas.build()

        assertEquals(1, list.commands.size)
        assertTrue(list.commands.single() is DisplayCommand.FillRect)
    }

    @Test
    fun capturesSaveTransformAndRoundRect() {
        val canvas = ComposeDisplayListCanvas(320, 200)
        val paint = Paint().apply {
            color = Color(0xFFFFFFFF)
        }

        canvas.save()
        canvas.translate(8f, 12f)
        canvas.drawRoundRect(0f, 0f, 40f, 30f, 6f, 6f, paint)
        canvas.restore()

        val commands = canvas.build().commands
        assertEquals(DisplayCommand.Save, commands[0])
        assertTrue(commands[1] is DisplayCommand.Translate)
        assertTrue(commands[2] is DisplayCommand.FillRoundRect)
        assertEquals(DisplayCommand.Restore, commands[3])
    }

    @Test
    fun compatibilityModeReportsOnlyActuallyUnsupportedCalls() {
        val canvas = ComposeDisplayListCanvas(100, 100, strict = false)
        val paint = Paint()
        canvas.drawCircle(androidx.compose.ui.geometry.Offset(20f, 20f), 10f, paint)
        canvas.drawPoints(
            androidx.compose.ui.graphics.PointMode.Points,
            listOf(androidx.compose.ui.geometry.Offset(5f, 5f)),
            paint,
        )

        assertTrue(canvas.unsupportedOperations.none { it == "drawCircle" })
        assertTrue("drawPoints" in canvas.unsupportedOperations)
        canvas.build()
    }

    @Test
    fun capturesTransformsAndBasicShapesWithoutSkiaFallback() {
        val canvas = ComposeDisplayListCanvas(320, 200, strict = true)
        val fill = Paint().apply {
            color = Color(0xFF55AA88)
        }
        val stroke = Paint().apply {
            color = Color(0xFFFFFFFF)
            strokeWidth = 2f
        }

        canvas.save()
        canvas.translate(4f, 8f)
        canvas.rotate(15f)
        canvas.drawOval(10f, 10f, 60f, 40f, fill)
        canvas.drawCircle(androidx.compose.ui.geometry.Offset(80f, 40f), 12f, fill)
        canvas.drawLine(
            androidx.compose.ui.geometry.Offset(0f, 0f),
            androidx.compose.ui.geometry.Offset(100f, 50f),
            stroke,
        )
        canvas.restore()

        val commands = canvas.build().commands
        assertTrue(commands.any { it is DisplayCommand.Rotate })
        assertTrue(commands.any { it is DisplayCommand.FillOval })
        assertTrue(commands.any { it is DisplayCommand.FillCircle })
        assertTrue(commands.any { it is DisplayCommand.StrokeLine })
    }


    @Test
    fun capturesImageBitmapPixelsAndCrop() {
        val image = object : ImageBitmap {
            override val width: Int = 2
            override val height: Int = 1
            override val colorSpace: ColorSpace = ColorSpaces.Srgb
            override val hasAlpha: Boolean = true
            override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

            override fun readPixels(
                buffer: IntArray,
                startX: Int,
                startY: Int,
                width: Int,
                height: Int,
                bufferOffset: Int,
                stride: Int,
            ) {
                buffer[bufferOffset] = 0xFFFF0000.toInt()
                if (width > 1) buffer[bufferOffset + 1] = 0x8000FF00.toInt()
            }

            override fun prepareToDraw() = Unit
        }
        val canvas = ComposeDisplayListCanvas(100, 100)
        val paint = Paint()

        canvas.drawImageRect(
            image = image,
            srcOffset = androidx.compose.ui.unit.IntOffset(0, 0),
            srcSize = androidx.compose.ui.unit.IntSize(2, 1),
            dstOffset = androidx.compose.ui.unit.IntOffset(10, 20),
            dstSize = androidx.compose.ui.unit.IntSize(40, 20),
            paint = paint,
        )

        val list = canvas.build()
        val command = list.commands.single() as DisplayCommand.DrawImage
        val resource = list.resources.images.getValue(command.imageId)
        assertEquals(2, resource.width)
        assertEquals(1, resource.height)
        assertEquals(8, resource.rgba8.size)
        assertEquals(255.toByte(), resource.rgba8[0])
        assertEquals(0.toByte(), resource.rgba8[1])
        assertEquals(0.toByte(), resource.rgba8[2])
        assertEquals(255.toByte(), resource.rgba8[3])
        assertEquals(dev.yurie.display.Rect(10f, 20f, 40f, 20f), command.destination)
    }

}