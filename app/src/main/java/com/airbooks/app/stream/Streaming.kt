package com.airbooks.app.stream

import kotlin.math.min

/**
 * Streaming policy: pure Kotlin, no Android dependencies, so it can be unit-tested on the JVM.
 *
 * AirGram streams from Telegram/TDLib, which does NOT offer HTTP-Range semantics. TDLib can be
 * asked to download a byte window (`DownloadFile(fileId, priority, offset, limit)`) and reports
 * one contiguous downloaded prefix for that window (`local.downloadOffset` +
 * `local.downloadedPrefixSize`). [RangeTracker] decides when that window must move so repeated
 * reads/seeks never issue duplicate requests for a range that is already being fetched.
 */

enum class StreamingMode(val label: String) {
    NORMAL("Normal Streaming"),
    LOW_DATA("Low Data Streaming"),
}

/** All tunable thresholds live here instead of being scattered as magic numbers. */
object StreamingConfig {
    const val RANGE_ALIGN_BYTES = 512L * 1024

    // Window (read-ahead) sizes requested from TDLib.
    const val NORMAL_WINDOW_MAX_BYTES = 32L * 1024 * 1024
    const val NORMAL_WINDOW_MED_BYTES = 16L * 1024 * 1024
    const val NORMAL_WINDOW_MIN_BYTES = 8L * 1024 * 1024
    const val LOW_WINDOW_MAX_BYTES = 8L * 1024 * 1024
    const val LOW_WINDOW_MED_BYTES = 4L * 1024 * 1024
    const val LOW_WINDOW_MIN_BYTES = 2L * 1024 * 1024

    const val MAX_RETRIES_NORMAL = 5
    const val MAX_RETRIES_LOW_DATA = 4
    const val BACKOFF_BASE_NORMAL_MS = 400L
    const val BACKOFF_BASE_LOW_DATA_MS = 1_000L
    const val BACKOFF_CAP_MS = 30_000L

    const val STALL_TIMEOUT_NORMAL_MS = 60_000L
    const val STALL_TIMEOUT_LOW_DATA_MS = 90_000L

    // Throughput bands (bits/s) used when the media bitrate is unknown.
    const val BAND_LOW_MAX_BPS = 500_000.0
    const val BAND_MED_MAX_BPS = 2_500_000.0
    const val HYSTERESIS_UP = 1.25
    const val HYSTERESIS_DOWN = 0.80
}

/**
 * Bounded EWMA throughput estimator. Individual samples are clamped so one absurd read cannot
 * poison the estimate, and the average moves slowly so policy does not flap.
 */
class ThroughputEstimator(private val alpha: Double = 0.25) {
    @Volatile var ewmaBps: Double = 0.0
        private set
    @Volatile var lastSampleBps: Double = 0.0
        private set
    @Volatile var sampleCount: Int = 0
        private set

    @Synchronized
    fun addSample(bytes: Long, durationMs: Long) {
        if (bytes <= 0 || durationMs <= 0) return
        val inst = (bytes * 8_000.0 / durationMs).coerceIn(1_000.0, 200_000_000.0)
        lastSampleBps = inst
        ewmaBps = if (sampleCount == 0) inst else ewmaBps + alpha * (inst - ewmaBps)
        sampleCount++
    }

    @Synchronized
    fun reset() {
        ewmaBps = 0.0; lastSampleBps = 0.0; sampleCount = 0
    }
}

/** Shared, process-wide diagnostics so the player/settings can show what streaming is doing. */
object StreamingRuntime {
    val estimator = ThroughputEstimator()
    @Volatile var playbackActive: Boolean = false
    @Volatile var activeMode: StreamingMode = StreamingMode.NORMAL
    @Volatile var activeFileId: Int = 0
    @Volatile var rebufferCount: Int = 0
    @Volatile var lastError: String? = null
}

/**
 * Tracks the single TDLib download window currently requested for a file and decides when it
 * must move. Guarantees: no duplicate request for the identical range, no re-request while the
 * read position is comfortably inside the window, and an immediate re-plan on seek outside it.
 */
