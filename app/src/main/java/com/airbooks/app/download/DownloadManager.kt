package com.airbooks.app.download

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import com.airbooks.app.Prefs
import com.airbooks.app.bench.Bench
import com.airbooks.app.stream.StreamingRuntime
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Tg
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

/**
 * Android/TDLib glue around [DownloadScheduler].
 *
 * Turns each [DownloadScheduler.SchedulePlan] into `downloadFile` calls (priority 0 = park),
 * feeds progress from TDLib's `updateFile` events (no polling), validates completed files before
 * marking them COMPLETED, persists the queue, keeps a `dataSync` foreground service alive while
 * transfers run, and pushes TDLib the documented [TdApi.SetNetworkType] updates on every
 * connectivity change.
 */
object DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ctx: Context? = null
    val scheduler = DownloadScheduler()

    private val _ui = MutableStateFlow<List<DownloadScheduler.DownloadJob>>(emptyList())
    val ui: StateFlow<List<DownloadScheduler.DownloadJob>> = _ui.asStateFlow()

    private val lastPriority = HashMap<Int, Int>()
    private val startedNotified = HashSet<Int>()
    private val prevDownloaded = HashMap<Int, Long>()
    private val lastSampleAt = HashMap<Int, Long>()
    private var ticks = 0
    private var serviceOn = false
    private var persistDirty = false

    val aggregateBps: Double get() = scheduler.aggregateSpeedBps()

    private val queueFile: File? get() = ctx?.let { File(it.filesDir, "download_queue.tsv") }

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        Bench.init(context)
        scheduler.setProfile(profileOf(Prefs.downloadProfile))
        scheduler.wifiOnly = Prefs.wifiOnlyDownloads
        runCatching { queueFile?.takeIf { it.exists() }?.let { scheduler.fromPersist(it.readText()) } }
        Tg.onDownloadError = { id, msg ->
            scheduler.onError(id, msg)
            Bench.retry(id)
            persist()
        }
        NetworkMonitor.start(context) { metered, type ->
            scheduler.metered = metered
            Tg.setNetworkType(type)
            reschedule()
        }
        scope.launch { controlLoop() }
        scope.launch { uiLoop() }
    }

    fun profileOf(label: String): DownloadScheduler.Profile =
        DownloadScheduler.Profile.values().firstOrNull { it.label == label }
            ?: DownloadScheduler.Profile.BALANCED

    fun setProfile(p: DownloadScheduler.Profile) {
        scheduler.setProfile(p)
        Prefs.updateDownloadProfile(p.label)
        reschedule()
    }

    fun setWifiOnly(v: Boolean) {
        scheduler.wifiOnly = v
        Prefs.updateWifiOnlyDownloads(v)
        reschedule()
    }

    // ───────────────────────────── public actions ─────────────────────────────

    fun enqueue(item: Media) {
        scheduler.enqueue(item.fileId, item.name, item.size)
        Tg.beginDownload(item.fileId)
        persist()
        reschedule()
    }

    fun pause(id: Int) { scheduler.pause(id); parkNow(id); persist() }
    fun resume(id: Int) { scheduler.resume(id); persist(); reschedule() }
    fun retry(id: Int) { scheduler.retry(id); persist(); reschedule() }
    fun boost(id: Int) { scheduler.boost(id); persist(); reschedule() }

    fun cancel(id: Int) {
        scheduler.cancel(id)
        parkNow(id)
        Bench.drop(id)
        startedNotified.remove(id)
        prevDownloaded.remove(id)
        Tg.endDownload(id)
        persist()
    }

    fun removeFinished(id: Int) { scheduler.removeFinished(id); persist() }

    /** Real destination: app-owned external Downloads dir (permission-free, shareable, visible
     *  to the user via Files/this app); TDLib cache stays separate for streaming. */
    fun exportDir(): File {
        val base = ctx?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx!!.filesDir
        return File(base, "AirGram").apply { mkdirs() }
    }

    /** Copy a completed file out of TDLib's cache with a single zero-copy channel transfer. */
    suspend fun saveToDevice(id: Int): File? = withContext(Dispatchers.IO) {
        val j = scheduler.job(id) ?: return@withContext null
        val src = File(j.localPath ?: return@withContext null)
        if (!src.exists()) return@withContext null
        val dst = File(exportDir(), j.name)
        if (dst.exists() && dst.length() == src.length()) return@withContext dst
        dst.outputStream().channel.use { out ->
            src.inputStream().channel.use { inp -> inp.transferTo(0, src.length(), out) }
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), null, null)
        dst
    }

    fun deleteExport(id: Int): Boolean {
        val j = scheduler.job(id) ?: return false
        val f = File(exportDir(), j.name)
        return f.takeIf { it.exists() }?.delete() ?: false
    }

    fun storageUsageBytes(): Long =
        scheduler.queue.filter { it.state == DownloadScheduler.JobState.COMPLETED }
            .sumOf { File(it.localPath ?: "").length() } + Tg.cacheBytes()

    /** Same size + name already exported = duplicate candidate for the UI. */
    fun duplicateNames(): Set<String> =
        scheduler.queue.filter { it.state == DownloadScheduler.JobState.COMPLETED }
            .groupingBy { "${it.name}|${it.size}" }.eachCount().filterValues { it > 1 }.keys

    // ───────────────────────────── internals ─────────────────────────────

    private fun reschedule() { /* next control-loop tick (≤500 ms) applies it */ }

    private fun parkNow(id: Int) {
        Tg.download(id, 0, 0, 0)
        lastPriority.remove(id)
    }

    private suspend fun controlLoop() {
        while (true) {
            delay(500)
            val c = ctx ?: continue
            scheduler.playbackActive = StreamingRuntime.playbackActive
            // Sustained rebuffering during playback temporarily shrinks background concurrency.
            if (StreamingRuntime.playbackActive && StreamingRuntime.rebufferCount > 2) {
                scheduler.playbackPressure()
            }
            val plan = scheduler.schedule()
            for (id in plan.run) {
                val p = plan.priorityFor(id)
                if (lastPriority[id] != p) {
                    // offset=0/limit=0: sequential whole-file download; TDLib resumes from its
                    // persisted prefix, so parking and re-issuing never re-transfers a byte.
                    Tg.download(id, p, 0, 0)
                    lastPriority[id] = p
                }
                if (startedNotified.add(id)) Bench.jobStarted(id)
            }
            for (id in plan.park) parkNow(id)

            for (j in scheduler.queue.filter { it.state == DownloadScheduler.JobState.ACTIVE }) {
                val f = Tg.peekFile(j.fileId) ?: continue
                val l = f.local
                if (l.isDownloadingCompleted && l.path.isNotEmpty()) {
                    validateAndComplete(j.fileId, l.path, maxOf(f.size, f.expectedSize))
                } else {
                    // updateFile-driven progress (no TDLib polling): TTFF, peak window and
                    // redundant-byte detection all come from these deltas.
                    val now = System.currentTimeMillis()
                    val cur = l.downloadedSize
                    val prev = prevDownloaded[j.fileId]
                    if (prev != null) {
                        val delta = cur - prev
                        if (delta < 0) {
                            Bench.regression(j.fileId, -delta)
                        } else if (delta > 0) {
                            Bench.firstByte(j.fileId)
                            Bench.progressSample(j.fileId, delta, now - (lastSampleAt[j.fileId] ?: now))
                            lastSampleAt[j.fileId] = now
                        }
                    } else {
                        lastSampleAt[j.fileId] = now
                    }
                    prevDownloaded[j.fileId] = cur
                    scheduler.onProgress(j.fileId, cur)
                }
            }
            ticks++
            if (ticks % 4 == 0) scheduler.adapt(scheduler.aggregateSpeedBps()) // 2 s window
            serviceLifecycle(c)
            if (persistDirty && ticks % 8 == 0) persist()
        }
    }

    /** A file is COMPLETED only after the final path is verified on disk. */
    private fun validateAndComplete(id: Int, path: String, expectedSize: Long) {
        scope.launch(Dispatchers.IO) {
            val j = scheduler.job(id)
            if (!FileValidation.isValid(path, expectedSize)) {
                scheduler.onError(id, "Completed file failed validation on disk")
                if (j != null) {
                    Bench.jobFinished(id, j.name, j.size, j.speedBps, false,
                        scheduler.metered, scheduler.concurrency, scheduler.profile.label)
                }
                return@launch
            }
            scheduler.onComplete(id, path)
            if (j != null) {
                Bench.jobFinished(id, j.name, j.size, j.speedBps, true,
                    scheduler.metered, scheduler.concurrency, scheduler.profile.label)
            }
            Tg.endDownload(id)
            lastPriority.remove(id)
            persist()
        }
    }

    private suspend fun uiLoop() {
        while (true) {
            delay(250) // 4 Hz UI cadence: accurate but cheap
            _ui.value = scheduler.queue.map { it.copy() }
        }
    }

    private fun serviceLifecycle(c: Context) {
        val need = scheduler.activeCount > 0
        if (need && !serviceOn) {
            serviceOn = true
            runCatching {
                c.startForegroundService(Intent(c, DownloadService::class.java))
            }
        } else if (!need && serviceOn) {
            serviceOn = false
            runCatching { c.stopService(Intent(c, DownloadService::class.java)) }
        }
    }

    private fun persist() {
        persistDirty = true
        scope.launch(Dispatchers.IO) {
            runCatching { queueFile?.writeText(scheduler.toPersist()) }
            persistDirty = false
        }
    }

    /** Byte digest for history/duplicate detection — completed files only, off the download path. */
    suspend fun sha256(path: String): String? = withContext(Dispatchers.IO) {
        FileValidation.sha256(path)
    }
}

/** Feeds TDLib the documented, mandatory network-type updates and the scheduler's metered flag. */
object NetworkMonitor {
    private var started = false

    fun start(ctx: Context, onChange: (metered: Boolean, type: TdApi.NetworkType?) -> Unit) {
        if (started) return
        started = true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            private fun emit(network: Network?) {
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                val metered = cm.isActiveNetworkMetered
                val type: TdApi.NetworkType? = when {
                    caps == null -> TdApi.NetworkTypeNone()
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TdApi.NetworkTypeMobile()
                    else -> TdApi.NetworkTypeOther()
                }
                onChange(metered, type)
            }
            override fun onAvailable(network: Network) = emit(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = emit(network)
            override fun onLost(network: Network) = emit(null)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(cb)
        }
        emit0(cm, onChange)
    }

    private fun emit0(cm: ConnectivityManager, onChange: (Boolean, TdApi.NetworkType?) -> Unit) {
        val metered = cm.isActiveNetworkMetered
        onChange(metered, if (metered) TdApi.NetworkTypeMobile() else TdApi.NetworkTypeWiFi())
    }
}
