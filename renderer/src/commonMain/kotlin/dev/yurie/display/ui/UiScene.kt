package dev.yurie.display.ui

import dev.yurie.display.Frame
import dev.yurie.display.Rect
import dev.yurie.display.Rgba
import dev.yurie.display.compose.ComposeFrameAdapter

internal enum class Direction { BOX, ROW, COLUMN, BUTTON, TEXT, MODEL }

internal data class Node(
    val direction: Direction,
    val width: Float,
    val height: Float,
    val background: Rgba?,
    val padding: Float,
    val spacing: Float,
    val children: List<Node>,
    val onClick: (() -> Unit)?,
    val label: String? = null,
    val labelColor: Rgba = Rgba(1f, 1f, 1f),
    val labelScale: Float = 2f,
    val modelAngle: Float = 0f,
)

class UiScope internal constructor() {
    private val nodes = mutableListOf<Node>()

    private fun add(
        direction: Direction,
        width: Float,
        height: Float,
        background: Rgba?,
        padding: Float,
        spacing: Float,
        onClick: (() -> Unit)? = null,
        label: String? = null,
        labelColor: Rgba = Rgba(1f, 1f, 1f),
        labelScale: Float = 2f,
        modelAngle: Float = 0f,
        content: UiScope.() -> Unit = {},
    ) {
        require(width.isFinite() && width >= 0f && height.isFinite() && height >= 0f)
        require(padding.isFinite() && padding >= 0f && spacing.isFinite() && spacing >= 0f)
        require(labelScale.isFinite() && labelScale > 0f && modelAngle.isFinite())
        if (label != null) require(label.all { it.uppercaseChar() in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!? .:-/+" }) {
            "Bitmap labels support only basic ASCII letters, numbers and punctuation"
        }
        val scope = UiScope().apply(content)
        nodes += Node(direction, width, height, background, padding, spacing, scope.nodes.toList(),
            onClick, label, labelColor, labelScale, modelAngle)
    }

    fun box(width: Float, height: Float, background: Rgba? = null, padding: Float = 0f, content: UiScope.() -> Unit = {}) =
        add(Direction.BOX, width, height, background, padding, 0f, content = content)

    fun row(width: Float, height: Float, background: Rgba? = null, padding: Float = 0f, spacing: Float = 0f, content: UiScope.() -> Unit) =
        add(Direction.ROW, width, height, background, padding, spacing, content = content)

    fun column(width: Float, height: Float, background: Rgba? = null, padding: Float = 0f, spacing: Float = 0f, content: UiScope.() -> Unit) =
        add(Direction.COLUMN, width, height, background, padding, spacing, content = content)

    fun button(width: Float, height: Float, background: Rgba, onClick: () -> Unit) =
        add(Direction.BUTTON, width, height, background, 0f, 0f, onClick = onClick)

    fun button(
        width: Float, height: Float, background: Rgba, label: String,
        labelColor: Rgba = Rgba(1f, 1f, 1f), labelScale: Float = 3f, onClick: () -> Unit,
    ) = add(Direction.BUTTON, width, height, background, 0f, 0f,
        onClick = onClick, label = label, labelColor = labelColor, labelScale = labelScale)

    fun text(width: Float, height: Float, text: String, color: Rgba = Rgba(1f, 1f, 1f), scale: Float = 2f) =
        add(Direction.TEXT, width, height, null, 0f, 0f, label = text, labelColor = color, labelScale = scale)

    fun wireframe(width: Float, height: Float, angle: Float) =
        add(Direction.MODEL, width, height, null, 0f, 0f, modelAngle = angle)

    internal fun snapshot(): List<Node> = nodes.toList()
}

class UiScene internal constructor(
    private val width: Int,
    private val height: Int,
    val density: Float,
    private val background: Rgba,
    private val roots: List<Node>,
) {
    private data class Hit(val bounds: Rect, val callback: () -> Unit)
    private val hits = mutableListOf<Hit>()
    private var laidOut = false

    init {
        require(width > 0 && height > 0)
        require(density.isFinite() && density > 0f)
    }

    fun frame(): Frame {
        val adapter = ComposeFrameAdapter(width, height, density)
        val viewport = Rect(0f, 0f, width / density, height / density)
        hits.clear()
        adapter.clear(background)
        layout(adapter, roots, Direction.COLUMN, 0f, 0f, 0f, 0f, viewport)
        laidOut = true
        return adapter.build()
    }

    fun click(pixelX: Float, pixelY: Float): Boolean {
        require(pixelX.isFinite() && pixelY.isFinite())
        if (!laidOut) frame()
        val x = pixelX / density
        val y = pixelY / density
        val hit = hits.asReversed().firstOrNull {
            x >= it.bounds.x && x < it.bounds.x + it.bounds.width &&
                y >= it.bounds.y && y < it.bounds.y + it.bounds.height
        } ?: return false
        hit.callback()
        return true
    }

    private fun layout(
        adapter: ComposeFrameAdapter,
        nodes: List<Node>,
        direction: Direction,
        originX: Float,
        originY: Float,
        padding: Float,
        spacing: Float,
        clip: Rect,
    ) {
        var cursor = 0f
        for (node in nodes) {
            val x = originX + padding + if (direction == Direction.ROW) cursor else 0f
            val y = originY + padding + if (direction == Direction.COLUMN) cursor else 0f
            val bounds = Rect(x, y, node.width, node.height)
            val visible = intersect(bounds, clip)
            if (visible != null) {
                adapter.clipRect(visible.x, visible.y, visible.width, visible.height) {
                    node.background?.let { drawRect(x, y, node.width, node.height, it) }
                    if (node.direction == Direction.MODEL) {
                        WireframePreview.draw(this, x, y, node.width, node.height, node.modelAngle)
                    }
                    node.label?.let { label ->
                        val labelWidth = BitmapLabels.width(label, node.labelScale)
                        val labelX = if (node.direction == Direction.BUTTON) x + (node.width - labelWidth) / 2f else x
                        val labelY = y + (node.height - 5f * node.labelScale) / 2f
                        BitmapLabels.draw(this, label, labelX, labelY, node.labelScale, node.labelColor)
                    }
                    if (node.onClick != null) hits += Hit(visible, node.onClick)
                    layout(adapter, node.children, node.direction, x, y, node.padding, node.spacing, visible)
                }
            }
            if (direction == Direction.ROW) cursor += node.width + spacing
            if (direction == Direction.COLUMN) cursor += node.height + spacing
        }
    }

    private fun intersect(a: Rect, b: Rect): Rect? {
        val left = maxOf(a.x, b.x)
        val top = maxOf(a.y, b.y)
        val right = minOf(a.x + a.width, b.x + b.width)
        val bottom = minOf(a.y + a.height, b.y + b.height)
        return if (right <= left || bottom <= top) null else Rect(left, top, right - left, bottom - top)
    }
}

fun uiScene(
    width: Int,
    height: Int,
    density: Float = 1f,
    background: Rgba = Rgba(0f, 0f, 0f),
    content: UiScope.() -> Unit,
): UiScene {
    require(density.isFinite() && density > 0f)
    return UiScene(width, height, density, background, UiScope().apply(content).snapshot())
}
