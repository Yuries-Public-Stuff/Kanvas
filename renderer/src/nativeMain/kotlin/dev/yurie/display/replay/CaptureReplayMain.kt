package dev.yurie.display.replay

import dev.yurie.display.GraphicsApi
import dev.yurie.display.NativeGpuRenderer
import dev.yurie.display.scene.ComposeCaptureParser
import dev.yurie.display.scene.GpuPrimitiveLowerer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.usleep

@OptIn(ExperimentalForeignApi::class)
private fun readText(path: String): String = memScoped {
    val file = fopen(path, "rb") ?: error("Could not open capture: " + path)
    try {
        check(fseek(file, 0, SEEK_END) == 0) { "Could not seek capture" }
        val length = ftell(file)
        require(length >= 0) { "Could not size capture" }
        check(fseek(file, 0, SEEK_SET) == 0) { "Could not rewind capture" }
        val size = length.toInt()
        val bytes = allocArray<ByteVar>(size + 1)
        val read = fread(bytes, 1u, size.toULong(), file).toInt()
        check(read == size) { "Capture read was truncated: " + read + "/" + size }
        bytes[size] = 0
        bytes.toKString()
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalNativeApi::class)
private fun backend(args: Array<String>): GraphicsApi {
    val selected = args.firstOrNull { it.startsWith("--backend=") }
        ?.substringAfter('=')
        ?.lowercase()
    return when (selected) {
        "metal" -> GraphicsApi.METAL
        "vulkan" -> GraphicsApi.VULKAN
        "opengl" -> GraphicsApi.OPENGL
        "d3d9" -> GraphicsApi.DIRECT3D9
        "gdi" -> GraphicsApi.GDI
        null -> if (Platform.osFamily == OsFamily.MACOSX) GraphicsApi.METAL else GraphicsApi.VULKAN
        else -> error("Unknown backend: " + selected)
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
fun main(args: Array<String>) {
    require(args.isNotEmpty()) {
        "Usage: captureReplay <capture.kdcap> [--backend=metal|vulkan|opengl|d3d9|gdi]"
    }

    val path = args.first { !it.startsWith("--") }
    val frames = ComposeCaptureParser.parse(readText(path))
    require(frames.isNotEmpty()) { "No complete frames found in " + path }

    val api = backend(args)
    val first = frames.first().displayList
    val renderer = NativeGpuRenderer(
        width = first.width,
        height = first.height,
        title = "Kotlin Display | Compose capture | " + api,
        api = api,
    )

    val unsupported = linkedSetOf<String>()
    try {
        frames.forEach { captured ->
            if (!renderer.poll()) return
            val lowered = GpuPrimitiveLowerer.lower(captured.displayList)
            unsupported += captured.unsupportedOperations
            unsupported += lowered.unsupported
            renderer.render(lowered.frame)
            usleep(16_000u)
        }

        println("Replayed " + frames.size + " captured Compose frames through " + api + ".")
        if (unsupported.isNotEmpty()) {
            println("Unsupported operations observed: " + unsupported.joinToString())
        }

        val last = GpuPrimitiveLowerer.lower(frames.last().displayList).frame
        while (renderer.poll()) {
            renderer.render(last)
            usleep(16_000u)
        }
    } finally {
        renderer.close()
    }
}
