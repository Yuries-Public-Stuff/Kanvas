package dev.yurie.display.ui

import dev.yurie.display.Rgba
import dev.yurie.display.compose.ComposeFrameAdapter
import kotlin.math.cos
import kotlin.math.sin

object WireframePreview {
    private data class Vertex(val x: Float, val y: Float, val z: Float)
    private data class Point(val x: Float, val y: Float)
    private val cube = listOf(
        Vertex(-1f, -1f, -1f), Vertex(1f, -1f, -1f), Vertex(1f, 1f, -1f), Vertex(-1f, 1f, -1f),
        Vertex(-1f, -1f, 1f), Vertex(1f, -1f, 1f), Vertex(1f, 1f, 1f), Vertex(-1f, 1f, 1f),
    )
    private val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4,
        0 to 4, 1 to 5, 2 to 6, 3 to 7)

    fun draw(adapter: ComposeFrameAdapter, x: Float, y: Float, width: Float, height: Float, angle: Float) {
        require(listOf(x, y, width, height, angle).all(Float::isFinite))
        require(width >= 0f && height >= 0f)
        if (width < 20f || height < 20f) return
        adapter.drawRect(x, y, width, height, Rgba(0.08f, 0.14f, 0.19f))
        val centerX = x + width * 0.5f
        val centerY = y + height * 0.5f
        val scale = minOf(width * 0.20f, height * 0.28f)
        val c = cos(angle)
        val s = sin(angle)
        val tilt = 0.39f
        val projected = cube.map { point ->
            val rx = point.x * c + point.z * s
            val rz = point.z * c - point.x * s
            val ry = point.y * cos(tilt) - rz * sin(tilt)
            val depth = point.y * sin(tilt) + rz * cos(tilt)
            val perspective = 3.5f / (3.5f + depth)
            Point(centerX + rx * perspective * scale, centerY + ry * perspective * scale)
        }
        val lineColor = Rgba(0.47f, 0.87f, 0.88f)
        for ((a, b) in edges) {
            val start = projected[a]
            val end = projected[b]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val steps = maxOf(1, minOf(128, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)).toInt()))
            for (index in 0..steps) {
                val t = index.toFloat() / steps
                adapter.drawRect(start.x + dx * t, start.y + dy * t, 2f, 2f, lineColor)
            }
        }
        adapter.drawRect(centerX - 2f, centerY - 2f, 4f, 4f, Rgba(0.96f, 0.74f, 0.41f))
    }
}
