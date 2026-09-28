package dev.yurie.display.demo

import dev.yurie.display.GraphicsApi
import dev.yurie.display.ui.UiState
import dev.yurie.display.ui.runNativeUi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.native.OsFamily
import kotlin.time.TimeSource

@OptIn(ExperimentalNativeApi::class)
fun main(args: Array<String>) {
    require(args.size <= 1 && (args.isEmpty() || args[0] in setOf("--gdi", "--vulkan", "--metal", "--opengl", "--d3d9"))) {
        "Usage: executable [--gdi|--vulkan|--metal|--opengl|--d3d9]"
    }
    val backend = when (args.firstOrNull()) {
        "--gdi" -> GraphicsApi.GDI
        "--metal" -> GraphicsApi.METAL
        "--opengl" -> GraphicsApi.OPENGL
        "--d3d9" -> GraphicsApi.DIRECT3D9
        "--vulkan" -> GraphicsApi.VULKAN
        else -> if (Platform.osFamily == OsFamily.MACOSX) GraphicsApi.METAL else GraphicsApi.VULKAN
    }
    val queued = UiState(3)
    val paused = UiState(false)
    val compact = UiState(false)
    val spinning = UiState(true)
    val rotation = UiState(0f)
    val commandCount = UiState(0)
    val meter = FrameRateMeter()
    val clock = TimeSource.Monotonic.markNow()
    var previousTick = 0L

    runNativeUi(960, 540, "Queue Desk | ${backend.name}", backend = backend, configure = {
        watch(queued)
        watch(paused)
        watch(compact)
        watch(spinning)
        watch(rotation)
        watch(commandCount)
        watch(meter.display)
        watch(meter.percentile)
    }, onDrawCommands = { count ->
        commandCount.value = count
    }, onFrameMeasured = { durationNs ->
        meter.presented(durationNs)
        val now = clock.elapsedNow().inWholeNanoseconds
        if (spinning.value && previousTick > 0L) {
            val delta = ((now - previousTick).coerceAtLeast(0L).toDouble() / 1_000_000_000.0).coerceAtMost(0.1)
            rotation.value = (rotation.value + delta.toFloat() * 1.2f) % 6.2831855f
        }
        previousTick = now
    }) { width, height ->
        queueDeskScene(width, height, queued, paused, compact, spinning, rotation, commandCount, meter)
    }
}