class RangeTracker {
    var requestedStart: Long = -1L
        private set
    var requestedEnd: Long = -1L
        private set

    /**
     * Returns the [start, end) range to request, or null if the current window already covers
     * [position] with enough read-ahead. Thread-safe; safe to call on every read.
     */
    @Synchronized
    fun plan(position: Long, windowBytes: Long, fileSize: Long): LongRange? {
        if (fileSize <= 0 || windowBytes <= 0 || position >= fileSize) return null
        val lowWatermark = windowBytes / 3
        val need = requestedStart < 0 || position < requestedStart || position >= requestedEnd ||
            (requestedEnd - position < lowWatermark && requestedEnd < fileSize)
        if (!need) return null
        val align = StreamingConfig.RANGE_ALIGN_BYTES
        val start = position - (position % align)
        val end = min(fileSize, start + windowBytes)
        if (start == requestedStart && end == requestedEnd) return null // already in flight
        requestedStart = start
        requestedEnd = end
        return start until end
    }

    @Synchronized
    fun invalidate() {
        requestedStart = -1L
        requestedEnd = -1L
    }

    @Synchronized
    fun covers(position: Long): Boolean =
        requestedStart >= 0 && position in requestedStart until requestedEnd
}

/** Bounded exponential backoff: delay grows per attempt but never exceeds the cap. */
object Backoff {
    fun delayMs(attempt: Int, mode: StreamingMode): Long {
        val base = if (mode == StreamingMode.LOW_DATA) {
            StreamingConfig.BACKOFF_BASE_LOW_DATA_MS
        } else {
            StreamingConfig.BACKOFF_BASE_NORMAL_MS
        }
        val shift = attempt.coerceIn(0, 10)
        return min(StreamingConfig.BACKOFF_CAP_MS, base shl shift)
    }

    fun maxRetries(mode: StreamingMode): Int =
        if (mode == StreamingMode.LOW_DATA) StreamingConfig.MAX_RETRIES_LOW_DATA
        else StreamingConfig.MAX_RETRIES_NORMAL

    fun stallTimeoutMs(mode: StreamingMode): Long =
        if (mode == StreamingMode.LOW_DATA) StreamingConfig.STALL_TIMEOUT_LOW_DATA_MS
        else StreamingConfig.STALL_TIMEOUT_NORMAL_MS
}

enum class ThroughputBand { UNKNOWN, LOW, MEDIUM, HIGH }

data class PolicyInput(
    val throughputBps: Double,
    val avgLatencyMs: Long,
    val mediaBitrateBps: Long, // 0 = unknown
    val bufferedMs: Long,
    val rebufferCount: Int,
    val positionMs: Long,
    val mode: StreamingMode,
    val hasLowerQualityVariant: Boolean,
    val maxWindowBytes: Long, // user cap (Settings "streaming prefetch")
)

data class PolicyDecision(
    val band: ThroughputBand,
    val minBufferMs: Long,
    val maxBufferMs: Long,
    val bufferForPlaybackMs: Long,
    val bufferForPlaybackAfterRebufferMs: Long,
    val prefetchBytes: Long,
    val maxRetries: Int,
    val preferLowerVariant: Boolean,
    val allowBackgroundPrefetch: Boolean,
    val explanation: String,
)

/**
 * Stateful policy with hysteresis: the throughput band only changes after throughput crosses a
 * threshold by a margin, so small fluctuations do not continually change buffering behaviour.
 * One instance per playback session. Not a bitrate/resolution switcher: when Telegram provides
 * a single fixed-quality file, [PolicyDecision.preferLowerVariant] is only a preference signal
 * and is false unless a lower variant genuinely exists.
 */
class PlaybackPolicy {
    private var band: ThroughputBand = ThroughputBand.UNKNOWN

