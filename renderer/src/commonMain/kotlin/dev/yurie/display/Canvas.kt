package dev.yurie.display

class Canvas(private val width: Int, private val height: Int) {
    private val commands = mutableListOf<DrawCommand>()

    init {
        require(width > 0 && height > 0) { "Canvas size must be positive" }
    }

    fun clear(color: Rgba) {
        commands.clear()
        commands += DrawCommand.Clear(color)
    }

    fun fillRect(x: Float, y: Float, width: Float, height: Float, color: Rgba) {
        commands += DrawCommand.FillRect(Rect(x, y, width, height), color)
    }

    fun frame(): Frame = Frame(width, height, commands.toList())
}

inline fun frame(width: Int, height: Int, draw: Canvas.() -> Unit): Frame =
    Canvas(width, height).apply(draw).frame()
