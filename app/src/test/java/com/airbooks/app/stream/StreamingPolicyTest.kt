package com.airbooks.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPolicyTest {

    private fun input(
        throughput: Double = 0.0,
        mode: StreamingMode = StreamingMode.NORMAL,
        bitrate: Long = 0,
        rebuffers: Int = 0,
        lowerVariant: Boolean = false,
        maxWindow: Long = 64L * 1024 * 1024,
    ) = PolicyInput(throughput, 50, bitrate, 0, rebuffers, 0, mode, lowerVariant, maxWindow)

    // Acceptance 5/6: estimator is bounded and stable.
    @Test
    fun estimatorConvergesAndClampsAbsurdSamples() {
        val e = ThroughputEstimator(alpha = 0.5)
        e.addSample(bytes = 1_000_000, durationMs = 1_000) // 8 Mbps
        assertEquals(8_000_000.0, e.ewmaBps, 1.0)
        e.addSample(bytes = 10_000_000_000L, durationMs = 1) // absurd -> clamped to 200 Mbps
        assertTrue(e.ewmaBps < 200_000_000.0)
        assertTrue(e.ewmaBps > 8_000_000.0)
        e.addSample(bytes = 0, durationMs = 100) // ignored
        assertEquals(2, e.sampleCount)
    }

    // Acceptance 9/2: repeated seeks inside the window never re-request; a seek outside re-plans
    // exactly one new range, and repeating the same plan is de-duplicated.
    @Test
    fun rangeTrackerDeduplicatesAndReplansOnSeek() {
        val t = RangeTracker()
        val size = 100L * 1024 * 1024
        val first = t.plan(0, 8L * 1024 * 1024, size)
        assertEquals(0L, first!!.first)
        assertNull("same range must not be re-requested", t.plan(1024, 8L * 1024 * 1024, size))
        assertNull(t.plan(4L * 1024 * 1024, 8L * 1024 * 1024, size)) // comfortably inside
        val seek = t.plan(50L * 1024 * 1024, 8L * 1024 * 1024, size)
        assertTrue(seek!!.first in 49L * 1024 * 1024..50L * 1024 * 1024)
        assertNull(t.plan(50L * 1024 * 1024 + 10, 8L * 1024 * 1024, size))
        // Backward seek also re-plans.
        assertEquals(0L, t.plan(0, 8L * 1024 * 1024, size)!!.first)
        // EOF: nothing to plan at/past end.
        assertNull(t.plan(size, 8L * 1024 * 1024, size))
        assertNull(t.plan(0, 8L * 1024 * 1024, 0))
    }

    // Acceptance 6: backoff is bounded and retries are capped (no endless retry loops).
    @Test
    fun backoffIsBoundedAndMonotonic() {
        var prev = -1L
        for (attempt in 0..20) {
            val d = Backoff.delayMs(attempt, StreamingMode.LOW_DATA)
            assertTrue(d >= prev)
            assertTrue(d <= StreamingConfig.BACKOFF_CAP_MS)
            prev = d
        }
        assertEquals(StreamingConfig.BACKOFF_CAP_MS, Backoff.delayMs(50, StreamingMode.NORMAL))
        assertTrue(Backoff.maxRetries(StreamingMode.LOW_DATA) <= Backoff.maxRetries(StreamingMode.NORMAL))
    }

    // Acceptance 4: Low Data really reduces prefetch and background work; Normal keeps more.
    @Test
    fun lowDataReducesPrefetchVsNormal() {
        val slow = 300_000.0
        val normal = PlaybackPolicy().decide(input(slow, StreamingMode.NORMAL))
        val low = PlaybackPolicy().decide(input(slow, StreamingMode.LOW_DATA))
        assertTrue(low.prefetchBytes < normal.prefetchBytes)
        assertFalse(low.allowBackgroundPrefetch)
        assertTrue(low.bufferForPlaybackMs <= normal.bufferForPlaybackMs)
        assertTrue(low.maxBufferMs <= normal.maxBufferMs)
    }

    // Acceptance 5: on a fast link Normal retains the largest read-ahead; no fake variant switch
    // is claimed when no lower-quality source exists.
    @Test
    fun normalOnFastNetworkKeepsLargestWindowAndNeverFakesVariants() {
        val fast = PlaybackPolicy().decide(input(50_000_000.0, lowerVariant = false))
        assertEquals(ThroughputBand.HIGH, fast.band)
        assertEquals(StreamingConfig.NORMAL_WINDOW_MAX_BYTES, fast.prefetchBytes)
        assertFalse(fast.preferLowerVariant)
        val withVariant = PlaybackPolicy().decide(input(100_000.0, lowerVariant = true))
        assertTrue(withVariant.preferLowerVariant)
    }

    // Hysteresis: throughput hovering just above a boundary must not flip the band every call.
    @Test
    fun hysteresisPreventsBandFlapping() {
        val p = PlaybackPolicy()
        assertEquals(ThroughputBand.MEDIUM, p.decide(input(1_000_000.0)).band)
        // 2.6 Mbps is above the 2.5 Mbps MEDIUM/HIGH boundary but within the +25% margin.
        assertEquals(ThroughputBand.MEDIUM, p.decide(input(2_600_000.0)).band)
        assertEquals(ThroughputBand.HIGH, p.decide(input(4_000_000.0)).band)
        // Dropping slightly below the boundary keeps HIGH until -20% is crossed.
        assertEquals(ThroughputBand.HIGH, p.decide(input(2_400_000.0)).band)
        assertEquals(ThroughputBand.MEDIUM, p.decide(input(1_500_000.0)).band)
    }

    // Unknown bitrate/throughput -> conservative defaults, bounded rebuffer protection.
    @Test
    fun unknownNetworkIsConservativeAndRebufferProtectionIsBounded() {
        val p = PlaybackPolicy()
        val d0 = p.decide(input(0.0, rebuffers = 0))
        val dMany = PlaybackPolicy().decide(input(0.0, rebuffers = 10_000))
        assertEquals(ThroughputBand.UNKNOWN, d0.band)
        assertTrue(dMany.bufferForPlaybackAfterRebufferMs - d0.bufferForPlaybackAfterRebufferMs <= 5_000)
    }

    // User's max-window cap from Settings is honoured.
    @Test
    fun userWindowCapIsHonoured() {
        val d = PlaybackPolicy().decide(input(50_000_000.0, maxWindow = 4L * 1024 * 1024))
        assertEquals(4L * 1024 * 1024, d.prefetchBytes)
    }
}
