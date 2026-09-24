package dev.yurie.display.scene

import dev.yurie.display.Rect
import dev.yurie.display.Rgba

data class DisplayList(
    val width: Int,
    val height: Int,
    val commands: List<DisplayCommand>,
    val resources: DisplayResources = DisplayResources(),
) {
    init {
        require(width > 0 && height > 0)
        DisplayListValidator.validate(commands, resources)
    }
}

data class TransformMatrix(val values: List<Float>) {
    init {
        require(values.size == 16) { "Transform matrix must contain 16 values" }
        require(values.all(Float::isFinite)) { "Transform matrix values must be finite" }
    }
}

sealed interface DisplayCommand {
    data class Clear(val color: Rgba) : DisplayCommand

    data object Save : DisplayCommand
    data object Restore : DisplayCommand

    data class Translate(val x: Float, val y: Float) : DisplayCommand
    data class Scale(val x: Float, val y: Float) : DisplayCommand
    data class Rotate(val degrees: Float) : DisplayCommand
    data class Skew(val xDegrees: Float, val yDegrees: Float) : DisplayCommand
    data class Concat(val matrix: TransformMatrix) : DisplayCommand

    data class ClipRect(val bounds: Rect) : DisplayCommand
    data class ClipPath(val pathId: Int) : DisplayCommand

    data class FillRect(val bounds: Rect, val color: Rgba) : DisplayCommand
    data class FillRoundRect(
        val bounds: Rect,
        val radiusX: Float,
        val radiusY: Float,
        val color: Rgba,
    ) : DisplayCommand
    data class FillOval(val bounds: Rect, val color: Rgba) : DisplayCommand
    data class FillCircle(val centerX: Float, val centerY: Float, val radius: Float, val color: Rgba) : DisplayCommand
    data class StrokeLine(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val width: Float,
        val color: Rgba,
    ) : DisplayCommand
    data class FillArc(
        val bounds: Rect,
        val startAngle: Float,
        val sweepAngle: Float,
        val useCenter: Boolean,
        val color: Rgba,
    ) : DisplayCommand
    data class FillPath(val pathId: Int, val color: Rgba) : DisplayCommand
    data class StrokePath(val pathId: Int, val width: Float, val color: Rgba) : DisplayCommand

    data class DrawImage(
        val imageId: Int,
        val source: Rect,
        val destination: Rect,
        val opacity: Float = 1f,
    ) : DisplayCommand

    data class DrawText(
        val fontId: Int,
        val text: String,
        val x: Float,
        val baselineY: Float,
        val color: Rgba,
    ) : DisplayCommand

