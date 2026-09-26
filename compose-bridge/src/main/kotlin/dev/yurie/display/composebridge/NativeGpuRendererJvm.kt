package dev.yurie.display.composebridge

import androidx.compose.runtime.Composable
import dev.yurie.display.DrawCommand
import dev.yurie.display.GraphicsApi
import dev.yurie.display.composebridge.nativebridge.NativeGpuBridge
import dev.yurie.display.scene.DisplayList
import dev.yurie.display.scene.GpuPrimitiveLowerer

class NativeGpuRendererJvm(
    width: Int,
    height: Int,
    title: String,
    val api: GraphicsApi = defaultJvmGpuApi(),
) : AutoCloseable {
    private var handle: Long

    init {
        require(width > 0 && height > 0)
        NativeGpuBridge.ensureLoaded()
        handle = NativeGpuBridge.create(
            width,
            height,
            title,
            requestedBridgeId(api),
        )
        check(handle != 0L) { "Could not create native GPU renderer for " + api }
    }

    fun poll(): Boolean {
        check(handle != 0L) { "Renderer is closed" }
        return NativeGpuBridge.poll(handle)
    }

    fun size(): Pair<Int, Int> {
        check(handle != 0L) { "Renderer is closed" }
        val packed = NativeGpuBridge.size(handle)
        return ((packed ushr 32).toInt()) to (packed and 0xffffffffL).toInt()
    }

    fun drainPointers(onPointer: (kind: Int, x: Float, y: Float) -> Unit) {
        check(handle != 0L) { "Renderer is closed" }
        val event = IntArray(3)
        while (NativeGpuBridge.nextPointer(handle, event)) {
            onPointer(event[0], event[1].toFloat(), event[2].toFloat())
        }
    }

    fun render(displayList: DisplayList, strict: Boolean = true): Set<String> {
        check(handle != 0L) { "Renderer is closed" }

        val lowered = GpuPrimitiveLowerer.lower(displayList)
        if (strict && lowered.unsupported.isNotEmpty()) {
            error(
                "Display list still needs GPU primitives for " + api + ": " +
                    lowered.unsupported.joinToString()
            )
        }

        val frame = lowered.frame
        val kinds = IntArray(frame.commands.size)
        val geometry = FloatArray(frame.commands.size * 4)
        val colors = FloatArray(frame.commands.size * 4)
        val uvs = FloatArray(frame.commands.size * 4)

        frame.commands.forEachIndexed { index, command ->
            when (command) {
                is DrawCommand.Clear -> {
                    kinds[index] = 1
                    writeColor(colors, index, command.color.red, command.color.green, command.color.blue, command.color.alpha)
                }
                is DrawCommand.FillRect -> {
                    kinds[index] = 2
                    geometry[index * 4] = command.bounds.x
                    geometry[index * 4 + 1] = command.bounds.y
                    geometry[index * 4 + 2] = command.bounds.width
                    geometry[index * 4 + 3] = command.bounds.height
                    writeColor(colors, index, command.color.red, command.color.green, command.color.blue, command.color.alpha)
                }
            }
        }

        val status = NativeGpuBridge.present(
            handle,
            kinds,
            geometry,
            colors,
            uvs,
        )
        if (status == NativeGpuBridge.STATUS_SWAPCHAIN_OUT_OF_DATE) {
            return lowered.unsupported
        }
        check(status == NativeGpuBridge.STATUS_OK) {
            "Native GPU present failed with status " + status + " on " + api
        }
        return lowered.unsupported
    }

    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        NativeGpuBridge.destroy(current)
    }

    private fun writeColor(
        target: FloatArray,
        index: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        target[index * 4] = red
        target[index * 4 + 1] = green
        target[index * 4 + 2] = blue
        target[index * 4 + 3] = alpha
    }
}

class ComposeGpuWindowHost(
    width: Int,
    height: Int,
    title: String,
    density: Float = 1f,
    api: GraphicsApi = defaultJvmGpuApi(),
    private val strict: Boolean = true,
) : AutoCloseable {
    private val renderer = NativeGpuRendererJvm(width, height, title, api)
    private val scene = ComposeSceneCaptureHost(
        width = width,
        height = height,
        density = density,
        strictCanvas = strict,
    )
    private var closed = false

    fun setContent(content: @Composable () -> Unit) {
        check(!closed)
        scene.setContent(content)
    }

    fun run() {
        check(!closed)
        var previousSize = 0 to 0

        while (renderer.poll()) {
            val size = renderer.size()
            if (size.first <= 0 || size.second <= 0) {
                Thread.sleep(8)
                continue
            }
            if (size != previousSize) {
                scene.resize(size.first, size.second)
                previousSize = size
            }

            renderer.drainPointers { kind, x, y ->
                when (kind) {
                    1 -> scene.pointerPress(x, y, System.currentTimeMillis())
                    2 -> scene.pointerRelease(x, y, System.currentTimeMillis())
                    3 -> scene.pointerMove(x, y, System.currentTimeMillis())
                }
            }

            val captured = scene.render(System.nanoTime())
            if (strict && captured.unsupportedOperations.isNotEmpty()) {
                error(
                    "Compose emitted unsupported Canvas operations: " +
                        captured.unsupportedOperations.joinToString()
                )
            }
            renderer.render(captured.displayList, strict = strict)
            Thread.sleep(8)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            scene.close()
        } finally {
            renderer.close()
        }
    }
}

fun defaultJvmGpuApi(): GraphicsApi {
    val requested = System.getProperty(
        "kotlin.display.backend",
        System.getenv().getOrDefault("KD_BACKEND", "auto"),
    ).lowercase()

    if (requested != "auto") {
        return when (requested) {
            "metal" -> GraphicsApi.METAL
            "vulkan" -> GraphicsApi.VULKAN
            "opengl" -> GraphicsApi.OPENGL
            "d3d9" -> GraphicsApi.DIRECT3D9
            "gdi" -> GraphicsApi.GDI
            else -> error("Unknown Kotlin Display backend: " + requested)
        }
    }

    val os = System.getProperty("os.name", "").lowercase()
    return when {
        os.contains("mac") -> GraphicsApi.METAL
        os.contains("win") -> GraphicsApi.VULKAN
        else -> GraphicsApi.VULKAN
    }
}

private fun requestedBridgeId(api: GraphicsApi): Int {
    val requested = System.getProperty(
        "kotlin.display.backend",
        System.getenv().getOrDefault("KD_BACKEND", "auto"),
    ).lowercase()

    return if (requested == "auto") {
        NativeGpuBridge.AUTO
    } else {
        api.bridgeId()
    }
}

private fun GraphicsApi.bridgeId(): Int = when (this) {
    GraphicsApi.VULKAN -> NativeGpuBridge.VULKAN
    GraphicsApi.METAL -> NativeGpuBridge.METAL
    GraphicsApi.OPENGL -> NativeGpuBridge.OPENGL
    GraphicsApi.DIRECT3D9 -> NativeGpuBridge.D3D9
    GraphicsApi.GDI -> NativeGpuBridge.GDI
    else -> error("JVM bridge does not implement " + this)
}
