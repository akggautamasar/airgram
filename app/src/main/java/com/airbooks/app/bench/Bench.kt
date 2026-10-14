package com.airbooks.app.bench

import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import com.airbooks.app.BuildConfig
import java.io.File

/**
 * On-device benchmark recorder (Android side). Every completed/failed managed download becomes a
 * [Trial] row in `files/bench/trials.csv`; the Downloads screen picks the scenario tag
 * (single / sweep-k / contention) and can export/share the CSV.
 *
 * This is the AirGram half of the comparison protocol in DOWNLOADS.md §6: identical files,
 * alternating order versus official Telegram, ≥5 trials per scenario, medians reported, and
 * trials whose sustained speed varies >25 % across alternations flagged as server-load affected.
 */
object Bench {
    @Volatile var scenario: String = "single"

    private var ctx: Context? = null
    private val trials = mutableListOf<Trial>()
    private data class Live(
        var startMs: Long = 0,
        var firstByteMs: Long = -1,
        var peakBps: Double = 0.0,
        var redundant: Long = 0,
        var cpuStartMs: Long = 0,
        var batteryStart: Int = 0,
        var retries: Int = 0,
    )
    private val live = HashMap<Int, Live>()

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    fun jobStarted(fileId: Int) {
        val now = System.currentTimeMillis()
        live[fileId] = Live(
            startMs = now, cpuStartMs = processCpuMs(), batteryStart = batteryPct(),
        )
    }

    fun firstByte(fileId: Int) {
        val l = live[fileId] ?: return
        if (l.firstByteMs < 0) l.firstByteMs = System.currentTimeMillis() - l.startMs
    }

    /** Instantaneous sample (bytes over dtMs) — feeds the peak window. */
    fun progressSample(fileId: Int, bytes: Long, dtMs: Long) {
        if (dtMs <= 0 || bytes <= 0) return
        val l = live[fileId] ?: return
        val bps = bytes * 8_000.0 / dtMs
        if (bps > l.peakBps) l.peakBps = bps
    }

    /** TDLib prefix went backwards = bytes fetched then discarded (redundant transfer). */
    fun regression(fileId: Int, bytes: Long) {
        live[fileId]?.let { it.redundant += bytes }
    }

    fun retry(fileId: Int) {
        live[fileId]?.let { it.retries++ }
    }

    /** Cancelled jobs leave no trial row. */
    fun drop(fileId: Int) {
        live.remove(fileId)
    }

    fun jobFinished(fileId: Int, name: String, size: Long, sustainedBps: Double, ok: Boolean,
                    metered: Boolean, concurrency: Int, profile: String) {
        val l = live.remove(fileId) ?: return
        val now = System.currentTimeMillis()
        trials.add(
            Trial(
                tsEpochMs = now,
                scenario = scenario,
                appVersion = BuildConfig.VERSION_NAME,
                fileId = fileId,
                name = name,
                sizeBytes = size,
                ttffMs = l.firstByteMs.coerceAtLeast(0),
                completionMs = now - l.startMs,
                sustainedMBps = sustainedBps / 8_000_000.0,
                peakMBps = l.peakBps / 8_000_000.0,
                retries = l.retries,
                redundantBytes = l.redundant,
                cpuMs = (processCpuMs() - l.cpuStartMs).coerceAtLeast(0),
                batteryDeltaPct = (batteryPct() - l.batteryStart),
                network = if (metered) "metered" else "wifi",
                concurrency = concurrency,
                profile = profile,
                ok = ok,
            ),
        )
        persist()
    }

    fun all(): List<Trial> = trials.toList()

    fun exportCsv(): File? {
        val c = ctx ?: return null
        val dir = File(c.filesDir, "bench").apply { mkdirs() }
        val f = File(dir, "trials.csv")
        runCatching {
            f.writeText(buildString {
                append(Trial.HEADER).append('\n')
                trials.forEach { append(it.toCsvRow()).append('\n') }
            })
        }
        return f.takeIf { it.exists() }
    }

    private fun persist() { exportCsv() }

    /** Process CPU ms (user+system) from /proc/self/stat — cheap, no polling loop. */
    private fun processCpuMs(): Long = runCatching {
        val parts = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
        // fields after comm: state(0) ... utime=11, stime=12 (0-based after the split above)
        val hz = 100L
        ((parts[11].toLong() + parts[12].toLong()) * 1000) / hz
    }.getOrDefault(0L)

    private fun batteryPct(): Int = runCatching {
        val c = ctx ?: return@runCatching 0
        val ifilter = IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        val sticky = c.registerReceiver(null, ifilter)
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) 0 else (level * 100 / scale)
    }.getOrDefault(0)
}
