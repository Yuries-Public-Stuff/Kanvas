package dev.yurie.display.demo

import dev.yurie.display.metrics.ThroughputCounter
import dev.yurie.display.ui.UiState
import kotlin.time.TimeSource

class FrameRateMeter {
    private val clock = TimeSource.Monotonic.markNow()
    private val counter = ThroughputCounter()
    private val callSamples = mutableListOf<Long>()
    val display = UiState("FPS -- CALL --.- MS")
    val percentile = UiState("P95 --.- MS")

    fun presented(callDurationNs: Long) {
        callSamples += callDurationNs
        val sample = counter.record(clock.elapsedNow().inWholeNanoseconds, callDurationNs) ?: return
        val rate = (sample.perSecond + 0.5).toInt()
        val tenths = (sample.averageCallNanoseconds / 100_000L).toInt()
        display.value = "FPS $rate CALL ${tenths / 10}.${tenths % 10} MS"
        callSamples.sort()
        val rank = ((callSamples.size * 95 + 99) / 100 - 1).coerceIn(0, callSamples.lastIndex)
        val p95 = (callSamples[rank] / 100_000L).toInt()
        percentile.value = "P95 ${p95 / 10}.${p95 % 10} MS"
        callSamples.clear()
    }
}