    @Synchronized
    fun decide(input: PolicyInput): PolicyDecision {
        band = nextBand(band, input)
        val low = input.mode == StreamingMode.LOW_DATA
        // Repeated rebuffering buys extra protection, but bounded (never grows without limit).
        val rebufferPenalty = min(input.rebufferCount, 4).toLong()

        val (minB, maxB, startB, reB, prefetch) = when {
            low -> when (band) {
                ThroughputBand.HIGH -> longArrayOf(8_000, 30_000, 750, 1_500, StreamingConfig.LOW_WINDOW_MAX_BYTES)
                ThroughputBand.MEDIUM -> longArrayOf(10_000, 30_000, 1_000, 2_500, StreamingConfig.LOW_WINDOW_MED_BYTES)
                else -> longArrayOf(12_000, 45_000, 1_250, 4_000 + rebufferPenalty * 500, StreamingConfig.LOW_WINDOW_MIN_BYTES)
            }
            else -> when (band) {
                ThroughputBand.HIGH -> longArrayOf(15_000, 90_000, 1_500, 3_000, StreamingConfig.NORMAL_WINDOW_MAX_BYTES)
                ThroughputBand.MEDIUM -> longArrayOf(20_000, 90_000, 2_000, 4_000, StreamingConfig.NORMAL_WINDOW_MED_BYTES)
                else -> longArrayOf(25_000, 120_000, 2_500, 5_000 + rebufferPenalty * 1_000, StreamingConfig.NORMAL_WINDOW_MIN_BYTES)
            }
        }
        val cappedPrefetch = min(prefetch, input.maxWindowBytes.coerceAtLeast(StreamingConfig.LOW_WINDOW_MIN_BYTES))
        return PolicyDecision(
            band = band,
            minBufferMs = minB,
            maxBufferMs = maxB,
            bufferForPlaybackMs = startB,
            bufferForPlaybackAfterRebufferMs = reB,
            prefetchBytes = cappedPrefetch,
            maxRetries = Backoff.maxRetries(input.mode),
            preferLowerVariant = input.hasLowerQualityVariant &&
                (low || band == ThroughputBand.LOW),
            allowBackgroundPrefetch = !low && band != ThroughputBand.LOW,
            explanation = "mode=${input.mode.label} band=$band throughput=${input.throughputBps.toLong()}bps " +
                "bitrate=${input.mediaBitrateBps}bps buffered=${input.bufferedMs}ms rebuffers=${input.rebufferCount}",
        )
    }

    private fun nextBand(current: ThroughputBand, input: PolicyInput): ThroughputBand {
        if (input.throughputBps <= 0) return ThroughputBand.UNKNOWN
        // Normalise by media bitrate when known (need head-room above 1.0x to stay ahead).
        val lowMax: Double
        val medMax: Double
        if (input.mediaBitrateBps > 0) {
            lowMax = input.mediaBitrateBps * 1.0
            medMax = input.mediaBitrateBps * 2.0
        } else {
            lowMax = StreamingConfig.BAND_LOW_MAX_BPS
            medMax = StreamingConfig.BAND_MED_MAX_BPS
        }
        val t = input.throughputBps
        val raw = when {
            t < lowMax -> ThroughputBand.LOW
            t < medMax -> ThroughputBand.MEDIUM
            else -> ThroughputBand.HIGH
        }
        if (current == ThroughputBand.UNKNOWN || raw == current) return raw
        // Hysteresis: require crossing the relevant boundary by a margin to switch bands.
        val boundary = when {
            raw.ordinal > current.ordinal -> if (current == ThroughputBand.LOW) lowMax else medMax
            else -> if (current == ThroughputBand.HIGH) medMax else lowMax
        }
        val crossed = if (raw.ordinal > current.ordinal) {
            t > boundary * StreamingConfig.HYSTERESIS_UP
        } else {
            t < boundary * StreamingConfig.HYSTERESIS_DOWN
        }
        return if (crossed) raw else current
    }

    @Synchronized
    fun currentBand(): ThroughputBand = band

    @Synchronized
    fun reset() {
        band = ThroughputBand.UNKNOWN
    }
}
