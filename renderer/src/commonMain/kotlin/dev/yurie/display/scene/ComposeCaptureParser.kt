package dev.yurie.display.scene

import dev.yurie.display.Rect
import dev.yurie.display.Rgba

data class CapturedDisplayFrame(
    val frameNumber: Long,
    val displayList: DisplayList,
    val unsupportedOperations: Set<String>,
)

object ComposeCaptureParser {
    fun parse(text: String): List<CapturedDisplayFrame> {
        val frames = mutableListOf<CapturedDisplayFrame>()
        var current: FrameState? = null

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("CAPTURE_")) return@forEach

            when {
                line.startsWith("FRAME_BEGIN ") -> {
                    current?.let { frames += it.finish() }
                    val fields = fields(line)
                    current = FrameState(
                        number = line.substringAfter("FRAME_BEGIN ").substringBefore(' ').toLong(),
                        width = fields.requireInt("width"),
                        height = fields.requireInt("height"),
                    )
                }

                line.startsWith("FRAME_END ") -> {
                    current?.let { frames += it.finish() }
                    current = null
                }

                else -> current?.consume(line)
            }
        }

        current?.let { frames += it.finish() }
        return frames
    }

    private class FrameState(
        private val number: Long,
        private val width: Int,
        private val height: Int,
    ) {
        private val commands = mutableListOf<DisplayCommand>()
        private val fonts = linkedMapOf<FontResource, Int>()
        private val unsupported = linkedSetOf<String>()

        fun consume(line: String) {
            val operation = line.substringBefore(' ')
            val values = fields(line)
            when (operation) {
                "CLEAR" -> commands += DisplayCommand.Clear(parseColor(values["color"]))
                "SAVE" -> commands += DisplayCommand.Save
                "RESTORE" -> commands += DisplayCommand.Restore
                "TRANSLATE" -> commands += DisplayCommand.Translate(
                    values.requireFloat("dx"), values.requireFloat("dy"),
                )
                "SCALE" -> commands += DisplayCommand.Scale(
                    values.requireFloat("sx"), values.requireFloat("sy"),
                )
                "CLIP_RECT" -> {
                    val rect = values.rect()
                    if (values["mode"]?.contains("DIFFERENCE", ignoreCase = true) == true) {
                        unsupported += "ClipRectDifference"
                    } else {
                        commands += DisplayCommand.ClipRect(rect)
                    }
                }
                "RECT" -> commands += DisplayCommand.FillRect(
                    values.rect(), parseColor(values["color"]),
                )
                "RRECT" -> {
                    val radii = parseFloatArray(values["radii"])
                    val rx = radii.getOrNull(0)?.coerceAtLeast(0f) ?: 0f
                    val ry = radii.getOrNull(1)?.coerceAtLeast(0f) ?: rx
                    commands += DisplayCommand.FillRoundRect(
                        values.rect(), rx, ry, parseColor(values["color"]),
                    )
                }
                "OVAL" -> commands += DisplayCommand.FillOval(
                    values.rect(),
                    parseColor(values["color"]),
                )
                "CIRCLE" -> commands += DisplayCommand.FillCircle(
                    centerX = values.requireFloat("x"),
                    centerY = values.requireFloat("y"),
                    radius = values.requireFloat("radius"),
                    color = parseColor(values["color"]),
                )
                "LINE" -> commands += DisplayCommand.StrokeLine(
                    x1 = values.requireFloat("x1"),
                    y1 = values.requireFloat("y1"),
                    x2 = values.requireFloat("x2"),
                    y2 = values.requireFloat("y2"),
                    width = values.requireFloat("width"),
                    color = parseColor(values["color"]),
                )
                "STRING" -> {
                    val text = values["text"]?.unquote().orEmpty()
                    if (text.isEmpty()) return
                    val font = FontResource(
                        family = values["family"]?.unquote()?.ifBlank { "Default" } ?: "Default",
                        sizePx = values["size"]?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 14f,
                    )
                    val id = fonts.getOrPut(font) { fonts.size + 1 }
                    commands += DisplayCommand.DrawText(
                        fontId = id,
                        text = text,
                        x = values.requireFloat("x"),
                        baselineY = values.requireFloat("y"),
                        color = parseColor(values["color"]),
                    )
                }
                "OP" -> unsupported += values["name"] ?: "Unknown"
                else -> unsupported += operation
            }
        }

        fun finish(): CapturedDisplayFrame {
            val balanced = balanceState(commands, unsupported)
            val withClear = if (balanced.firstOrNull() is DisplayCommand.Clear) {
                balanced
            } else {
                listOf(DisplayCommand.Clear(Rgba(0f, 0f, 0f, 0f))) + balanced
            }
            return CapturedDisplayFrame(
                frameNumber = number,
                displayList = DisplayList(
                    width = width,
                    height = height,
                    commands = withClear,
                    resources = DisplayResources(
                        fonts = fonts.entries.associate { (font, id) -> id to font },
                    ),
                ),
                unsupportedOperations = unsupported.toSet(),
            )
        }
    }

    private fun balanceState(
        source: List<DisplayCommand>,
        unsupported: MutableSet<String>,
    ): List<DisplayCommand> {
        var saveDepth = 0
        var layerDepth = 0
        val out = mutableListOf<DisplayCommand>()
        source.forEach { command ->
            when (command) {
                DisplayCommand.Save -> {
                    saveDepth++
                    out += command
                }
                DisplayCommand.Restore -> {
                    if (saveDepth > 0) {
                        saveDepth--
                        out += command
                    } else {
                        unsupported += "RestoreWithoutSave"
                    }
                }
                is DisplayCommand.BeginLayer -> {
                    layerDepth++
                    out += command
                }
                DisplayCommand.EndLayer -> {
                    if (layerDepth > 0) {
                        layerDepth--
                        out += command
                    } else {
                        unsupported += "EndLayerWithoutBegin"
                    }
                }
                else -> out += command
            }
        }
        repeat(saveDepth) {
            unsupported += "AutoBalancedSave"
            out += DisplayCommand.Restore
        }
        repeat(layerDepth) {
            unsupported += "AutoBalancedLayer"
            out += DisplayCommand.EndLayer
        }
        return out
    }

    private fun Map<String, String>.rect(): Rect {
        val left = requireFloat("l")
        val top = requireFloat("t")
        val right = requireFloat("r")
        val bottom = requireFloat("b")
        return Rect(
            x = minOf(left, right),
            y = minOf(top, bottom),
            width = kotlin.math.abs(right - left),
            height = kotlin.math.abs(bottom - top),
        )
    }

    private fun Map<String, String>.requireFloat(name: String): Float =
        requireNotNull(this[name]?.toFloatOrNull()) { "Missing/invalid $name in capture" }

    private fun Map<String, String>.requireInt(name: String): Int =
        requireNotNull(this[name]?.toIntOrNull()) { "Missing/invalid $name in capture" }

    private fun parseColor(raw: String?): Rgba {
        if (raw == null || raw == "null" || raw == "unknown") return Rgba(1f, 1f, 1f, 1f)
        val signed = raw.toLongOrNull() ?: return Rgba(1f, 1f, 1f, 1f)
        val argb = signed and 0xffffffffL
        val alpha = ((argb ushr 24) and 0xff).toFloat() / 255f
        val red = ((argb ushr 16) and 0xff).toFloat() / 255f
        val green = ((argb ushr 8) and 0xff).toFloat() / 255f
        val blue = (argb and 0xff).toFloat() / 255f
        return Rgba(red, green, blue, alpha)
    }

    private fun parseFloatArray(raw: String?): List<Float> {
        if (raw == null || raw == "null") return emptyList()
        return raw.removePrefix("[").removeSuffix("]")
            .split(',')
            .mapNotNull { it.toFloatOrNull() }
    }

    private fun fields(line: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = line.indexOf(' ')
        if (index < 0) return result
        while (index < line.length) {
            while (index < line.length && line[index].isWhitespace()) index++
            if (index >= line.length) break
            val keyStart = index
            while (index < line.length && line[index] != '=' && !line[index].isWhitespace()) index++
            if (index >= line.length || line[index] != '=') {
                while (index < line.length && !line[index].isWhitespace()) index++
                continue
            }
            val key = line.substring(keyStart, index)
            index++
            val value = if (index < line.length && line[index] == '"') {
                val start = index
                index++
                var escaped = false
                while (index < line.length) {
                    val ch = line[index++]
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') break
                }
                line.substring(start, index)
            } else {
                val start = index
                while (index < line.length && !line[index].isWhitespace()) index++
                line.substring(start, index)
            }
            result[key] = value
        }
        return result
    }

    private fun String.unquote(): String {
        if (length < 2 || first() != '"' || last() != '"') return this
        val source = substring(1, length - 1)
        val out = StringBuilder()
        var escaped = false
        source.forEach { ch ->
            if (escaped) {
                out.append(
                    when (ch) {
                        'n' -> '\n'
                        'r' -> '\r'
                        '\\' -> '\\'
                        '"' -> '"'
                        else -> ch
                    },
                )
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else {
                out.append(ch)
            }
        }
        if (escaped) out.append('\\')
        return out.toString()
    }
}