    data class DrawGlyphRun(
        val fontId: Int,
        val glyphIds: IntArray,
        val positions: FloatArray,
        val color: Rgba,
    ) : DisplayCommand {
        init {
            require(glyphIds.size * 2 == positions.size) {
                "Glyph positions must contain x/y for each glyph"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is DrawGlyphRun && fontId == other.fontId &&
                glyphIds.contentEquals(other.glyphIds) &&
                positions.contentEquals(other.positions) &&
                color == other.color

        override fun hashCode(): Int {
            var result = fontId
            result = 31 * result + glyphIds.contentHashCode()
            result = 31 * result + positions.contentHashCode()
            result = 31 * result + color.hashCode()
            return result
        }
    }

    data class BeginLayer(val opacity: Float = 1f) : DisplayCommand
    data object EndLayer : DisplayCommand
}

data class ImageResource(
    val width: Int,
    val height: Int,
    val rgba8: ByteArray,
) {
    init {
        require(width > 0 && height > 0)
        require(rgba8.size == width * height * 4) {
            "RGBA8 image byte count must equal width * height * 4"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ImageResource && width == other.width && height == other.height &&
            rgba8.contentEquals(other.rgba8)

    override fun hashCode(): Int =
        31 * (31 * width + height) + rgba8.contentHashCode()
}

data class FontResource(
    val family: String,
    val sizePx: Float,
    val weight: Int = 400,
    val italic: Boolean = false,
) {
    init {
        require(family.isNotBlank())
        require(sizePx.isFinite() && sizePx > 0f)
        require(weight in 1..1000)
    }
}

sealed interface PathVerb {
    data class MoveTo(val x: Float, val y: Float) : PathVerb
    data class LineTo(val x: Float, val y: Float) : PathVerb
    data class QuadraticTo(val cx: Float, val cy: Float, val x: Float, val y: Float) : PathVerb
    data class CubicTo(
        val c1x: Float,
        val c1y: Float,
        val c2x: Float,
        val c2y: Float,
        val x: Float,
        val y: Float,
    ) : PathVerb
    data object Close : PathVerb
}

data class PathResource(
    val verbs: List<PathVerb>,
    val evenOdd: Boolean = false,
) {
    init {
        require(verbs.isNotEmpty()) { "Path resource cannot be empty" }
        verbs.forEach { verb ->
            val values = when (verb) {
                is PathVerb.MoveTo -> listOf(verb.x, verb.y)
                is PathVerb.LineTo -> listOf(verb.x, verb.y)
                is PathVerb.QuadraticTo -> listOf(verb.cx, verb.cy, verb.x, verb.y)
                is PathVerb.CubicTo -> listOf(
                    verb.c1x, verb.c1y, verb.c2x, verb.c2y, verb.x, verb.y,
                )
                PathVerb.Close -> emptyList()
            }
            require(values.all(Float::isFinite)) { "Path coordinates must be finite" }
        }
    }
}

data class DisplayResources(
    val images: Map<Int, ImageResource> = emptyMap(),
    val fonts: Map<Int, FontResource> = emptyMap(),
    val paths: Map<Int, PathResource> = emptyMap(),
)

object DisplayListValidator {
    fun validate(commands: List<DisplayCommand>, resources: DisplayResources) {
        var saveDepth = 0
        var layerDepth = 0

        commands.forEach { command ->
            when (command) {
                DisplayCommand.Save -> saveDepth++
                DisplayCommand.Restore -> {
                    require(saveDepth > 0) { "Restore without Save" }
                    saveDepth--
                }

                is DisplayCommand.Translate -> finite(command.x, command.y)
                is DisplayCommand.Scale -> {
                    finite(command.x, command.y)
                    require(command.x != 0f && command.y != 0f) { "Scale cannot be zero" }
                }
                is DisplayCommand.Rotate -> finite(command.degrees)
                is DisplayCommand.Skew -> finite(command.xDegrees, command.yDegrees)
                is DisplayCommand.Concat -> Unit

                is DisplayCommand.FillRoundRect -> {
                    finite(command.radiusX, command.radiusY)
                    require(command.radiusX >= 0f && command.radiusY >= 0f)
                }
                is DisplayCommand.FillCircle -> {
                    finite(command.centerX, command.centerY, command.radius)
                    require(command.radius >= 0f)
                }
                is DisplayCommand.StrokeLine -> {
                    finite(command.x1, command.y1, command.x2, command.y2, command.width)
                    require(command.width >= 0f)
                }
                is DisplayCommand.FillArc -> finite(command.startAngle, command.sweepAngle)
                is DisplayCommand.ClipPath -> require(command.pathId in resources.paths) { "Missing clip path resource" }
                is DisplayCommand.FillPath -> require(command.pathId in resources.paths) { "Missing fill path resource" }
                is DisplayCommand.StrokePath -> {
                    require(command.pathId in resources.paths) { "Missing stroke path resource" }
                    require(command.width.isFinite() && command.width >= 0f)
                }

                is DisplayCommand.DrawImage -> {
                    val image = resources.images[command.imageId]
                    require(image != null) { "Missing image resource" }
                    require(command.opacity.isFinite() && command.opacity in 0f..1f)
                    require(command.source.x >= 0f && command.source.y >= 0f)
                    require(command.source.x + command.source.width <= image.width.toFloat())
                    require(command.source.y + command.source.height <= image.height.toFloat())
                }
                is DisplayCommand.DrawText -> {
                    require(command.fontId in resources.fonts) { "Missing font resource" }
                    require(command.text.isNotEmpty()) { "Text command cannot be empty" }
                    finite(command.x, command.baselineY)
                }
                is DisplayCommand.DrawGlyphRun -> {
                    require(command.fontId in resources.fonts) { "Missing font resource" }
                    require(command.positions.all(Float::isFinite))
                }

                is DisplayCommand.BeginLayer -> {
                    require(command.opacity.isFinite() && command.opacity in 0f..1f)
                    layerDepth++
                }
                DisplayCommand.EndLayer -> {
                    require(layerDepth > 0) { "EndLayer without BeginLayer" }
                    layerDepth--
                }

                is DisplayCommand.Clear,
                is DisplayCommand.ClipRect,
                is DisplayCommand.FillRect,
                is DisplayCommand.FillOval -> Unit
            }
        }

        require(saveDepth == 0) { "Unbalanced Save/Restore" }
        require(layerDepth == 0) { "Unbalanced BeginLayer/EndLayer" }
    }

    private fun finite(vararg values: Float) {
        require(values.all(Float::isFinite)) { "Non-finite display-list value" }
    }
}
