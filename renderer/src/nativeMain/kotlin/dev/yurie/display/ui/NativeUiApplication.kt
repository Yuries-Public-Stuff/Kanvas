package dev.yurie.display.ui

import dev.yurie.display.GraphicsApi
import dev.yurie.display.NativeGpuRenderer
import dev.yurie.display.nativebridge.KD_OK
import dev.yurie.display.nativebridge.kd_telemetry_close
import dev.yurie.display.nativebridge.kd_telemetry_open
import dev.yurie.display.nativebridge.kd_telemetry_record
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
fun runNativeUi(
    width: Int,
    height: Int,
    title: String,
    backend: GraphicsApi = GraphicsApi.VULKAN,
    configure: UiSceneHost.() -> Unit = {},
    onPresented: () -> Unit = {},
    onFrameMeasured: (Long) -> Unit = {},
    onDrawCommands: (Int) -> Unit = {},
    scene: (width: Int, height: Int) -> UiScene,
) {
    val renderer = NativeGpuRenderer(width, height, title, backend)
    val host = UiSceneHost(scene)
    val clock = TimeSource.Monotonic.markNow()
    var previousCompletion = 0L
    try {
        host.configure()
        check(kd_telemetry_open(backend.name.lowercase()) == KD_OK) { "Could not open frame telemetry CSV" }
        while (renderer.poll()) {
            val (currentWidth, currentHeight) = renderer.size()
            if (currentWidth == 0 || currentHeight == 0) continue
            val sceneStart = clock.elapsedNow().inWholeNanoseconds
            host.frame(currentWidth, currentHeight)
            renderer.dispatchPointerDown(host, currentWidth, currentHeight)
            val frame = host.frame(currentWidth, currentHeight)
            val renderStart = clock.elapsedNow().inWholeNanoseconds
            if (!renderer.present(frame)) continue
            val completion = clock.elapsedNow().inWholeNanoseconds
            val renderDuration = completion - renderStart
            val interval = if (previousCompletion == 0L) 0L else completion - previousCompletion
            previousCompletion = completion
            check(kd_telemetry_record(completion.toULong(), interval.toULong(),
                (renderStart - sceneStart).toULong(), renderDuration.toULong(),
                frame.commands.size.toUInt(), currentWidth.toUInt(), currentHeight.toUInt()) == KD_OK) {
                "Frame telemetry write failed"
            }
            onDrawCommands(frame.commands.size)
            onFrameMeasured(renderDuration)
            onPresented()
        }
    } finally {
        kd_telemetry_close()
        try {
            host.close()
        } finally {
            renderer.close()
        }
    }
}
