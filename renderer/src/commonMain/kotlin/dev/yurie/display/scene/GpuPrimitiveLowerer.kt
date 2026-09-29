package dev.yurie.display.scene

import dev.yurie.display.DrawCommand
import dev.yurie.display.Frame
import dev.yurie.display.Rect
import dev.yurie.display.Rgba
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Lowers display-list ops into the rectangle ABI.
object GpuPrimitiveLowerer {
    private const val MAX_COMMANDS = 65536
    private const val CURVE_STEPS = 16
    private const val OVAL_STEPS = 40

    private data class Point(val x: Float, val y: Float)

    private data class State(
        val a: Float = 1f,
        val b: Float = 0f,
        val c: Float = 0f,
        val d: Float = 1f,
        val tx: Float = 0f,
        val ty: Float = 0f,
        val clip: Rect? = null,
        val opacity: Float = 1f,
    )

    data class Result(
        val frame: Frame,
        val unsupported: Set<String>,
    )

    fun lower(displayList: DisplayList): Result {
        var state = State(
            clip = Rect(0f, 0f, displayList.width.toFloat(), displayList.height.toFloat()),
        )
        val stack = mutableListOf<State>()
        val layerOpacity = mutableListOf<Float>()
        val out = mutableListOf<DrawCommand>()
        val unsupported = linkedSetOf<String>()

        fun colorWithOpacity(color: Rgba, opacity: Float = state.opacity): Rgba =
            Rgba(
                color.red,
                color.green,
                color.blue,
                (color.alpha * opacity).coerceIn(0f, 1f),
            )

        fun transform(point: Point): Point =
            Point(
                x = state.a * point.x + state.c * point.y + state.tx,
                y = state.b * point.x + state.d * point.y + state.ty,
            )

        fun intersect(a: Rect?, b: Rect): Rect? {
            if (a == null) return null
            val left = max(a.x, b.x)
            val top = max(a.y, b.y)
            val right = min(a.x + a.width, b.x + b.width)
            val bottom = min(a.y + a.height, b.y + b.height)
            return if (right <= left || bottom <= top) null
            else Rect(left, top, right - left, bottom - top)
        }

        fun emitDeviceRect(rect: Rect, color: Rgba) {
            if (out.size >= MAX_COMMANDS) {
                unsupported += "CommandBudget"
                return
            }
            val clipped = intersect(state.clip, rect) ?: return
            if (clipped.width <= 0f || clipped.height <= 0f || color.alpha <= 0f) return
            out += DrawCommand.FillRect(clipped, color)
        }

        fun axisAligned(): Boolean = abs(state.b) < 0.0001f && abs(state.c) < 0.0001f

        fun transformedRect(rect: Rect): Rect {
            val p0 = transform(Point(rect.x, rect.y))
            val p1 = transform(Point(rect.x + rect.width, rect.y + rect.height))
            return Rect(
                min(p0.x, p1.x),
                min(p0.y, p1.y),
                abs(p1.x - p0.x),
                abs(p1.y - p0.y),
            )
        }

        fun emitPolygon(local: List<Point>, color: Rgba) {
            if (local.size < 3) return
            val points = local.map(::transform)
            val minY = floor(points.minOf { it.y }).toInt()
            val maxY = ceil(points.maxOf { it.y }).toInt()

            for (row in minY until maxY) {
                if (out.size >= MAX_COMMANDS) {
                    unsupported += "CommandBudget"
                    return
                }
                val sampleY = row + 0.5f
                val intersections = mutableListOf<Float>()

                for (index in points.indices) {
                    val p0 = points[index]
                    val p1 = points[(index + 1) % points.size]
                    if (p0.y == p1.y) continue
                    val low = min(p0.y, p1.y)
                    val high = max(p0.y, p1.y)
                    if (sampleY < low || sampleY >= high) continue
                    val t = (sampleY - p0.y) / (p1.y - p0.y)
                    intersections += p0.x + (p1.x - p0.x) * t
                }

                intersections.sort()
                var index = 0
                while (index + 1 < intersections.size) {
                    val left = intersections[index]
                    val right = intersections[index + 1]
                    if (right > left) {
                        emitDeviceRect(
                            Rect(left, row.toFloat(), right - left, 1f),
                            color,
                        )
                    }
                    index += 2
                }
            }
        }

        fun emitRect(rect: Rect, color: Rgba) {
            val resolved = colorWithOpacity(color)
            if (axisAligned()) {
                emitDeviceRect(transformedRect(rect), resolved)
            } else {
                emitPolygon(
                    listOf(
                        Point(rect.x, rect.y),
                        Point(rect.x + rect.width, rect.y),
                        Point(rect.x + rect.width, rect.y + rect.height),
                        Point(rect.x, rect.y + rect.height),
                    ),
                    resolved,
                )
            }
        }

        fun emitOval(bounds: Rect, color: Rgba) {
            if (bounds.width <= 0f || bounds.height <= 0f) return
            val centerX = bounds.x + bounds.width / 2f
            val centerY = bounds.y + bounds.height / 2f
            val radiusX = bounds.width / 2f
            val radiusY = bounds.height / 2f
            val points = (0 until OVAL_STEPS).map { index ->
                val angle = index.toFloat() / OVAL_STEPS * (PI * 2.0).toFloat()
                Point(
                    centerX + cos(angle) * radiusX,
                    centerY + sin(angle) * radiusY,
                )
            }
            emitPolygon(points, colorWithOpacity(color))
        }

        fun emitRoundRect(command: DisplayCommand.FillRoundRect) {
            val b = command.bounds
            val rx = min(command.radiusX, b.width / 2f)
            val ry = min(command.radiusY, b.height / 2f)
            if (rx <= 0f || ry <= 0f) {
                emitRect(b, command.color)
                return
            }

            val points = mutableListOf<Point>()
            fun corner(cx: Float, cy: Float, start: Float) {
                repeat(8) { step ->
                    val angle = start + step / 7f * (PI / 2.0).toFloat()
                    points += Point(
                        cx + cos(angle) * rx,
                        cy + sin(angle) * ry,
                    )
                }
            }

            corner(b.x + b.width - rx, b.y + ry, -(PI / 2.0).toFloat())
            corner(b.x + b.width - rx, b.y + b.height - ry, 0f)
            corner(b.x + rx, b.y + b.height - ry, (PI / 2.0).toFloat())
            corner(b.x + rx, b.y + ry, PI.toFloat())
            emitPolygon(points, colorWithOpacity(command.color))
        }

        fun emitLine(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            width: Float,
            color: Rgba,
        ) {
            val resolvedWidth = max(1f, width)
            val p0 = transform(Point(x1, y1))
            val p1 = transform(Point(x2, y2))
            val dx = p1.x - p0.x
            val dy = p1.y - p0.y
            val steps = max(1, ceil(max(abs(dx), abs(dy))).toInt())
            val scaledWidth = max(
                1f,
                resolvedWidth * max(
                    sqrt(state.a * state.a + state.b * state.b),
                    sqrt(state.c * state.c + state.d * state.d),
                ),
            )
            val resolvedColor = colorWithOpacity(color)

            repeat(steps + 1) { index ->
                val t = index.toFloat() / steps
                val x = p0.x + dx * t
                val y = p0.y + dy * t
                emitDeviceRect(
                    Rect(
                        x - scaledWidth / 2f,
                        y - scaledWidth / 2f,
                        scaledWidth,
                        scaledWidth,
                    ),
                    resolvedColor,
                )
            }
        }

        fun flattenPath(path: PathResource): List<List<Point>> {
            val subpaths = mutableListOf<MutableList<Point>>()
            var current = mutableListOf<Point>()
            var cursor = Point(0f, 0f)
            var start = Point(0f, 0f)

            fun ensurePath() {
                if (current.isEmpty()) {
                    current = mutableListOf()
                    subpaths += current
                }
            }

            path.verbs.forEach { verb ->
                when (verb) {
                    is PathVerb.MoveTo -> {
                        current = mutableListOf()
                        subpaths += current
                        cursor = Point(verb.x, verb.y)
                        start = cursor
                        current += cursor
                    }
                    is PathVerb.LineTo -> {
                        ensurePath()
                        cursor = Point(verb.x, verb.y)
                        current += cursor
                    }
                    is PathVerb.QuadraticTo -> {
                        ensurePath()
                        val from = cursor
                        repeat(CURVE_STEPS) { step ->
                            val t = (step + 1).toFloat() / CURVE_STEPS
                            val mt = 1f - t
                            current += Point(
                                mt * mt * from.x + 2f * mt * t * verb.cx + t * t * verb.x,
                                mt * mt * from.y + 2f * mt * t * verb.cy + t * t * verb.y,
                            )
                        }
                        cursor = Point(verb.x, verb.y)
                    }
                    is PathVerb.CubicTo -> {
                        ensurePath()
                        val from = cursor
                        repeat(CURVE_STEPS) { step ->
                            val t = (step + 1).toFloat() / CURVE_STEPS
                            val mt = 1f - t
                            current += Point(
                                mt * mt * mt * from.x +
                                    3f * mt * mt * t * verb.c1x +
                                    3f * mt * t * t * verb.c2x +
                                    t * t * t * verb.x,
                                mt * mt * mt * from.y +
                                    3f * mt * mt * t * verb.c1y +
                                    3f * mt * t * t * verb.c2y +
                                    t * t * t * verb.y,
                            )
                        }
                        cursor = Point(verb.x, verb.y)
                    }
                    PathVerb.Close -> {
                        if (current.isNotEmpty() && current.last() != start) current += start
                        cursor = start
                    }
                }
            }
            return subpaths.filter { it.size >= 2 }
        }

        fun pathBounds(path: PathResource): Rect? {
            val points = flattenPath(path).flatten()
            if (points.isEmpty()) return null
            val transformed = points.map(::transform)
            val left = transformed.minOf { it.x }
            val top = transformed.minOf { it.y }
            val right = transformed.maxOf { it.x }
            val bottom = transformed.maxOf { it.y }
            return Rect(left, top, right - left, bottom - top)
        }

        fun emitImage(command: DisplayCommand.DrawImage) {
            val image = displayList.resources.images[command.imageId] ?: return
            if (command.source.width <= 0f || command.source.height <= 0f ||
                command.destination.width <= 0f || command.destination.height <= 0f) return
            val srcLeft = floor(command.source.x).toInt().coerceIn(0, image.width - 1)
            val srcTop = floor(command.source.y).toInt().coerceIn(0, image.height - 1)
            val srcRight = ceil(command.source.x + command.source.width)
                .toInt().coerceIn(srcLeft + 1, image.width)
            val srcBottom = ceil(command.source.y + command.source.height)
                .toInt().coerceIn(srcTop + 1, image.height)

            val sourcePixels = max(1, (srcRight - srcLeft) * (srcBottom - srcTop))
            val available = max(1, min(1024, MAX_COMMANDS - out.size - 1))
            val stride = max(1, ceil(sqrt(sourcePixels.toFloat() / available)).toInt())
            val dest = command.destination

            var sy = srcTop
            while (sy < srcBottom && out.size < MAX_COMMANDS) {
                var sx = srcLeft
                while (sx < srcRight && out.size < MAX_COMMANDS) {
                    val sampleX = min(srcRight - 1, sx + stride / 2)
                    val sampleY = min(srcBottom - 1, sy + stride / 2)
                    val pixel = (sampleY * image.width + sampleX) * 4
                    val red = (image.rgba8[pixel].toInt() and 0xff) / 255f
                    val green = (image.rgba8[pixel + 1].toInt() and 0xff) / 255f
                    val blue = (image.rgba8[pixel + 2].toInt() and 0xff) / 255f
                    val alpha = (image.rgba8[pixel + 3].toInt() and 0xff) / 255f
                    if (alpha > 0f) {
                        val blockRight = min(srcRight, sx + stride)
                        val blockBottom = min(srcBottom, sy + stride)
                        val u0 = (sx - command.source.x) / command.source.width
                        val v0 = (sy - command.source.y) / command.source.height
                        val u1 = (blockRight - command.source.x) / command.source.width
                        val v1 = (blockBottom - command.source.y) / command.source.height
                        emitRect(
                            Rect(
                                dest.x + dest.width * u0,
                                dest.y + dest.height * v0,
                                dest.width * (u1 - u0),
                                dest.height * (v1 - v0),
                            ),
                            Rgba(
                                red,
                                green,
                                blue,
                                alpha * command.opacity,
                            ),
                        )
                    }
                    sx += stride
                }
                sy += stride
            }
        }

        displayList.commands.forEach { command ->
            when (command) {
                is DisplayCommand.Clear -> out += DrawCommand.Clear(colorWithOpacity(command.color, 1f))

                DisplayCommand.Save -> stack += state
                DisplayCommand.Restore -> {
                    if (stack.isNotEmpty()) state = stack.removeAt(stack.lastIndex)
                }

                is DisplayCommand.Translate -> {
                    state = state.copy(
                        tx = state.tx + state.a * command.x + state.c * command.y,
                        ty = state.ty + state.b * command.x + state.d * command.y,
                    )
                }

                is DisplayCommand.Scale -> {
                    state = state.copy(
                        a = state.a * command.x,
                        b = state.b * command.x,
                        c = state.c * command.y,
                        d = state.d * command.y,
                    )
                }

                is DisplayCommand.Rotate -> {
                    val radians = command.degrees / 180f * PI.toFloat()
                    val cs = cos(radians)
                    val sn = sin(radians)
                    state = state.copy(
                        a = state.a * cs + state.c * sn,
                        b = state.b * cs + state.d * sn,
                        c = -state.a * sn + state.c * cs,
                        d = -state.b * sn + state.d * cs,
                    )
                }

                is DisplayCommand.Skew -> {
                    val kx = command.xDegrees
                    val ky = command.yDegrees
                    state = state.copy(
                        a = state.a + state.c * ky,
                        b = state.b + state.d * ky,
                        c = state.a * kx + state.c,
                        d = state.b * kx + state.d,
                    )
                }

                is DisplayCommand.Concat -> {
                    val m = command.matrix.values
                    val ma = m[0]
                    val mb = m[1]
                    val mc = m[4]
                    val md = m[5]
                    val mtx = m[12]
                    val mty = m[13]
                    state = state.copy(
                        a = state.a * ma + state.c * mb,
                        b = state.b * ma + state.d * mb,
                        c = state.a * mc + state.c * md,
                        d = state.b * mc + state.d * md,
                        tx = state.a * mtx + state.c * mty + state.tx,
                        ty = state.b * mtx + state.d * mty + state.ty,
                    )
                }

                is DisplayCommand.ClipRect -> {
                    val rect = if (axisAligned()) {
                        transformedRect(command.bounds)
                    } else {
                        val points = listOf(
                            Point(command.bounds.x, command.bounds.y),
                            Point(command.bounds.x + command.bounds.width, command.bounds.y),
                            Point(command.bounds.x + command.bounds.width, command.bounds.y + command.bounds.height),
                            Point(command.bounds.x, command.bounds.y + command.bounds.height),
                        ).map(::transform)
                        Rect(
                            points.minOf { it.x },
                            points.minOf { it.y },
                            points.maxOf { it.x } - points.minOf { it.x },
                            points.maxOf { it.y } - points.minOf { it.y },
                        )
                    }
                    state = state.copy(clip = intersect(state.clip, rect))
                }

                is DisplayCommand.ClipPath -> {
                    val path = displayList.resources.paths[command.pathId]
                    val bounds = path?.let(::pathBounds)
                    if (bounds != null) state = state.copy(clip = intersect(state.clip, bounds))
                }

                is DisplayCommand.FillRect -> emitRect(command.bounds, command.color)
                is DisplayCommand.FillRoundRect -> emitRoundRect(command)
                is DisplayCommand.FillOval -> emitOval(command.bounds, command.color)
                is DisplayCommand.FillCircle -> emitOval(
                    Rect(
                        command.centerX - command.radius,
                        command.centerY - command.radius,
                        command.radius * 2f,
                        command.radius * 2f,
                    ),
                    command.color,
                )

                is DisplayCommand.StrokeLine -> emitLine(
                    command.x1,
                    command.y1,
                    command.x2,
                    command.y2,
                    command.width,
                    command.color,
                )

                is DisplayCommand.FillArc -> {
                    val points = mutableListOf<Point>()
                    val centerX = command.bounds.x + command.bounds.width / 2f
                    val centerY = command.bounds.y + command.bounds.height / 2f
                    val radiusX = command.bounds.width / 2f
                    val radiusY = command.bounds.height / 2f
                    if (command.useCenter) points += Point(centerX, centerY)
                    repeat(CURVE_STEPS + 1) { step ->
                        val t = step.toFloat() / CURVE_STEPS
                        val angle = (command.startAngle + command.sweepAngle * t) / 180f * PI.toFloat()
                        points += Point(
                            centerX + cos(angle) * radiusX,
                            centerY + sin(angle) * radiusY,
                        )
                    }
                    emitPolygon(points, colorWithOpacity(command.color))
                }

                is DisplayCommand.FillPath -> {
                    val path = displayList.resources.paths[command.pathId] ?: return@forEach
                    flattenPath(path).forEach { points ->
                        if (points.size >= 3) emitPolygon(points, colorWithOpacity(command.color))
                    }
                }

                is DisplayCommand.StrokePath -> {
                    val path = displayList.resources.paths[command.pathId] ?: return@forEach
                    flattenPath(path).forEach { points ->
                        points.zipWithNext().forEach { (from, to) ->
                            emitLine(
                                from.x,
                                from.y,
                                to.x,
                                to.y,
                                command.width,
                                command.color,
                            )
                        }
                    }
                }

                is DisplayCommand.DrawImage -> emitImage(command)

                is DisplayCommand.DrawText -> emitBitmapText(
                    command,
                    displayList.resources.fonts[command.fontId],
                    state.opacity,
                    ::emitRect,
                )

                is DisplayCommand.DrawGlyphRun -> {
                    val font = displayList.resources.fonts[command.fontId]
                    val size = font?.sizePx ?: 14f
                    val width = max(1f, size * 0.55f)
                    val height = max(1f, size)
                    command.glyphIds.indices.forEach { index ->
                        val x = command.positions[index * 2]
                        val y = command.positions[index * 2 + 1] - height
                        emitRect(Rect(x, y, width, height), command.color)
                    }
                }

                is DisplayCommand.BeginLayer -> {
                    layerOpacity += state.opacity
                    state = state.copy(opacity = state.opacity * command.opacity)
                }

                DisplayCommand.EndLayer -> {
                    val previous = layerOpacity.removeLastOrNull()
                    if (previous != null) state = state.copy(opacity = previous)
                }
            }
        }

        if (out.firstOrNull() !is DrawCommand.Clear) {
            out.add(0, DrawCommand.Clear(Rgba(0f, 0f, 0f, 0f)))
        }

        return Result(
            Frame(displayList.width, displayList.height, out.take(MAX_COMMANDS)),
            unsupported,
        )
    }

