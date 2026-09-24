package dev.yurie.display.scene

import dev.yurie.display.DrawCommand
import dev.yurie.display.Frame
import dev.yurie.display.Rect

data class DisplayListCompatibility(
    val supported: Boolean,
    val unsupportedCommands: Set<String>,
)

object LegacyFrameLowerer {
    fun compatibility(displayList: DisplayList): DisplayListCompatibility {
        val unsupported = displayList.commands.mapNotNullTo(linkedSetOf()) { command ->
            when (command) {
                is DisplayCommand.Clear,
                DisplayCommand.Save,
                DisplayCommand.Restore,
                is DisplayCommand.Translate,
                is DisplayCommand.Scale,
                is DisplayCommand.ClipRect,
                is DisplayCommand.FillRect -> null
                is DisplayCommand.ClipPath -> "ClipPath"
                is DisplayCommand.Rotate -> "Rotate"
                is DisplayCommand.Skew -> "Skew"
                is DisplayCommand.Concat -> "Concat"
                is DisplayCommand.FillRoundRect -> "FillRoundRect"
                is DisplayCommand.FillOval -> "FillOval"
                is DisplayCommand.FillCircle -> "FillCircle"
                is DisplayCommand.StrokeLine -> "StrokeLine"
                is DisplayCommand.FillArc -> "FillArc"
                is DisplayCommand.FillPath -> "FillPath"
                is DisplayCommand.StrokePath -> "StrokePath"
                is DisplayCommand.DrawImage -> "DrawImage"
                is DisplayCommand.DrawText -> "DrawText"
                is DisplayCommand.DrawGlyphRun -> "DrawGlyphRun"
                is DisplayCommand.BeginLayer -> "BeginLayer"
                DisplayCommand.EndLayer -> "EndLayer"
            }
        }
        return DisplayListCompatibility(unsupported.isEmpty(), unsupported)
    }

    fun lower(displayList: DisplayList): Frame {
        val compatibility = compatibility(displayList)
        require(compatibility.supported) {
            "Display list needs native primitives: " + compatibility.unsupportedCommands.joinToString()
        }

        data class State(
            val sx: Float,
            val sy: Float,
            val tx: Float,
            val ty: Float,
            val clip: Rect?,
        )

        var state = State(1f, 1f, 0f, 0f, Rect(0f, 0f, displayList.width.toFloat(), displayList.height.toFloat()))
        val stack = mutableListOf<State>()
        val out = mutableListOf<DrawCommand>()

        fun transformed(rect: Rect): Rect {
            val x1 = rect.x * state.sx + state.tx
            val y1 = rect.y * state.sy + state.ty
            val x2 = (rect.x + rect.width) * state.sx + state.tx
            val y2 = (rect.y + rect.height) * state.sy + state.ty
            return Rect(
                minOf(x1, x2),
                minOf(y1, y2),
                kotlin.math.abs(x2 - x1),
                kotlin.math.abs(y2 - y1),
            )
        }

        fun intersect(a: Rect?, b: Rect): Rect? {
            if (a == null) return null
            val left = maxOf(a.x, b.x)
            val top = maxOf(a.y, b.y)
            val right = minOf(a.x + a.width, b.x + b.width)
            val bottom = minOf(a.y + a.height, b.y + b.height)
            return if (right <= left || bottom <= top) null
            else Rect(left, top, right - left, bottom - top)
        }

        displayList.commands.forEach { command ->
            when (command) {
                is DisplayCommand.Clear -> out += DrawCommand.Clear(command.color)
                DisplayCommand.Save -> stack += state
                DisplayCommand.Restore -> state = stack.removeAt(stack.lastIndex)
                is DisplayCommand.Translate -> {
                    state = state.copy(
                        tx = state.tx + command.x * state.sx,
                        ty = state.ty + command.y * state.sy,
                    )
                }
                is DisplayCommand.Scale -> {
                    state = state.copy(
                        sx = state.sx * command.x,
                        sy = state.sy * command.y,
                    )
                }
                is DisplayCommand.Rotate,
                is DisplayCommand.Skew,
                is DisplayCommand.Concat -> error("Unsupported transform passed compatibility check")
                is DisplayCommand.ClipRect -> {
                    state = state.copy(clip = intersect(state.clip, transformed(command.bounds)))
                }
                is DisplayCommand.ClipPath -> error("Unsupported clip passed compatibility check")
                is DisplayCommand.FillRect -> {
                    val clipped = intersect(state.clip, transformed(command.bounds))
                    if (clipped != null) out += DrawCommand.FillRect(clipped, command.color)
                }
                is DisplayCommand.FillRoundRect,
                is DisplayCommand.FillOval,
                is DisplayCommand.FillCircle,
                is DisplayCommand.StrokeLine,
                is DisplayCommand.FillArc,
                is DisplayCommand.FillPath,
                is DisplayCommand.StrokePath,
                is DisplayCommand.DrawImage,
                is DisplayCommand.DrawText,
                is DisplayCommand.DrawGlyphRun,
                is DisplayCommand.BeginLayer,
                DisplayCommand.EndLayer -> error("Unsupported command passed compatibility check")
            }
        }

        return Frame(displayList.width, displayList.height, out)
    }
}
