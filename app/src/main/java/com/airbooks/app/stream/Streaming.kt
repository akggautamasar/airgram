package com.airbooks.app.stream

import kotlin.math.max
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
    EXTREME_LOW_DATA("Extreme Low Data"),
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

    // Extreme Low Data: continuity over startup. Biggest bounded windows, most patience.
    const val EXTREME_WINDOW_MAX_BYTES = 48L * 1024 * 1024
    const val EXTREME_WINDOW_MED_BYTES = 24L * 1024 * 1024
    const val EXTREME_WINDOW_MIN_BYTES = 12L * 1024 * 1024

    const val MAX_RETRIES_NORMAL = 5
    const val MAX_RETRIES_LOW_DATA = 4
    const val MAX_RETRIES_EXTREME = 6
    const val BACKOFF_BASE_NORMAL_MS = 400L
    const val BACKOFF_BASE_LOW_DATA_MS = 1_000L
    const val BACKOFF_BASE_EXTREME_MS = 1_500L
    const val BACKOFF_CAP_MS = 30_000L

    const val STALL_TIMEOUT_NORMAL_MS = 60_000L
    const val STALL_TIMEOUT_LOW_DATA_MS = 90_000L
    const val STALL_TIMEOUT_EXTREME_MS = 150_000L

    // Pause-to-buffer: bounded prebuffer-ahead targets per mode (Extreme = largest).
    // Byte caps bound disk/heap; second caps adapt the target to the media bitrate;
    // the throughput check keeps the target reachable on the measured link.
    const val PAUSE_PREBUFFER_NORMAL_BYTES = 24L * 1024 * 1024
    const val PAUSE_PREBUFFER_LOW_BYTES = 6L * 1024 * 1024
    const val PAUSE_PREBUFFER_EXTREME_BYTES = 96L * 1024 * 1024
    const val PAUSE_PREBUFFER_NORMAL_SEC = 90L
    const val PAUSE_PREBUFFER_LOW_SEC = 30L
    const val PAUSE_PREBUFFER_EXTREME_SEC = 300L

    // TDLib priority for pause-time prebuffering: below the active playback window (32)
    // and interleaved with the download scheduler's 8..30 range, so queued user downloads
    // keep their share of TDLib's byte budget while the player is paused.
    const val PREBUFFER_PRIORITY = 20

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

/**
 * The genuinely downloaded contiguous prefix TDLib reports for the streamed file
 * (`local.downloadOffset + downloadedPrefixSize`, or the whole file when complete).
 * This is the ONLY loaded region AirGram can honestly claim: TDLib exposes one
 * contiguous prefix per file, never arbitrary independent segments.
 */
data class LoadedPrefix(
    val fileId: Int,
    val start: Long,
    val end: Long,
    val fileSize: Long,
    val completed: Boolean,
) {
    /** 0..1 fraction of the file downloaded in the current window. */
    val fraction: Float get() = if (fileSize > 0) (end.toFloat() / fileSize).coerceIn(0f, 1f) else 0f

    companion object { val EMPTY = LoadedPrefix(0, 0, 0, 0, false) }
}

/**
 * Per-session playback measurements (acceptance §6): startup time, buffering duration,
 * rebuffer events (counted in [StreamingRuntime.rebufferCount]), fetched bytes and
 * prebuffered seconds. Pure aggregation, JVM-testable; wall-clock values are passed in.
 */
class PlaybackMetrics {
    /** prepare → first READY of the session; -1 until measured. */
    @Volatile var startupMs: Long = -1L
        private set
    /** Cumulative time spent in BUFFERING during the session. */
    @Volatile var totalBufferingMs: Long = 0L
        private set
    /**
     * Bytes the TDLib prefix grew by during the session — a lower bound on data consumed:
     * window moves after seeks can re-fetch overlaps that this never double-counts, and it
     * never fabricates bytes that were not actually downloaded.
     */
    @Volatile var bytesFetched: Long = 0L
        private set

    private var sessionStartAt = -1L
    private var bufferingSince = -1L

    @Synchronized
    fun startSession(atMs: Long) {
        sessionStartAt = atMs
        startupMs = -1L
        totalBufferingMs = 0L
        bytesFetched = 0L
        bufferingSince = -1L
    }

    @Synchronized
    fun markBuffering(atMs: Long) {
        if (bufferingSince < 0) bufferingSince = atMs
    }

    @Synchronized
    fun markReady(atMs: Long) {
        if (sessionStartAt >= 0 && startupMs < 0) startupMs = (atMs - sessionStartAt).coerceAtLeast(0)
        if (bufferingSince >= 0) {
            totalBufferingMs += (atMs - bufferingSince).coerceAtLeast(0)
            bufferingSince = -1
        }
    }

    @Synchronized
    fun noteBytesFetched(delta: Long) {
        if (delta > 0) bytesFetched += delta
    }

    companion object {
        /** Seconds of media a byte count represents at the average bitrate; -1 if unknown. */
        fun prebufferSeconds(aheadBytes: Long, mediaBitrateBps: Long): Long =
            if (mediaBitrateBps > 0 && aheadBytes > 0) aheadBytes * 8 / mediaBitrateBps else -1L
    }
}

