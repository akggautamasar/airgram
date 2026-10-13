package com.airbooks.app.tg

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.airbooks.app.Prefs
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * ExoPlayer DataSource that reads straight from Telegram through TDLib.
 *
 * Instead of downloading the whole file, it asks TDLib for a window of bytes around the playhead
 * (size = Prefs.prefetchMb), serves reads from TDLib's partially-downloaded file, and slides the
 * window forward as playback advances. Seeking just moves the window. Bytes that were already
 * fetched stay in TDLib's cache, so scrubbing back is free and slow connections only pay once.
 */
@UnstableApi
class TdDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var fileId = 0
    private var size = 0L
    private var pos = 0L
    private var remaining = 0L
    private var reqStart = -1L
    private var reqEnd = -1L
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull() ?: throw IOException("Bad tg:// uri")
        transferInitializing(dataSpec)

        val f = Tg.cachedFile(fileId)
        size = maxOf(f.size, f.expectedSize)
        if (size <= 0L) throw IOException("Telegram did not report a file size")

        pos = dataSpec.position
        if (pos >= size) throw IOException("Position out of range")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, size - pos) else size - pos

        reqStart = -1L; reqEnd = -1L
        ensureWindow()
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        val want = minOf(length.toLong(), remaining)
        val avail = waitAvailable(pos)
        val n = readFrom(pos, buffer, offset, minOf(want, avail).toInt())
        if (n <= 0) throw IOException("Read from Telegram cache failed")
        pos += n
        remaining -= n
        bytesTransferred(n)
        ensureWindow()
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            raf?.close()
        } catch (_: IOException) {
        } finally {
            raf = null; rafPath = null; uri = null
        }
        if (opened) {
            opened = false
            // Stop pulling data we no longer need (saves mobile data). Already-fetched bytes are kept.
            Tg.cancelDownload(fileId)
            transferEnded()
        }
    }

    /** Keep a download window of Prefs.prefetchMb requested around the read position. */
    private fun ensureWindow() {
        val window = Prefs.prefetchMb * MB
        val low = window / 2
        val need = reqStart < 0 || pos < reqStart || pos >= reqEnd || (reqEnd - pos < low && reqEnd < size)
        if (!need) return
        reqStart = pos - (pos % MB)
        reqEnd = minOf(size, reqStart + window)
        Tg.download(fileId, 32, reqStart, reqEnd - reqStart)
    }

    /** Blocks until at least one byte at [at] is readable; returns how many contiguous bytes are. */
    private fun waitAvailable(at: Long): Long {
        var lastSize = -1L
        var lastProgress = SystemClock.elapsedRealtime()
        var lastKick = lastProgress
        while (true) {
            val l = Tg.cachedFile(fileId).local
            if (l.path.isNotEmpty()) {
                if (l.isDownloadingCompleted) return size - at
                val start = l.downloadOffset
                val end = start + l.downloadedPrefixSize
                if (at in start until end) return end - at
            }
            val now = SystemClock.elapsedRealtime()
            if (l.downloadedSize != lastSize) { lastSize = l.downloadedSize; lastProgress = now }
            if (!l.isDownloadingActive && now - lastKick > 3000) { reqStart = -1L; ensureWindow(); lastKick = now }
            if (now - lastProgress > STALL_TIMEOUT_MS) throw IOException("No data from Telegram – check your connection")
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
        const val STALL_TIMEOUT_MS = 120_000L
    }
}
