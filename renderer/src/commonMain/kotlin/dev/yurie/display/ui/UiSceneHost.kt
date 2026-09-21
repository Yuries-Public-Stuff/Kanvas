package dev.yurie.display.ui

import dev.yurie.display.Frame

class UiSceneHost(private val build: (width: Int, height: Int) -> UiScene) : AutoCloseable {
    private var currentSize: Pair<Int, Int>? = null
    private var currentScene: UiScene? = null
    private var currentFrame: Frame? = null
    private val subscriptions = mutableListOf<() -> Unit>()
    private var closed = false

    fun <T> watch(state: UiState<T>): UiSceneHost {
        check(!closed) { "Scene host is closed" }
        subscriptions += state.observe { invalidate() }
        return this
    }

    fun frame(width: Int, height: Int): Frame {
        check(!closed) { "Scene host is closed" }
        require(width > 0 && height > 0) { "Viewport must be positive" }
        if (currentFrame == null || currentSize != (width to height)) {
            val nextScene = build(width, height)
            val nextFrame = nextScene.frame()
            require(nextFrame.width == width && nextFrame.height == height) {
                "Scene dimensions must match the viewport"
            }
            currentScene = nextScene
            currentFrame = nextFrame
            currentSize = width to height
        }
        return currentFrame!!
    }

    fun scene(width: Int, height: Int): UiScene {
        frame(width, height)
        return currentScene!!
    }

    fun click(pixelX: Float, pixelY: Float): Boolean {
        check(!closed) { "Scene host is closed" }
        val scene = checkNotNull(currentScene) { "Build a frame before dispatching input" }
        val consumed = scene.click(pixelX, pixelY)
        if (consumed) invalidate()
        return consumed
    }

    fun invalidate() {
        if (!closed) currentFrame = null
    }

    override fun close() {
        if (closed) return
        closed = true
        subscriptions.forEach { it() }
        subscriptions.clear()
        currentScene = null
        currentFrame = null
        currentSize = null
    }
}
