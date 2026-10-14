package com.airbooks.app.tg

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.airbooks.app.Prefs
import com.airbooks.app.stream.Backoff
import com.airbooks.app.stream.PlaybackPolicy
import com.airbooks.app.stream.PolicyInput
import com.airbooks.app.stream.RangeTracker
import com.airbooks.app.stream.StreamingMode
import com.airbooks.app.stream.StreamingRuntime
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * ExoPlayer DataSource backed by Telegram/TDLib — true progressive streaming.
 *
 * TDLib has no HTTP-Range API. The bridge used here is the one TDLib actually supports:
 * `DownloadFile(fileId, priority, offset, limit)` fetches one byte window, and the resulting
 * partial file reports a single contiguous prefix (`local.downloadOffset` +
 * `local.downloadedPrefixSize`). This class therefore:
 *
 *  - asks TDLib for a bounded window around the read position (size decided by [PlaybackPolicy]
 *    from the active [StreamingMode], estimated throughput and the user's prefetch cap);
 *  - serves reads only from bytes inside that prefix — the player starts before the whole file
 *    exists, and the complete file is never held in RAM (single [RandomAccessFile], read chunks
 *    are ExoPlayer's own buffers);
 *  - moves the window on seek (forward or backward) instead of restarting the file;
 *  - never issues a duplicate request for a range already in flight ([RangeTracker]);
 *  - re-kicks a stalled window with bounded exponential backoff ([Backoff]) and gives up after
 *    a mode-dependent stall timeout so ExoPlayer can surface a retryable error;
 *  - is cancellation-safe: [close] flips a volatile flag checked by the wait loop, and the
 *    underlying TDLib download is only cancelled when the last concurrent reader of the same
 *    file closes (reference counting in [Tg.beginStream]/[Tg.endStream]);
 *  - never starts a competing full-file download: every request carries an explicit limit.
 *
 * Limitation: because TDLib exposes only one contiguous prefix, a seek discards the previous
 * window's read-ahead (already-downloaded bytes elsewhere are not randomly addressable through
 * this API). Bytes are still correct for every requested position.
 */
@UnstableApi
class TdDataSource(
    // Live session mode: the in-player selector writes StreamingRuntime.activeMode, so mode
    // switches change real network behaviour immediately, regardless of the
    // "remember last-used mode" preference (that only picks the *starting* mode).
    private val modeProvider: () -> StreamingMode = { StreamingRuntime.activeMode },
    private val policy: PlaybackPolicy = PlaybackPolicy(),
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var fileId = 0
    private var size = 0L
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    @Volatile private var closed = false

    private val ranges = RangeTracker()
    private val readLock = Any()
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("Bad tg:// uri")
        closed = false
        transferInitializing(dataSpec)

        var f = Tg.cachedFile(fileId)
        size = maxOf(f.size, f.expectedSize)
        if (size <= 0L) {
            // Unknown length: refresh metadata once (also recovers an expired file reference).
            f = Tg.refreshFileBlocking(fileId)
            size = maxOf(f.size, f.expectedSize)
        }
        if (size <= 0L) throw IOException("Telegram did not report a file size; cannot stream this file")

        pos = dataSpec.position
        if (pos >= size) throw IOException("Position out of range")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, size - pos)
        } else {
            size - pos
        }

        ranges.invalidate()
        Tg.beginStream(fileId)
        opened = true
        try {
            ensureWindow()
        } catch (e: Exception) {
            Tg.endStream(fileId)
            opened = false
            throw e
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (closed) throw InterruptedIOException("Streaming closed")

        val want = minOf(length.toLong(), remaining)
        val waitStart = SystemClock.elapsedRealtime()
        val avail = waitAvailable(pos)
        val n = synchronized(readLock) { readFrom(pos, buffer, offset, minOf(want, avail).toInt()) }
        if (n <= 0) throw IOException("Read from Telegram cache failed")
        val waited = SystemClock.elapsedRealtime() - waitStart
        if (waited >= 20) {
            // What we actually sustained while fetching this chunk feeds the shared estimator.
            StreamingRuntime.estimator.addSample(n.toLong(), waited)
        }
        pos += n
        remaining -= n
        bytesTransferred(n)
        ensureWindow()
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        synchronized(readLock) {
            try {
                raf?.close()
            } catch (_: IOException) {
            }
            raf = null
            rafPath = null
        }
        uri = null
        if (opened) {
            opened = false
            // Stops the TDLib download only when no other reader still needs this file.
            Tg.endStream(fileId)
            transferEnded()
        }
    }

    /** Request the policy-sized window around the read position, de-duplicated by [ranges]. */
    private fun ensureWindow() {
        if (closed) return
        val mode = modeProvider()
        val decision = policy.decide(
            PolicyInput(
                throughputBps = StreamingRuntime.estimator.ewmaBps,
                avgLatencyMs = 0L,
                mediaBitrateBps = 0, // Telegram exposes no bitrate; size/duration only via MediaItem layer
                bufferedMs = 0L,
                rebufferCount = StreamingRuntime.rebufferCount,
                positionMs = 0L,
                mode = mode,
                hasLowerQualityVariant = false, // one fixed-quality Telegram file: never faked
                maxWindowBytes = Prefs.prefetchMb * MB,
            )
        )
        val range = ranges.plan(pos, decision.prefetchBytes, size) ?: return
        Tg.download(fileId, priority = 32, offset = range.first, limit = range.last - range.first + 1)
    }

    /**
     * Blocks (on ExoPlayer's loader thread, never the main thread) until at least one byte at
     * [at] is readable; returns how many contiguous bytes are available from [at].
     * Re-kicks a stalled window with bounded backoff and fails after the mode's stall timeout.
     */
    private fun waitAvailable(at: Long): Long {
        val mode = modeProvider()
        val stallTimeout = Backoff.stallTimeoutMs(mode)
        var lastSize = -1L
        var lastProgress = SystemClock.elapsedRealtime()
        var kicks = 0
        var nextKick = lastProgress + Backoff.delayMs(0, mode)
        while (true) {
            if (closed) throw InterruptedIOException("Streaming closed")
            val f = Tg.cachedFile(fileId)
            val l = f.local
            if (l.path.isNotEmpty()) {
                if (l.isDownloadingCompleted) return size - at
                val start = l.downloadOffset
                val end = start + l.downloadedPrefixSize
                if (at in start until end) return end - at
                if (at < start) {
                    // We are asked for bytes before the current window (seek): move the window.
                    ranges.invalidate()
                    ensureWindow()
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (l.downloadedSize != lastSize) {
                lastSize = l.downloadedSize
                lastProgress = now
                kicks = 0
            }
            if (now - lastProgress > stallTimeout) {
                StreamingRuntime.lastError = "No data from Telegram within ${stallTimeout / 1000}s"
                throw IOException("No data from Telegram – check your connection")
            }
            if (!l.isDownloadingActive && now >= nextKick && kicks <= Backoff.maxRetries(mode)) {
                // Transient stall: re-request, but with growing gaps — never a request storm.
                ranges.invalidate()
                ensureWindow()
                kicks++
                nextKick = now + Backoff.delayMs(kicks, mode)
            }
            try {
                Thread.sleep(40)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        }
    }

    private fun readFrom(at: Long, buf: ByteArray, off: Int, len: Int): Int {
        val path = Tg.cachedFile(fileId).local.path
        if (path.isEmpty()) throw IOException("Telegram file is not available locally yet")
        if (raf == null || rafPath != path) {
            raf?.close()
            raf = RandomAccessFile(path, "r")
            rafPath = path
        }
        val r = raf!!
        r.seek(at)
        return r.read(buf, off, len)
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}

/** Factory so the player/service shares one policy and the live mode from [Prefs]. */
@UnstableApi
class TdDataSourceFactory(
    private val modeProvider: () -> StreamingMode = { StreamingRuntime.activeMode },
) : DataSource.Factory {
    private val policy = PlaybackPolicy()
    override fun createDataSource(): DataSource = TdDataSource(modeProvider, policy)
}