    private fun emitBitmapText(
        command: DisplayCommand.DrawText,
        font: FontResource?,
        opacity: Float,
        emit: (Rect, Rgba) -> Unit,
    ) {
        val scale = max(1f, (font?.sizePx ?: 14f) / 6f)
        val top = command.baselineY - 5f * scale
        var cursor = command.x
        val color = Rgba(
            command.color.red,
            command.color.green,
            command.color.blue,
            (command.color.alpha * opacity).coerceIn(0f, 1f),
        )

        command.text.forEach { raw ->
            val symbol = raw.uppercaseChar()
            val rows = glyph(symbol)
            if (rows == null) {
                emit(Rect(cursor, top, 3f * scale, 5f * scale), color)
            } else {
                rows.forEachIndexed { row, bits ->
                    var column = 0
                    while (column < 3) {
                        if (bits[column] == '0') {
                            column++
                            continue
                        }
                        val start = column
                        while (column < 3 && bits[column] == '1') column++
                        emit(
                            Rect(
                                cursor + start * scale,
                                top + row * scale,
                                (column - start) * scale,
                                scale,
                            ),
                            color,
                        )
                    }
                }
            }
            cursor += 4f * scale
        }
    }

    private fun glyph(c: Char): List<String>? = when (c) {
        'A' -> listOf("010","101","111","101","101")
        'B' -> listOf("110","101","110","101","110")
        'C' -> listOf("011","100","100","100","011")
        'D' -> listOf("110","101","101","101","110")
        'E' -> listOf("111","100","110","100","111")
        'F' -> listOf("111","100","110","100","100")
        'G' -> listOf("011","100","101","101","011")
        'H' -> listOf("101","101","111","101","101")
        'I' -> listOf("111","010","010","010","111")
        'J' -> listOf("001","001","001","101","010")
        'K' -> listOf("101","101","110","101","101")
        'L' -> listOf("100","100","100","100","111")
        'M' -> listOf("101","111","111","101","101")
        'N' -> listOf("101","111","111","111","101")
        'O' -> listOf("010","101","101","101","010")
        'P' -> listOf("110","101","110","100","100")
        'Q' -> listOf("010","101","101","111","011")
        'R' -> listOf("110","101","110","101","101")
        'S' -> listOf("011","100","010","001","110")
        'T' -> listOf("111","010","010","010","010")
        'U' -> listOf("101","101","101","101","111")
        'V' -> listOf("101","101","101","101","010")
        'W' -> listOf("101","101","111","111","101")
        'X' -> listOf("101","101","010","101","101")
        'Y' -> listOf("101","101","010","010","010")
        'Z' -> listOf("111","001","010","100","111")
        '0' -> listOf("111","101","101","101","111")
        '1' -> listOf("010","110","010","010","111")
        '2' -> listOf("110","001","010","100","111")
        '3' -> listOf("110","001","010","001","110")
        '4' -> listOf("101","101","111","001","001")
        '5' -> listOf("111","100","110","001","110")
        '6' -> listOf("011","100","110","101","010")
        '7' -> listOf("111","001","010","010","010")
        '8' -> listOf("010","101","010","101","010")
        '9' -> listOf("010","101","011","001","110")
        ' ' -> listOf("000","000","000","000","000")
        '.', ':' -> listOf("000","010","000","010","000")
        '-', '_' -> listOf("000","000","111","000","000")
        else -> null
    }
}
