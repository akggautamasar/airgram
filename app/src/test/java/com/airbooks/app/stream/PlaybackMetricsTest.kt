package com.airbooks.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Acceptance §6 measurements (pure aggregation) + audio-sized prebuffer targets (§5). */
class PlaybackMetricsTest {

    @Test
    fun startupIsMeasuredOncePerSession() {
        val m = PlaybackMetrics()
        m.startSession(1_000)
        m.markBuffering(1_100)
        m.markReady(2_600) // startup = first READY - session start
        assertEquals(1_600, m.startupMs)
        m.markBuffering(10_000)
        m.markReady(11_000) // later READY must not overwrite startup
        assertEquals(1_600, m.startupMs)
    }

    @Test
    fun bufferingDurationsAccumulateAcrossCycles() {
        val m = PlaybackMetrics()
        m.startSession(0)
        m.markBuffering(100)
        m.markReady(1_600) // 1_500 ms
        m.markBuffering(20_000)
        m.markReady(20_800) // + 800 ms
        assertEquals(2_300, m.totalBufferingMs)
        // Nested/duplicate transitions never double-count or go negative.
        m.markBuffering(30_000)
        m.markBuffering(30_500)
        m.markReady(31_000)
        assertEquals(3_300, m.totalBufferingMs)
    }

    @Test
    fun sessionStartResetsAllCounters() {
        val m = PlaybackMetrics()
        m.startSession(0)
        m.markBuffering(0)
        m.markReady(900)
        m.noteBytesFetched(5_000)
        m.startSession(10_000)
        assertEquals(-1L, m.startupMs)
        assertEquals(0L, m.totalBufferingMs)
        assertEquals(0L, m.bytesFetched)
    }

    @Test
    fun bytesFetchedCountsOnlyForwardGrowth() {
        val m = PlaybackMetrics()
        m.noteBytesFetched(1_000)
        m.noteBytesFetched(2_500)
        m.noteBytesFetched(-800) // window move / seek overlap: ignored, never negative
        m.noteBytesFetched(0)
        assertEquals(3_500, m.bytesFetched)
    }

    @Test
    fun prebufferSecondsConvertsWithBitrateAndIsHonestWhenUnknown() {
        // 12 MiB ahead at 1.5 Mbps = 12*1024*1024*8 / 1_500_000 = 67 s of media.
        assertEquals(67, PlaybackMetrics.prebufferSeconds(12L * 1024 * 1024, 1_500_000))
        assertEquals(-1L, PlaybackMetrics.prebufferSeconds(1_000, 0)) // bitrate unknown
        assertEquals(-1L, PlaybackMetrics.prebufferSeconds(0, 1_000_000)) // nothing ahead
    }

    // §5: audio-appropriate targets — the time-cap adaptation shrinks Extreme's 96 MB video
    // ceiling to a few MB for a typical 128 kbps track, so audio never over-fetches.
    @Test
    fun audioTargetsAreAudioSizedInEveryMode() {
        val audioBitrate = 128_000L
        val low = Prebuffer.targetBytes(StreamingMode.LOW_DATA, audioBitrate, 0.0, 0, 0)
        val normal = Prebuffer.targetBytes(StreamingMode.NORMAL, audioBitrate, 0.0, 0, 0)
        val extreme = Prebuffer.targetBytes(StreamingMode.EXTREME_LOW_DATA, audioBitrate, 0.0, 0, 0)
        assertEquals(480_000L, low) // 30 s of audio
        assertEquals(1_440_000L, normal) // 90 s
        assertEquals(4_800_000L, extreme) // 300 s — nowhere near the video byte cap
        assertTrue(extreme < StreamingConfig.PAUSE_PREBUFFER_EXTREME_BYTES)
        assertTrue(low < normal && normal < extreme)
    }
}