/** Shared, process-wide diagnostics so the player/settings can show what streaming is doing. */
object StreamingRuntime {
    val estimator = ThroughputEstimator()
    val metrics = PlaybackMetrics()
    @Volatile var playbackActive: Boolean = false
    @Volatile var activeMode: StreamingMode = StreamingMode.NORMAL
    @Volatile var activeFileId: Int = 0
    @Volatile var rebufferCount: Int = 0
    @Volatile var lastError: String? = null

    // ── Pause-to-buffer & seek-bar state (written by TdDataSource / PlaybackService) ──
    /** True while the USER has paused (never while the extreme-mode start gate holds). */
    @Volatile var userPaused: Boolean = false
    /** True while the service holds playback to grow the buffer (extreme mode gate). */
    @Volatile var internalHold: Boolean = false
    /** Average media bitrate (bits/s) from size/duration; 0 = unknown. Feeds policy targets. */
    @Volatile var mediaBitrateBps: Long = 0
    /** Live contiguous downloaded prefix for the active stream (seek-bar loaded track). */
    @Volatile var loadedPrefix: LoadedPrefix = LoadedPrefix.EMPTY
    /** Bytes downloaded ahead of the read position (diagnostics / prebuffer status). */
    @Volatile var lastPrebufferAheadBytes: Long = 0
}

/**
 * Bounded pause-to-buffer targets. While the user has paused, the active data source keeps
 * advancing TDLib's window until this many bytes ahead of the playhead are available — then
 * stops and only resumes fetching if the buffer falls below the target again.
 *
 * The target adapts: byte cap per mode (Extreme > Normal > Low Data), converted to a time cap
 * when the media bitrate is known, and further limited to what the measured throughput can
 * actually deliver, so a hopeless link never churns through a huge target.
 */
object Prebuffer {
    fun targetBytes(
        mode: StreamingMode,
        mediaBitrateBps: Long, // 0 = unknown
        throughputBps: Double, // EWMA estimate; <= 0 = unknown
        fileSizeBytes: Long, // 0 = unknown
        maxWindowBytes: Long, // user "read-ahead cap (Normal)" slider; 0 = unset
    ): Long {
        val capBytes = when (mode) {
            StreamingMode.LOW_DATA -> StreamingConfig.PAUSE_PREBUFFER_LOW_BYTES
            StreamingMode.EXTREME_LOW_DATA -> StreamingConfig.PAUSE_PREBUFFER_EXTREME_BYTES
            StreamingMode.NORMAL ->
                if (maxWindowBytes > 0) min(StreamingConfig.PAUSE_PREBUFFER_NORMAL_BYTES, maxWindowBytes)
                else StreamingConfig.PAUSE_PREBUFFER_NORMAL_BYTES
        }
        val capSec = when (mode) {
            StreamingMode.LOW_DATA -> StreamingConfig.PAUSE_PREBUFFER_LOW_SEC
            StreamingMode.EXTREME_LOW_DATA -> StreamingConfig.PAUSE_PREBUFFER_EXTREME_SEC
            StreamingMode.NORMAL -> StreamingConfig.PAUSE_PREBUFFER_NORMAL_SEC
        }
        val floorBytes = when (mode) {
            StreamingMode.LOW_DATA -> StreamingConfig.RANGE_ALIGN_BYTES
            StreamingMode.EXTREME_LOW_DATA -> StreamingConfig.EXTREME_WINDOW_MIN_BYTES
            StreamingMode.NORMAL -> StreamingConfig.NORMAL_WINDOW_MIN_BYTES
        }
        var t = capBytes
        var measured = false
        if (mediaBitrateBps > 0) {
            t = min(t, (mediaBitrateBps / 8.0 * capSec).toLong())
            measured = true
        }
        // Reachability: with 4x head-room over capSec, a target the link cannot deliver in
        // a reasonable time is cut down instead of being chased forever.
        if (throughputBps > 0) {
            t = min(t, (throughputBps / 8.0 * capSec * 4).toLong())
            measured = true
        }
        // The byte floor only applies when nothing is measured yet; a known bitrate gives
        // audio-appropriate (small) targets instead of video-sized floors.
        if (!measured) t = max(t, floorBytes)
        if (fileSizeBytes > 0) t = min(t, fileSizeBytes)
        return t.coerceAtLeast(0)
    }
}

/**
 * Extreme Low Data start/resume gate: pure predicates the playback service evaluates ~1 Hz.
 * Playback is HELD (internally paused — never a user pause) while the buffer ahead of the
 * playhead is below the mode's start target, and released once the resume target is reached,
 * the stream ends, or the user leaves extreme mode. Intentional user pauses are handled
 * separately and are never overridden.
 */
object StartGate {
    fun shouldHoldStart(mode: StreamingMode, aheadBufferedMs: Long, startTargetMs: Long, atEnd: Boolean): Boolean =
        mode == StreamingMode.EXTREME_LOW_DATA && !atEnd && aheadBufferedMs < startTargetMs

