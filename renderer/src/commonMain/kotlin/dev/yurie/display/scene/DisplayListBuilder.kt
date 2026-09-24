package dev.yurie.display.scene

import dev.yurie.display.Rect
import dev.yurie.display.Rgba

class DisplayListBuilder(
    private val width: Int,
    private val height: Int,
) {
    private val commands = mutableListOf<DisplayCommand>()
    private val images = mutableMapOf<Int, ImageResource>()
    private val fonts = mutableMapOf<Int, FontResource>()
    private val paths = mutableMapOf<Int, PathResource>()
    private var nextImageId = 1
    private var nextFontId = 1
    private var nextPathId = 1

    init {
        require(width > 0 && height > 0)
    }

    fun image(resource: ImageResource): Int {
        val id = nextImageId++
        images[id] = resource
        return id
    }

    fun font(resource: FontResource): Int {
        val id = nextFontId++
        fonts[id] = resource
        return id
    }

    fun path(resource: PathResource): Int {
        val id = nextPathId++
        paths[id] = resource
        return id
    }

    fun clear(color: Rgba) { commands += DisplayCommand.Clear(color) }
    fun save() { commands += DisplayCommand.Save }
    fun restore() { commands += DisplayCommand.Restore }
    fun translate(x: Float, y: Float) { commands += DisplayCommand.Translate(x, y) }
    fun scale(x: Float, y: Float) { commands += DisplayCommand.Scale(x, y) }
    fun rotate(degrees: Float) { commands += DisplayCommand.Rotate(degrees) }
    fun skew(xDegrees: Float, yDegrees: Float) { commands += DisplayCommand.Skew(xDegrees, yDegrees) }
    fun concat(values: FloatArray) { commands += DisplayCommand.Concat(TransformMatrix(values.toList())) }
    fun clipRect(bounds: Rect) { commands += DisplayCommand.ClipRect(bounds) }
    fun clipPath(pathId: Int) { commands += DisplayCommand.ClipPath(pathId) }
    fun fillRect(bounds: Rect, color: Rgba) { commands += DisplayCommand.FillRect(bounds, color) }

    fun fillRoundRect(bounds: Rect, radiusX: Float, radiusY: Float, color: Rgba) {
        commands += DisplayCommand.FillRoundRect(bounds, radiusX, radiusY, color)
    }

    fun fillOval(bounds: Rect, color: Rgba) {
        commands += DisplayCommand.FillOval(bounds, color)
    }

    fun fillCircle(centerX: Float, centerY: Float, radius: Float, color: Rgba) {
        commands += DisplayCommand.FillCircle(centerX, centerY, radius, color)
    }

    fun strokeLine(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Rgba) {
        commands += DisplayCommand.StrokeLine(x1, y1, x2, y2, width, color)
    }

    fun fillArc(bounds: Rect, startAngle: Float, sweepAngle: Float, useCenter: Boolean, color: Rgba) {
        commands += DisplayCommand.FillArc(bounds, startAngle, sweepAngle, useCenter, color)
    }

    fun fillPath(pathId: Int, color: Rgba) {
        commands += DisplayCommand.FillPath(pathId, color)
    }

    fun strokePath(pathId: Int, width: Float, color: Rgba) {
        commands += DisplayCommand.StrokePath(pathId, width, color)
    }

    fun drawImage(
        imageId: Int,
        source: Rect,
        destination: Rect,
        opacity: Float = 1f,
    ) {
        commands += DisplayCommand.DrawImage(imageId, source, destination, opacity)
    }

    fun drawImage(imageId: Int, destination: Rect, opacity: Float = 1f) {
        val resource = requireNotNull(images[imageId]) { "Unknown image resource $imageId" }
        drawImage(
            imageId = imageId,
            source = Rect(0f, 0f, resource.width.toFloat(), resource.height.toFloat()),
            destination = destination,
            opacity = opacity,
        )
    }

    fun drawText(fontId: Int, text: String, x: Float, baselineY: Float, color: Rgba) {
        commands += DisplayCommand.DrawText(fontId, text, x, baselineY, color)
    }

    fun drawGlyphRun(fontId: Int, glyphIds: IntArray, positions: FloatArray, color: Rgba) {
        commands += DisplayCommand.DrawGlyphRun(fontId, glyphIds, positions, color)
    }

    fun beginLayer(opacity: Float = 1f) { commands += DisplayCommand.BeginLayer(opacity) }
    fun endLayer() { commands += DisplayCommand.EndLayer }

    inline fun saved(block: DisplayListBuilder.() -> Unit) {
        save()
        try { block() } finally { restore() }
    }

    inline fun layer(opacity: Float = 1f, block: DisplayListBuilder.() -> Unit) {
        beginLayer(opacity)
        try { block() } finally { endLayer() }
    }

    fun build(): DisplayList = DisplayList(
        width = width,
        height = height,
        commands = commands.toList(),
        resources = DisplayResources(images.toMap(), fonts.toMap(), paths.toMap()),
    )
}

inline fun displayList(
    width: Int,
    height: Int,
    build: DisplayListBuilder.() -> Unit,
): DisplayList = DisplayListBuilder(width, height).apply(build).build()
