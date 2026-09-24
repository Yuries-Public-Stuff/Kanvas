package dev.yurie.display

import dev.yurie.display.scene.ComposeCaptureParser
import dev.yurie.display.scene.DisplayCommand
import dev.yurie.display.scene.GpuPrimitiveLowerer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeCaptureParserTest {
    @Test
    fun parsesFrameIntoSemanticDisplayList() {
        val capture = """
            CAPTURE_START test
            FRAME_BEGIN 1 width=800 height=600 time=1
            CLEAR 1 color=4278190080
            SAVE 2
            TRANSLATE 3 dx=10.0 dy=20.0
            CLIP_RECT 4 l=0.0 t=0.0 r=300.0 b=200.0 mode=INTERSECT aa=true
            RECT 5 l=2.0 t=3.0 r=12.0 b=13.0 color=4294901760 mode=FILL stroke=1.0
            RRECT 6 l=20.0 t=30.0 r=120.0 b=80.0 radii=[8.0,8.0] color=4278255360 mode=FILL stroke=1.0
            OVAL 7 l=140.0 t=30.0 r=200.0 b=90.0 color=4294901760 mode=FILL stroke=1.0
            CIRCLE 8 x=230.0 y=60.0 radius=24.0 color=4278255360 mode=FILL stroke=1.0
            LINE 9 x1=20.0 y1=120.0 x2=200.0 y2=120.0 width=2.0 color=4294967295 mode=STROKE stroke=2.0
            STRING 10 x=40.0 y=100.0 text="Wake" family="Inter" size=16.0 color=4294967295 mode=FILL stroke=1.0
            RESTORE 11
            FRAME_END 1
        """.trimIndent()

        val frames = ComposeCaptureParser.parse(capture)
        assertEquals(1, frames.size)
        val frame = frames.single()
        assertEquals(800, frame.displayList.width)
        assertEquals(600, frame.displayList.height)
        assertTrue(frame.unsupportedOperations.isEmpty())
        assertTrue(frame.displayList.commands.any { it is DisplayCommand.FillRoundRect })
        assertTrue(frame.displayList.commands.any { it is DisplayCommand.FillOval })
        assertTrue(frame.displayList.commands.any { it is DisplayCommand.FillCircle })
        assertTrue(frame.displayList.commands.any { it is DisplayCommand.StrokeLine })
        assertTrue(frame.displayList.commands.any { it is DisplayCommand.DrawText })
        assertEquals("Inter", frame.displayList.resources.fonts.values.single().family)
    }

    @Test
    fun preservesUnsupportedOperationsForCapabilityReporting() {
        val capture = """
            FRAME_BEGIN 4 width=100 height=100 time=1
            OP 1 name=drawPath(Lorg/jetbrains/skia/Path;)Lorg/jetbrains/skia/Canvas;
            FRAME_END 4
        """.trimIndent()

        val frame = ComposeCaptureParser.parse(capture).single()
        assertFalse(frame.unsupportedOperations.isEmpty())
    }

    @Test
    fun gpuLowererHandlesCapturedRectRoundRectAndBasicText() {
        val capture = """
            FRAME_BEGIN 1 width=320 height=200 time=1
            CLEAR 1 color=4278190080
            RECT 2 l=10.0 t=10.0 r=100.0 b=40.0 color=4294901760 mode=FILL stroke=1.0
            RRECT 3 l=10.0 t=50.0 r=120.0 b=100.0 radii=[10.0,10.0] color=4278255360 mode=FILL stroke=1.0
            STRING 4 x=10.0 y=140.0 text="WAKE" family="Inter" size=18.0 color=4294967295 mode=FILL stroke=1.0
            FRAME_END 1
        """.trimIndent()

        val frame = ComposeCaptureParser.parse(capture).single()
        val lowered = GpuPrimitiveLowerer.lower(frame.displayList)
        assertTrue(lowered.unsupported.isEmpty())
        assertTrue(lowered.frame.commands.size > frame.displayList.commands.size)
        assertTrue(lowered.frame.commands.first() is DrawCommand.Clear)
    }
}
