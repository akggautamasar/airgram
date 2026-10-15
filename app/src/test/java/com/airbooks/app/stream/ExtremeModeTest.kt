package com.airbooks.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three-mode streaming system: Normal, Low Data, Extreme Low Data.
 * Covers the pause-prebuffer targets, the extreme start/resume gate, per-mode patience and
 * the guarantee that Extreme never behaves like a full-file downloader (targets stay bounded).
 */
class ExtremeModeTest {

    private fun input(
        throughput: Double = 0.0,
        mode: StreamingMode,
        bitrate: Long = 0,
        rebuffers: Int = 0,
        maxWindow: Long = 64L * 1024 * 1024,
        fileSize: Long = 0,
    ) = PolicyInput(throughput, 50L, bitrate, 0L, rebuffers, 0L, mode, false, maxWindow, fileSize)

    private val MB = 1024L * 1024

    // Mode is persisted by enum name (Prefs) — all three must round-trip and stay distinct.
    @Test
    fun allThreeModesRoundTripAndAreDistinct() {
        val modes = StreamingMode.values()
        assertEquals(3, modes.size)
        modes.forEach { assertEquals(it, StreamingMode.valueOf(it.name)) }
        assertEquals(3, modes.map { it.label }.distinct().size)
    }

    // Pause-to-buffer targets: Low < Normal < Extreme, all bounded (never "download everything").
    @Test
    fun prebufferTargetsAreOrderedAndBounded() {
        val low = Prebuffer.targetBytes(StreamingMode.LOW_DATA, 0, 0.0, 0, 0)
        val normal = Prebuffer.targetBytes(StreamingMode.NORMAL, 0, 0.0, 0, 0)
        val extreme = Prebuffer.targetBytes(StreamingMode.EXTREME_LOW_DATA, 0, 0.0, 0, 0)
        assertTrue("low < normal", low < normal)
        assertTrue("normal < extreme", normal < extreme)
        assertEquals(StreamingConfig.PAUSE_PREBUFFER_EXTREME_BYTES, extreme)
        assertTrue(extreme <= 96 * MB)
    }

    // The Normal-mode user cap (Settings slider) bounds Normal's pause target; Extreme keeps
    // its own internal bound, Low Data its smaller one.
    @Test
    fun prebufferRespectsNormalUserCapOnly() {
        val capped = Prebuffer.targetBytes(StreamingMode.NORMAL, 0, 0.0, 0, 8 * MB)
        assertEquals(8 * MB, capped)
        val extreme = Prebuffer.targetBytes(StreamingMode.EXTREME_LOW_DATA, 0, 0.0, 0, 8 * MB)
        assertEquals(StreamingConfig.PAUSE_PREBUFFER_EXTREME_BYTES, extreme)
    }

    // Targets adapt to the media bitrate: a 1 Mbps file needs far less than the byte cap
    // for the same prebuffered playtime, and ordering across modes still holds.
    @Test
    fun prebufferAdaptsToMediaBitrate() {
        val bitrate = 1_000_000L // 1 Mbps
        val low = Prebuffer.targetBytes(StreamingMode.LOW_DATA, bitrate, 0.0, 0, 0)
        val normal = Prebuffer.targetBytes(StreamingMode.NORMAL, bitrate, 0.0, 0, 0)
        val extreme = Prebuffer.targetBytes(StreamingMode.EXTREME_LOW_DATA, bitrate, 0.0, 0, 0)
        assertEquals(3_750_000L, low) // 30 s at 125 kB/s
        assertEquals(11_250_000L, normal) // 90 s
        assertEquals(37_500_000L, extreme) // 300 s — below the 96 MB byte cap
        assertTrue(low < normal && normal < extreme)
    }

    // Targets adapt to measured throughput: on a hopeless link the target shrinks to what the
    // network can actually deliver (4x head-room over the mode's prebuffer seconds).
    @Test
    fun prebufferAdaptsToMeasuredThroughput() {
        val slow = 100_000.0 // 100 kbps
        val extreme = Prebuffer.targetBytes(StreamingMode.EXTREME_LOW_DATA, 0, slow, 0, 0)
        assertEquals(15_000_000L, extreme) // 100k/8 * 300 * 4
        assertTrue(extreme < StreamingConfig.PAUSE_PREBUFFER_EXTREME_BYTES)
    }

    // A small file is never over-targeted.
    @Test
    fun prebufferNeverExceedsFileSize() {
        val t = Prebuffer.targetBytes(StreamingMode.EXTREME_LOW_DATA, 0, 0.0, 2 * MB, 0)
        assertEquals(2 * MB, t)
    }

    // Extreme = continuity over startup: deepest start/resume buffers, biggest windows,
    // background prefetch always allowed (that is what pause-to-buffer needs).
    @Test
    fun extremePolicyPrioritizesContinuityOverStartup() {
        val normal = PlaybackPolicy().decide(input(0.0, StreamingMode.NORMAL))
        val extreme = PlaybackPolicy().decide(input(0.0, StreamingMode.EXTREME_LOW_DATA))
        assertTrue(extreme.bufferForPlaybackMs > normal.bufferForPlaybackMs)
        assertTrue(extreme.bufferForPlaybackAfterRebufferMs > normal.bufferForPlaybackAfterRebufferMs)
        assertTrue(extreme.maxBufferMs > normal.maxBufferMs)
        assertTrue(extreme.prefetchBytes > normal.prefetchBytes)
        assertTrue(extreme.pausePrebufferBytes > normal.pausePrebufferBytes)
        assertTrue(extreme.allowBackgroundPrefetch)
        // Even on a measured-slow link Extreme keeps prebuffering while paused.
        val extremeLowBand = PlaybackPolicy().decide(input(100_000.0, StreamingMode.EXTREME_LOW_DATA))
        assertEquals(ThroughputBand.LOW, extremeLowBand.band)
        assertTrue(extremeLowBand.allowBackgroundPrefetch)
        val normalLowBand = PlaybackPolicy().decide(input(100_000.0, StreamingMode.NORMAL))
        assertFalse(normalLowBand.allowBackgroundPrefetch)
    }

