package dev.yurie.display.scene

interface DisplayListRenderer : AutoCloseable {
    fun render(displayList: DisplayList)
    override fun close()
}

class RecordingDisplayListRenderer : DisplayListRenderer {
    private var closed = false
    private val history = mutableListOf<DisplayList>()

    val frames: List<DisplayList>
        get() = history.toList()

    override fun render(displayList: DisplayList) {
        check(!closed) { "Renderer already closed" }
        history += displayList.copy(commands = displayList.commands.toList())
    }

    override fun close() {
        closed = true
    }
}
