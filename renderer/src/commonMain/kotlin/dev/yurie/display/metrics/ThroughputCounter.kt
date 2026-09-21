package dev.yurie.display.metrics

data class ThroughputWindow(
    val operations: Long,
    val elapsedNanoseconds: Long,
    val averageCallNanoseconds: Long,
) {
    val perSecond: Double
        get() = operations.toDouble() * 1_000_000_000.0 / elapsedNanoseconds
}

class ThroughputCounter(private val sampleNanoseconds: Long = 1_000_000_000L) {
    private var started = 0L
    private var lastRecorded = 0L
    private var operations = 0L
    private var durationTotal = 0L

    init {
        require(sampleNanoseconds > 0L)
    }

    fun record(nowNanoseconds: Long, callNanoseconds: Long): ThroughputWindow? {
        require(nowNanoseconds >= lastRecorded)
        require(callNanoseconds >= 0L)
        require(durationTotal <= Long.MAX_VALUE - callNanoseconds)
        lastRecorded = nowNanoseconds
        operations++
        durationTotal += callNanoseconds
        val elapsed = nowNanoseconds - started
        if (elapsed < sampleNanoseconds) return null
        val window = ThroughputWindow(operations, elapsed, durationTotal / operations)
        started = nowNanoseconds
        operations = 0L
        durationTotal = 0L
        return window
    }
}