    fun shouldReleaseHold(mode: StreamingMode, aheadBufferedMs: Long, resumeTargetMs: Long, atEnd: Boolean): Boolean =
        mode != StreamingMode.EXTREME_LOW_DATA || atEnd || aheadBufferedMs >= resumeTargetMs
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

    /**
     * Adopt an externally planned range (the pause-time prebuffer pump extends the window
     * beyond what reads request). Returns true only when the range differs from the window
     * already in flight, so the pump never issues a duplicate request.
     */
    @Synchronized
    fun adoptOrPlan(start: Long, end: Long): Boolean {
        if (start == requestedStart && end == requestedEnd) return false
        requestedStart = start
        requestedEnd = end
        return true
    }

    @Synchronized
    fun covers(position: Long): Boolean =
        requestedStart >= 0 && position in requestedStart until requestedEnd
}

/** Bounded exponential backoff: delay grows per attempt but never exceeds the cap. */
object Backoff {
    fun delayMs(attempt: Int, mode: StreamingMode): Long {
        val base = when (mode) {
            StreamingMode.LOW_DATA -> StreamingConfig.BACKOFF_BASE_LOW_DATA_MS
            StreamingMode.EXTREME_LOW_DATA -> StreamingConfig.BACKOFF_BASE_EXTREME_MS
            StreamingMode.NORMAL -> StreamingConfig.BACKOFF_BASE_NORMAL_MS
        }
        val shift = attempt.coerceIn(0, 10)
        return min(StreamingConfig.BACKOFF_CAP_MS, base shl shift)
    }

    fun maxRetries(mode: StreamingMode): Int = when (mode) {
        StreamingMode.LOW_DATA -> StreamingConfig.MAX_RETRIES_LOW_DATA
        StreamingMode.EXTREME_LOW_DATA -> StreamingConfig.MAX_RETRIES_EXTREME
        StreamingMode.NORMAL -> StreamingConfig.MAX_RETRIES_NORMAL
    }

    fun stallTimeoutMs(mode: StreamingMode): Long = when (mode) {
        StreamingMode.LOW_DATA -> StreamingConfig.STALL_TIMEOUT_LOW_DATA_MS
        StreamingMode.EXTREME_LOW_DATA -> StreamingConfig.STALL_TIMEOUT_EXTREME_MS
        StreamingMode.NORMAL -> StreamingConfig.STALL_TIMEOUT_NORMAL_MS
    }
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
    val fileSizeBytes: Long = 0, // 0 = unknown; bounds the pause-prebuffer target
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
    /** Bounded bytes to keep fetching ahead of the playhead while the USER has paused. */
    val pausePrebufferBytes: Long,
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
        // Repeated rebuffering buys extra protection, but bounded (never grows without limit).
        val rebufferPenalty = min(input.rebufferCount, 4).toLong()

        val (minB, maxB, startB, reB, prefetch) = when (input.mode) {
            StreamingMode.LOW_DATA -> when (band) {
                ThroughputBand.HIGH -> longArrayOf(8_000, 30_000, 750, 1_500, StreamingConfig.LOW_WINDOW_MAX_BYTES)
                ThroughputBand.MEDIUM -> longArrayOf(10_000, 30_000, 1_000, 2_500, StreamingConfig.LOW_WINDOW_MED_BYTES)
                else -> longArrayOf(12_000, 45_000, 1_250, 4_000 + rebufferPenalty * 500, StreamingConfig.LOW_WINDOW_MIN_BYTES)
            }
            // Extreme Low Data: continuity over startup. Playback starts/resumes only with a
            // deep buffer (6-10 s), buffer ceilings are the largest, windows are the widest,
            // and background prefetch (pause-to-buffer) is ALWAYS allowed — that is the mode.
            StreamingMode.EXTREME_LOW_DATA -> when (band) {
                ThroughputBand.HIGH -> longArrayOf(30_000, 180_000, 6_000, 10_000, StreamingConfig.EXTREME_WINDOW_MAX_BYTES)
                ThroughputBand.MEDIUM -> longArrayOf(40_000, 180_000, 8_000, 12_000, StreamingConfig.EXTREME_WINDOW_MED_BYTES)
                else -> longArrayOf(50_000, 240_000, 10_000, 15_000 + rebufferPenalty * 1_500, StreamingConfig.EXTREME_WINDOW_MIN_BYTES)
            }
            StreamingMode.NORMAL -> when (band) {
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
                (input.mode != StreamingMode.NORMAL || band == ThroughputBand.LOW),
            allowBackgroundPrefetch = when (input.mode) {
                StreamingMode.EXTREME_LOW_DATA -> true
                StreamingMode.LOW_DATA -> false
                StreamingMode.NORMAL -> band != ThroughputBand.LOW
            },
            pausePrebufferBytes = Prebuffer.targetBytes(
                mode = input.mode,
                mediaBitrateBps = input.mediaBitrateBps,
                throughputBps = input.throughputBps,
                fileSizeBytes = input.fileSizeBytes,
                maxWindowBytes = input.maxWindowBytes,
            ),
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
