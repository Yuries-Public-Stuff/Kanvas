package dev.yurie.display.compose

import dev.yurie.display.Canvas
import dev.yurie.display.Frame
import dev.yurie.display.Rect
import dev.yurie.display.Rgba

// Compose frame to display-list adapter.
class ComposeFrameAdapter(
    private val width: Int,
    private val height: Int,
    val density: Float = 1f,
) {
    private val canvas = Canvas(width, height)
    private var originX = 0f
    private var originY = 0f
    private var clip = Rect(0f, 0f, width.toFloat(), height.toFloat())
    private var drawn = false

    init {
        require(density.isFinite() && density > 0f) { "Density must be finite and positive" }
    }

    fun clear(color: Rgba) {
        check(!drawn) { "Clear must precede draw commands" }
        canvas.clear(color)
    }

    fun drawRect(x: Float, y: Float, width: Float, height: Float, color: Rgba) {
        val local = Rect(x, y, width, height)
        val bounds = Rect(
            originX + local.x * density,
            originY + local.y * density,
            local.width * density,
            local.height * density,
        )
        require(listOf(bounds.x, bounds.y, bounds.width, bounds.height).all(Float::isFinite)) {
            "Scaled geometry must be finite"
        }
        drawn = true
        intersect(bounds, clip)?.let {
            if (it.width > 0f && it.height > 0f) {
                canvas.fillRect(it.x, it.y, it.width, it.height, color)
            }
        }
    }

    fun translate(x: Float, y: Float, draw: ComposeFrameAdapter.() -> Unit) {
        require(x.isFinite() && y.isFinite())
        val previousX = originX
        val previousY = originY
        originX += x * density
        originY += y * density
        try {
            draw()
        } finally {
            originX = previousX
            originY = previousY
        }
    }

    fun clipRect(x: Float, y: Float, width: Float, height: Float, draw: ComposeFrameAdapter.() -> Unit) {
        val local = Rect(x, y, width, height)
        val bounds = Rect(
            originX + local.x * density,
            originY + local.y * density,
            local.width * density,
            local.height * density,
        )
        require(listOf(bounds.x, bounds.y, bounds.width, bounds.height).all(Float::isFinite))
        val previous = clip
        clip = intersect(previous, bounds) ?: Rect(0f, 0f, 0f, 0f)
        try {
            draw()
        } finally {
            clip = previous
        }
    }

    fun build(): Frame = canvas.frame()

    private fun intersect(a: Rect, b: Rect): Rect? {
        val left = maxOf(a.x, b.x)
        val top = maxOf(a.y, b.y)
        val right = minOf(a.x + a.width, b.x + b.width)
        val bottom = minOf(a.y + a.height, b.y + b.height)
        return if (right <= left || bottom <= top) null
        else Rect(left, top, right - left, bottom - top)
    }
}

fun composeFrame(
    width: Int,
    height: Int,
    density: Float = 1f,
    draw: ComposeFrameAdapter.() -> Unit,
): Frame = ComposeFrameAdapter(width, height, density).apply(draw).build()
