package com.airbooks.app.bench

/**
 * One measured download trial. Pure JVM so CSV layout and derived metrics are unit-testable;
 * [Bench] (Android side) fills instances from scheduler/manager events and exports them.
 *
 * Peak and sustained speed are kept separate on purpose: sustained is an EWMA over the whole
 * transfer (≥10 s transfers only), peak is the best 1-second window.
 */
data class Trial(
    val tsEpochMs: Long,
    val scenario: String,          // single | sweep-k | contention | resume | manual
    val appVersion: String,
    val fileId: Int,
    val name: String,
    val sizeBytes: Long,
    val ttffMs: Long,              // time to first downloaded byte
    val completionMs: Long,        // wall clock enqueue -> validated completion
    val sustainedMBps: Double,
    val peakMBps: Double,
    val retries: Int,
    val redundantBytes: Long,      // prefix regressions = bytes fetched then discarded
    val cpuMs: Long,               // process CPU (user+system) consumed during the trial
    val batteryDeltaPct: Int,
    val network: String,           // wifi | metered
    val concurrency: Int,
    val profile: String,
    val ok: Boolean,
) {
    fun toCsvRow(): String = listOf(
        tsEpochMs, scenario, appVersion, fileId, csvEscape(name), sizeBytes, ttffMs, completionMs,
        "%.3f".format(sustainedMBps), "%.3f".format(peakMBps), retries, redundantBytes, cpuMs,
        batteryDeltaPct, network, concurrency, profile, if (ok) 1 else 0,
    ).joinToString(",")

    companion object {
        const val HEADER = "ts,scenario,appVersion,fileId,name,sizeBytes,ttffMs,completionMs," +
            "sustainedMBps,peakMBps,retries,redundantBytes,cpuMs,batteryDeltaPct,network," +
            "concurrency,profile,ok"

        fun csvEscape(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        /** Median of a sample, for reporting (empty-safe). */
        fun median(xs: List<Double>): Double =
            if (xs.isEmpty()) 0.0 else xs.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    }
}
