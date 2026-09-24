package dev.yurie.display.composebridge

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import java.util.IdentityHashMap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.yurie.display.Rect
import dev.yurie.display.Rgba
import dev.yurie.display.scene.DisplayList
import dev.yurie.display.scene.DisplayListBuilder

class ComposeDisplayListCanvas(
    width: Int,
    height: Int,
    private val strict: Boolean = true,
) : Canvas {
    private val builder = DisplayListBuilder(width, height)
    private val restoreKinds = mutableListOf<Boolean>()
    private val unsupported = linkedSetOf<String>()
    private val imageIds = IdentityHashMap<ImageBitmap, Int>()
    private val pathIds = IdentityHashMap<Path, Int>()

    val unsupportedOperations: Set<String>
        get() = unsupported.toSet()

    fun build(): DisplayList {
        check(restoreKinds.isEmpty()) { "Unbalanced Compose Canvas save/restore" }
        if (strict && unsupported.isNotEmpty()) {
            error("Unsupported Compose Canvas operations: " + unsupported.joinToString())
        }
        return builder.build()
    }

    override fun save() {
        builder.save()
        restoreKinds += false
    }

    override fun restore() {
        if (restoreKinds.isEmpty()) return
        val layer = restoreKinds.removeAt(restoreKinds.lastIndex)
        if (layer) builder.endLayer()
        builder.restore()
    }

    override fun saveLayer(bounds: ComposeRect, paint: Paint) {
        builder.save()
        builder.beginLayer(paint.alpha)
        restoreKinds += true
        validatePaint("saveLayer", paint, allowAlpha = true)
    }

    override fun translate(dx: Float, dy: Float) {
        builder.translate(dx, dy)
    }

    override fun scale(sx: Float, sy: Float) {
        builder.scale(sx, sy)
    }

    override fun rotate(degrees: Float) {
        builder.rotate(degrees)
    }

    override fun skew(sx: Float, sy: Float) {
        builder.skew(sx, sy)
    }

    override fun concat(matrix: Matrix) {
        builder.concat(matrix.values)
    }

    override fun clipRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        clipOp: androidx.compose.ui.graphics.ClipOp,
    ) {
        if (clipOp != androidx.compose.ui.graphics.ClipOp.Intersect) {
            unsupported("clipRectDifference")
            return
        }
        builder.clipRect(Rect(left, top, right - left, bottom - top))
    }

    override fun clipPath(path: Path, clipOp: androidx.compose.ui.graphics.ClipOp) {
        if (clipOp != androidx.compose.ui.graphics.ClipOp.Intersect) {
            unsupported("clipPathDifference")
            return
        }
        builder.clipPath(registerPath(path))
    }

    override fun drawLine(p1: Offset, p2: Offset, paint: Paint) {
        if (!validateStrokePaint("drawLine", paint)) return
        builder.strokeLine(
            p1.x,
            p1.y,
            p2.x,
            p2.y,
            paint.strokeWidth,
            paintColor(paint),
        )
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        if (!validatePaint("drawRect", paint, allowAlpha = true)) return
        builder.fillRect(
            Rect(left, top, right - left, bottom - top),
            paintColor(paint),
        )
    }

    override fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusX: Float,
        radiusY: Float,
        paint: Paint,
    ) {
        if (!validatePaint("drawRoundRect", paint, allowAlpha = true)) return
        builder.fillRoundRect(
            Rect(left, top, right - left, bottom - top),
            radiusX,
            radiusY,
            paintColor(paint),
        )
    }

    override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        if (!validatePaint("drawOval", paint, allowAlpha = true)) return
        builder.fillOval(Rect(left, top, right - left, bottom - top), paintColor(paint))
    }

    override fun drawCircle(center: Offset, radius: Float, paint: Paint) {
        if (!validatePaint("drawCircle", paint, allowAlpha = true)) return
        builder.fillCircle(center.x, center.y, radius, paintColor(paint))
    }

    override fun drawArc(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        paint: Paint,
    ) {
        if (!validatePaint("drawArc", paint, allowAlpha = true)) return
        builder.fillArc(
            Rect(left, top, right - left, bottom - top),
            startAngle,
            sweepAngle,
            useCenter,
            paintColor(paint),
        )
    }

    override fun drawPath(path: Path, paint: Paint) {
        val pathId = registerPath(path)
        when (paint.style) {
            PaintingStyle.Fill -> {
                if (!validatePaint("drawPath", paint, allowAlpha = true)) return
                builder.fillPath(pathId, paintColor(paint))
            }
            PaintingStyle.Stroke -> {
                if (!validateStrokePaint("drawPath", paint)) return
                builder.strokePath(pathId, paint.strokeWidth, paintColor(paint))
            }
            else -> unsupported("drawPath:style")
        }
    }

    override fun drawImage(image: ImageBitmap, topLeftOffset: Offset, paint: Paint) {
        if (!validateImagePaint("drawImage", paint)) return
        val imageId = registerImage(image)
        builder.drawImage(
            imageId = imageId,
            source = Rect(0f, 0f, image.width.toFloat(), image.height.toFloat()),
            destination = Rect(
                topLeftOffset.x,
                topLeftOffset.y,
                image.width.toFloat(),
                image.height.toFloat(),
            ),
            opacity = paint.alpha,
        )
    }

    override fun drawImageRect(
        image: ImageBitmap,
        srcOffset: IntOffset,
        srcSize: IntSize,
        dstOffset: IntOffset,
        dstSize: IntSize,
        paint: Paint,
    ) {
        if (!validateImagePaint("drawImageRect", paint)) return
        val imageId = registerImage(image)
        builder.drawImage(
            imageId = imageId,
            source = Rect(
                srcOffset.x.toFloat(),
                srcOffset.y.toFloat(),
                srcSize.width.toFloat(),
                srcSize.height.toFloat(),
            ),
            destination = Rect(
                dstOffset.x.toFloat(),
                dstOffset.y.toFloat(),
                dstSize.width.toFloat(),
                dstSize.height.toFloat(),
            ),
            opacity = paint.alpha,
        )
    }

    override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint) {
        unsupported("drawPoints")
    }

    override fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: Paint) {
        unsupported("drawRawPoints")
    }

    override fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint) {
        unsupported("drawVertices")
    }

    override fun enableZ() {
        unsupported("enableZ")
    }

    override fun disableZ() = Unit

    private fun validatePaint(name: String, paint: Paint, allowAlpha: Boolean = false): Boolean {
        var ok = true
        if (paint.style != PaintingStyle.Fill) {
            unsupported(name + ":stroke")
            ok = false
        }
        if (paint.shader != null) {
            unsupported(name + ":shader")
            ok = false
        }
        if (paint.colorFilter != null) {
            unsupported(name + ":colorFilter")
            ok = false
        }
        if (paint.pathEffect != null) {
            unsupported(name + ":pathEffect")
            ok = false
        }
        if (paint.blendMode != BlendMode.SrcOver) {
            unsupported(name + ":blendMode")
            ok = false
        }
        if (!allowAlpha && paint.alpha != 1f) {
            unsupported(name + ":alpha")
            ok = false
        }
        return ok
    }

    private fun registerPath(path: Path): Int =
        pathIds[path] ?: run {
            val verbs = mutableListOf<dev.yurie.display.scene.PathVerb>()
            for (segment in path) {
                when (segment.type) {
                    PathSegment.Type.Move -> verbs += dev.yurie.display.scene.PathVerb.MoveTo(
                        segment.points[0],
                        segment.points[1],
                    )
                    PathSegment.Type.Line -> verbs += dev.yurie.display.scene.PathVerb.LineTo(
                        segment.points[2],
                        segment.points[3],
                    )
                    PathSegment.Type.Quadratic,
                    PathSegment.Type.Conic -> verbs += dev.yurie.display.scene.PathVerb.QuadraticTo(
                        segment.points[2],
                        segment.points[3],
                        segment.points[4],
                        segment.points[5],
                    )
                    PathSegment.Type.Cubic -> verbs += dev.yurie.display.scene.PathVerb.CubicTo(
                        segment.points[2],
                        segment.points[3],
                        segment.points[4],
                        segment.points[5],
                        segment.points[6],
                        segment.points[7],
                    )
                    PathSegment.Type.Close -> verbs += dev.yurie.display.scene.PathVerb.Close
                    PathSegment.Type.Done -> Unit
                }
            }
            if (verbs.isEmpty()) {
                unsupported("emptyPath")
                return@run 0
            }
            builder.path(
                dev.yurie.display.scene.PathResource(
                    verbs = verbs,
                    evenOdd = path.fillType == PathFillType.EvenOdd,
                )
            ).also { pathIds[path] = it }
        }

    private fun registerImage(image: ImageBitmap): Int =
        imageIds[image] ?: run {
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            val rgba = ByteArray(pixels.size * 4)
            pixels.forEachIndexed { index, argb ->
                val base = index * 4
                rgba[base] = ((argb ushr 16) and 0xFF).toByte()
                rgba[base + 1] = ((argb ushr 8) and 0xFF).toByte()
                rgba[base + 2] = (argb and 0xFF).toByte()
                rgba[base + 3] = ((argb ushr 24) and 0xFF).toByte()
            }
            builder.image(
                dev.yurie.display.scene.ImageResource(
                    width = image.width,
                    height = image.height,
                    rgba8 = rgba,
                )
            ).also { imageIds[image] = it }
        }

    private fun validateImagePaint(name: String, paint: Paint): Boolean {
        var ok = true
        if (paint.shader != null) {
            unsupported(name + ":shader")
            ok = false
        }
        if (paint.colorFilter != null) {
            unsupported(name + ":colorFilter")
            ok = false
        }
        if (paint.pathEffect != null) {
            unsupported(name + ":pathEffect")
            ok = false
        }
        if (paint.blendMode != BlendMode.SrcOver) {
            unsupported(name + ":blendMode")
            ok = false
        }
        return ok
    }

    private fun validateStrokePaint(name: String, paint: Paint): Boolean {
        var ok = true
        if (paint.shader != null) {
            unsupported(name + ":shader")
            ok = false
        }
        if (paint.colorFilter != null) {
            unsupported(name + ":colorFilter")
            ok = false
        }
        if (paint.pathEffect != null) {
            unsupported(name + ":pathEffect")
            ok = false
        }
        if (paint.blendMode != BlendMode.SrcOver) {
            unsupported(name + ":blendMode")
            ok = false
        }
        return ok
    }

    private fun paintColor(paint: Paint): Rgba {
        val color = paint.color
        val alpha = (color.alpha * paint.alpha).coerceIn(0f, 1f)
        return Rgba(
            color.red.coerceIn(0f, 1f),
            color.green.coerceIn(0f, 1f),
            color.blue.coerceIn(0f, 1f),
            alpha,
        )
    }

    private fun unsupported(name: String) {
        unsupported += name
        if (strict) error("Unsupported Compose Canvas operation: " + name)
    }
}