    // Low Data stays the leanest mode on every axis.
    @Test
    fun lowDataRemainsLeanestAcrossAllThree() {
        val low = PlaybackPolicy().decide(input(0.0, StreamingMode.LOW_DATA))
        val normal = PlaybackPolicy().decide(input(0.0, StreamingMode.NORMAL))
        val extreme = PlaybackPolicy().decide(input(0.0, StreamingMode.EXTREME_LOW_DATA))
        assertTrue(low.prefetchBytes < normal.prefetchBytes)
        assertTrue(low.pausePrebufferBytes < extreme.pausePrebufferBytes)
        assertTrue(low.bufferForPlaybackMs <= normal.bufferForPlaybackMs)
        assertFalse(low.allowBackgroundPrefetch)
    }

    // Extreme is the most patient: most retries, longest stall tolerance, slowest-growing but
    // still bounded backoff.
    @Test
    fun extremeIsMostPatientButStillBounded() {
        assertTrue(Backoff.maxRetries(StreamingMode.LOW_DATA) < Backoff.maxRetries(StreamingMode.NORMAL))
        assertTrue(Backoff.maxRetries(StreamingMode.NORMAL) < Backoff.maxRetries(StreamingMode.EXTREME_LOW_DATA))
        assertTrue(Backoff.stallTimeoutMs(StreamingMode.EXTREME_LOW_DATA) > Backoff.stallTimeoutMs(StreamingMode.LOW_DATA))
        var prev = -1L
        for (attempt in 0..20) {
            val d = Backoff.delayMs(attempt, StreamingMode.EXTREME_LOW_DATA)
            assertTrue(d >= prev)
            assertTrue(d <= StreamingConfig.BACKOFF_CAP_MS)
            prev = d
        }
    }

    // Start gate: only Extreme holds playback below its start target; release on resume target,
    // at end-of-stream, or when the user leaves Extreme mode.
    @Test
    fun startGateHoldsOnlyExtremeAndReleasesCorrectly() {
        val ahead = 2_000L
        val start = 10_000L
        val resume = 15_000L
        assertTrue(StartGate.shouldHoldStart(StreamingMode.EXTREME_LOW_DATA, ahead, start, atEnd = false))
        assertFalse(StartGate.shouldHoldStart(StreamingMode.NORMAL, ahead, start, atEnd = false))
        assertFalse(StartGate.shouldHoldStart(StreamingMode.LOW_DATA, ahead, start, atEnd = false))
        assertFalse(StartGate.shouldHoldStart(StreamingMode.EXTREME_LOW_DATA, ahead, start, atEnd = true))
        assertFalse(StartGate.shouldReleaseHold(StreamingMode.EXTREME_LOW_DATA, ahead, resume, atEnd = false))
        assertTrue(StartGate.shouldReleaseHold(StreamingMode.EXTREME_LOW_DATA, resume, resume, atEnd = false))
        assertTrue(StartGate.shouldReleaseHold(StreamingMode.EXTREME_LOW_DATA, ahead, resume, atEnd = true))
        assertTrue(StartGate.shouldReleaseHold(StreamingMode.NORMAL, ahead, resume, atEnd = false))
    }

    // The prebuffer pump's de-duplication primitive: identical ranges are never re-issued.
    @Test
    fun adoptOrPlanDeduplicatesPumpRequests() {
        val t = RangeTracker()
        assertTrue(t.adoptOrPlan(0, 8 * MB))
        assertFalse("identical range must not be re-requested", t.adoptOrPlan(0, 8 * MB))
        assertTrue(t.adoptOrPlan(8 * MB, 16 * MB)) // pump extended the window
        assertTrue(t.covers(10 * MB))
        assertFalse(t.covers(20 * MB))
    }

    // Decision exposes a usable pause target for every mode (the pump reads exactly this).
    @Test
    fun everyDecisionCarriesAPositivePauseTarget() {
        StreamingMode.values().forEach { m ->
            val d = PlaybackPolicy().decide(input(0.0, m, fileSize = 500 * MB))
            assertTrue("${m.name} target", d.pausePrebufferBytes > 0)
            assertTrue(d.pausePrebufferBytes <= 500 * MB)
        }
    }

    // LoadedPrefix fraction math guards the seek-bar rendering (0..1, honest at edges).
    @Test
    fun loadedPrefixFractionIsClampedAndHonest() {
        assertEquals(0f, LoadedPrefix.EMPTY.fraction, 0f)
        assertEquals(0.5f, LoadedPrefix(1, 0, 50, 100, false).fraction, 0.0001f)
        assertEquals(1f, LoadedPrefix(1, 0, 150, 100, true).fraction, 0f) // clamped, complete
    }
}
