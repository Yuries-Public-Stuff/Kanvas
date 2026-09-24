package dev.yurie.display

enum class GraphicsApi {
    VULKAN, METAL, GDI, OPENGL, DIRECT3D12, DIRECT3D11, DIRECT3D10, DIRECT3D9EX, DIRECT3D9, RECORDING
}

data class BackendAvailability(
    val vulkan: Boolean = false,
    val metal: Boolean = false,
    val gdi: Boolean = false,
    val openGl: Boolean = false,
    val direct3d12: Boolean = false,
    val direct3d11: Boolean = false,
    val direct3d10: Boolean = false,
    val direct3d9Ex: Boolean = false,
    val direct3d9: Boolean = false,
    val recording: Boolean = true,
) {
    fun supports(api: GraphicsApi): Boolean = when (api) {
        GraphicsApi.VULKAN -> vulkan
        GraphicsApi.METAL -> metal
        GraphicsApi.GDI -> gdi
        GraphicsApi.OPENGL -> openGl
        GraphicsApi.DIRECT3D12 -> direct3d12
        GraphicsApi.DIRECT3D11 -> direct3d11
        GraphicsApi.DIRECT3D10 -> direct3d10
        GraphicsApi.DIRECT3D9EX -> direct3d9Ex
        GraphicsApi.DIRECT3D9 -> direct3d9
        GraphicsApi.RECORDING -> recording
    }
}

object BackendSelector {
    fun select(
        availability: BackendAvailability,
        preferred: GraphicsApi? = null,
        allowRecording: Boolean = false,
    ): GraphicsApi {
        if (preferred != null && availability.supports(preferred) &&
            (preferred != GraphicsApi.RECORDING || allowRecording)) return preferred

        val candidates = listOf(
            GraphicsApi.METAL, GraphicsApi.VULKAN, GraphicsApi.OPENGL, GraphicsApi.DIRECT3D12,
            GraphicsApi.DIRECT3D11, GraphicsApi.DIRECT3D10, GraphicsApi.DIRECT3D9EX,
            GraphicsApi.DIRECT3D9, GraphicsApi.GDI,
        )
        return candidates.firstOrNull(availability::supports)
            ?: if (allowRecording && availability.recording) GraphicsApi.RECORDING
               else error("No implemented graphics backend available")
    }
}

data class Rgba(val red: Float, val green: Float, val blue: Float, val alpha: Float = 1f) {
    init {
        require(listOf(red, green, blue, alpha).all { it.isFinite() && it in 0f..1f }) {
            "Color channels must be finite values between 0 and 1"
        }
    }
}

data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
    init {
        require(listOf(x, y, width, height).all(Float::isFinite)) { "Rect values must be finite" }
        require(width >= 0f && height >= 0f) { "Rect size cannot be negative" }
    }
}

sealed interface DrawCommand {
    data class Clear(val color: Rgba) : DrawCommand
    data class FillRect(val bounds: Rect, val color: Rgba) : DrawCommand
}

data class Frame(val width: Int, val height: Int, val commands: List<DrawCommand>) {
    init {
        require(width > 0 && height > 0) { "Frame size must be positive" }
    }
}

interface Renderer : AutoCloseable {
    val api: GraphicsApi
    fun render(frame: Frame)
    override fun close()
}

class RecordingRenderer : Renderer {
    override val api = GraphicsApi.RECORDING
    private var closed = false
    private val history = mutableListOf<Frame>()
    val frames: List<Frame> get() = history.toList()

    override fun render(frame: Frame) {
        check(!closed) { "Renderer already closed" }
        history.add(frame.copy(commands = frame.commands.toList()))
    }

    override fun close() { closed = true }
}
