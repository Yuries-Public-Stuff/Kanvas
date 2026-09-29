package dev.yurie.display.composebridge

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.yurie.display.scene.DisplayList
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface

// Captures a Compose scene into a display list.
@OptIn(InternalComposeUiApi::class)
class ComposeSceneCaptureHost(
    width: Int,
    height: Int,
    density: Float = 1f,
    private val strictCanvas: Boolean = true,
    invalidate: () -> Unit = {},
) : AutoCloseable {
    private val scene: ComposeScene = CanvasLayersComposeScene(
        density = Density(density),
        size = IntSize(width, height),
        coroutineContext = Dispatchers.Unconfined,
        invalidate = invalidate,
    )

    private var closed = false

    init {
        require(width > 0 && height > 0)
        require(density.isFinite() && density > 0f)
    }

    fun setContent(content: @Composable () -> Unit) {
        check(!closed)
        scene.setContent(content)
    }

    fun resize(width: Int, height: Int) {
        check(!closed)
        require(width > 0 && height > 0)
        scene.size = IntSize(width, height)
    }

    fun render(nanoTime: Long): CaptureResult {
        check(!closed)
        require(nanoTime >= 0L)
        val size = requireNotNull(scene.size) { "Scene size must be set before render" }

        // Compose scene layers require a Skia-backed canvas.
        val surface = Surface.makeRasterN32Premul(size.width, size.height)
        try {
            scene.render(surface.canvas.asComposeCanvas(), nanoTime)

            val snapshot = surface.makeImageSnapshot()
            try {
                val frame = snapshot.toComposeImageBitmap()
                val capture = ComposeDisplayListCanvas(
                    width = size.width,
                    height = size.height,
                    strict = strictCanvas,
                )
                capture.drawImage(
                    image = frame,
                    topLeftOffset = Offset.Zero,
                    paint = Paint(),
                )
                return CaptureResult(
                    displayList = capture.build(),
                    unsupportedOperations = capture.unsupportedOperations,
                )
            } finally {
                snapshot.close()
            }
        } finally {
            surface.close()
        }
    }

    fun pointerMove(x: Float, y: Float, timeMillis: Long = 0L) {
        sendPointer(PointerEventType.Move, x, y, timeMillis)
    }

    fun pointerPress(x: Float, y: Float, timeMillis: Long = 0L) {
        sendPointer(PointerEventType.Press, x, y, timeMillis)
    }

    fun pointerRelease(x: Float, y: Float, timeMillis: Long = 0L) {
        sendPointer(PointerEventType.Release, x, y, timeMillis)
    }

    private fun sendPointer(type: PointerEventType, x: Float, y: Float, timeMillis: Long) {
        check(!closed)
        require(x.isFinite() && y.isFinite())
        scene.sendPointerEvent(
            eventType = type,
            position = Offset(x, y),
            timeMillis = timeMillis,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        scene.close()
    }
}

data class CaptureResult(
    val displayList: DisplayList,
    val unsupportedOperations: Set<String>,
)
