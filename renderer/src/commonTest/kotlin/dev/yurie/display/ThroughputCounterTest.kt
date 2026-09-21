package dev.yurie.display

import dev.yurie.display.metrics.ThroughputCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ThroughputCounterTest {
    @Test fun computesRateAndActualCallDurationIndependently() {
        val counter = ThroughputCounter()
        assertNull(counter.record(500_000_000L, 10_000_000L))
        val result = counter.record(1_000_000_000L, 30_000_000L)!!
        assertEquals(2L, result.operations)
        assertEquals(2.0, result.perSecond)
        assertEquals(20_000_000L, result.averageCallNanoseconds)
    }

    @Test fun resetsForTheNextWindow() {
        val counter = ThroughputCounter(1_000L)
        assertNull(counter.record(100L, 20L))
        assertEquals(2L, counter.record(1_000L, 40L)!!.operations)
        assertNull(counter.record(1_500L, 70L))
        val result = counter.record(2_500L, 90L)!!
        assertEquals(2L, result.operations)
        assertEquals(1_500L, result.elapsedNanoseconds)
        assertEquals(80L, result.averageCallNanoseconds)
    }

    @Test fun rejectsInvalidInputs() {
        assertFailsWith<IllegalArgumentException> { ThroughputCounter(0L) }
        val counter = ThroughputCounter()
        assertFailsWith<IllegalArgumentException> { counter.record(-1L, 1L) }
        assertFailsWith<IllegalArgumentException> { counter.record(1L, -1L) }
        counter.record(500L, 1L)
        assertFailsWith<IllegalArgumentException> { counter.record(499L, 1L) }
    }
}
