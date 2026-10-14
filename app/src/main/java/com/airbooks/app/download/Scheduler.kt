package com.airbooks.app.download

import com.airbooks.app.stream.ThroughputEstimator

/**
 * Download scheduling core — pure Kotlin, no Android/TDLib dependencies, JVM-unit-testable.
 *
 * Design is driven by the TDLib 1.8.68 audit (see DOWNLOADS.md):
 *
 *  - TDLib keeps **one** download window per file (`FileNode::download_offset_`), so parallel
 *    ranges for a single file are impossible; a second `downloadFile(offset)` *moves* the window.
 *    Single-file speed therefore depends on TDLib's [ResourceManager] giving our task the largest
 *    possible share of its global byte budget — i.e. on **not competing** with thumbnails,
 *    prefetch, streaming windows or other downloads, and on using priority correctly.
 *  - TDLib already fair-shares its byte budget across active tasks by priority (equal priority
 *    = last-requested first), so "concurrency" at app level means *how many files we keep at
 *    priority > 0*, and adaptive concurrency must be measured, not assumed.
 *  - Pause = priority 0 / cancel; resume = re-issue, and TDLib continues from the persisted
 *    prefix (useFileDatabase), so completed bytes are never re-transferred.
 *
 * The Android/TDLib glue ([DownloadManager]) turns [SchedulePlan]s into `downloadFile` calls and
 * feeds [onProgress]/[onError]/[onComplete] from `updateFile` events.
 */
class DownloadScheduler(
    var profile: Profile = Profile.BALANCED,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class Profile(val label: String, val minConcurrency: Int, val baseConcurrency: Int, val maxConcurrency: Int) {
        HIGH_SPEED("High Speed", 2, 4, 6),
        BALANCED("Balanced", 1, 2, 4),
        LOW_DATA("Low Data", 1, 1, 2),
    }

    enum class JobState { QUEUED, ACTIVE, PAUSED, COMPLETED, FAILED, CANCELLED }

    data class DownloadJob(
        val fileId: Int,
        val name: String,
        val size: Long,
        var state: JobState = JobState.QUEUED,
        var downloaded: Long = 0,
        var boosted: Boolean = false,
        var enqueuedAt: Long = 0,
        var startedAt: Long = 0,
        var finishedAt: Long = 0,
        var attempts: Int = 0,
        var nextRetryAt: Long = 0,
        var error: String? = null,
        var localPath: String? = null,
        /** Bytes/s EWMA for this job, from updateFile deltas. */
        var speedBps: Double = 0.0,
        var lastProgressAt: Long = 0,
    ) {
        val remaining: Long get() = (size - downloaded).coerceAtLeast(0)
        val fraction: Float get() = if (size > 0) (downloaded.toDouble() / size).coerceIn(0.0, 1.0).toFloat() else 0f
        fun etaMs(): Long = if (speedBps > 1_000) (remaining / speedBps * 1000).toLong() else -1L
    }

    /** What the TDLib glue must do right now: which files to run (ordered by TDLib priority
     *  weight, highest first) and which to park at priority 0. */
    data class SchedulePlan(val run: List<Int>, val park: List<Int>) {
        /** TDLib priority (1..32) for a job in [run]; index 0 gets the top weight. */
        fun priorityFor(fileId: Int): Int {
            val i = run.indexOf(fileId)
            if (i < 0) return 0
            // Spread the top of TDLib's 1..32 range over active jobs; streaming keeps 32 elsewhere.
            return (30 - i * 2).coerceIn(8, 30)
        }
    }

    private val jobs = LinkedHashMap<Int, DownloadJob>()
    private val aggregate = ThroughputEstimator()

    /** Live concurrency; adapted inside [profile] bounds, never below min or above max. */
    var concurrency: Int = profile.baseConcurrency
        private set

    var playbackActive: Boolean = false
    var metered: Boolean = false
    var wifiOnly: Boolean = false

    private var lastAdaptAt = -ADAPT_COOLDOWN_MS
    private var aggregateBeforeLastChange = 0.0
    private var windowBytes = 0L
    private var windowStart = 0L

    val activeCount: Int get() = jobs.values.count { it.state == JobState.ACTIVE }
    val queue: List<DownloadJob> get() = jobs.values.toList()
    fun job(fileId: Int): DownloadJob? = jobs[fileId]
    fun aggregateSpeedBps(): Double = aggregate.ewmaBps

    // ───────────────────────────── job lifecycle ─────────────────────────────

    /** Duplicate-job prevention: one job per fileId; re-enqueue of a live/completed job is a no-op. */
    fun enqueue(fileId: Int, name: String, size: Long): DownloadJob {
        jobs[fileId]?.let { existing ->
            if (existing.state == JobState.COMPLETED && existing.localPath != null) return existing
            if (existing.state != JobState.CANCELLED && existing.state != JobState.FAILED) return existing
            // Retry of a failed/cancelled job keeps history but resets transfer state.
            existing.state = JobState.QUEUED
            existing.error = null
            existing.attempts = 0
            existing.enqueuedAt = clock()
            return existing
        }
        val j = DownloadJob(fileId, name, size, enqueuedAt = clock())
        jobs[fileId] = j
        return j
    }

    fun pause(fileId: Int) {
        jobs[fileId]?.takeIf { it.state == JobState.ACTIVE || it.state == JobState.QUEUED }
            ?.let { it.state = JobState.PAUSED }
    }

    fun resume(fileId: Int) {
        jobs[fileId]?.takeIf { it.state == JobState.PAUSED || it.state == JobState.FAILED }
            ?.let { it.state = JobState.QUEUED; it.nextRetryAt = 0 }
    }

    fun cancel(fileId: Int) {
        jobs[fileId]?.let { it.state = JobState.CANCELLED; it.finishedAt = clock() }
    }

    /** User-requested priority: jumps the queue and gets the top TDLib weight while active. */
    fun boost(fileId: Int) {
        jobs[fileId]?.let { it.boosted = true }
    }

    fun retry(fileId: Int) {
        jobs[fileId]?.takeIf { it.state == JobState.FAILED }?.let {
            it.state = JobState.QUEUED
            it.error = null
            it.nextRetryAt = 0
        }
    }

    fun removeFinished(fileId: Int) {
        jobs[fileId]?.takeIf { it.state == JobState.COMPLETED || it.state == JobState.CANCELLED }
            ?.let { jobs.remove(fileId) }
    }

    // ───────────────────────────── progress / errors ─────────────────────────────

    /** Feed updateFile deltas. [downloaded] is TDLib's absolute downloaded prefix size. */
    fun onProgress(fileId: Int, downloaded: Long, now: Long = clock()) {
        val j = jobs[fileId] ?: return
        val dt = if (j.lastProgressAt > 0) now - j.lastProgressAt else 0
        val delta = downloaded - j.downloaded
        if (dt in 50..10_000 && delta > 0) {
            // Per-job EWMA (alpha 0.3) for per-file speed + ETA.
            val inst = delta * 8_000.0 / dt
            j.speedBps = if (j.speedBps <= 0) inst else j.speedBps + 0.3 * (inst - j.speedBps)
        }
        if (delta > 0) {
            if (windowStart == 0L) windowStart = now
            windowBytes += delta
            if (now - windowStart >= 1_000) {
                aggregate.addSample(windowBytes, now - windowStart)
                windowBytes = 0
                windowStart = now
            }
        }
        j.downloaded = maxOf(j.downloaded, downloaded)
        j.lastProgressAt = now
    }

    fun onComplete(fileId: Int, path: String, now: Long = clock()) {
        jobs[fileId]?.let {
            it.state = JobState.COMPLETED
            it.localPath = path
            it.downloaded = maxOf(it.downloaded, it.size)
            it.finishedAt = now
            it.speedBps = 0.0
        }
    }

    /** Bounded exponential backoff; the job returns to the queue, never straight to ACTIVE. */
    fun onError(fileId: Int, message: String, now: Long = clock()) {
        val j = jobs[fileId] ?: return
        j.attempts++
        j.error = message
        j.state = JobState.QUEUED
        j.speedBps = 0.0
        j.nextRetryAt = now + backoffMs(j.attempts)
        // Error pressure shrinks concurrency immediately (adaptive controller confirms later).
        if (concurrency > profile.minConcurrency) concurrency--
    }

    /** Documented, testable backoff: 1 s base, ×2 per attempt, capped at 60 s. */
    fun backoffMs(attempt: Int): Long =
        minOf(60_000L, 1_000L shl attempt.coerceIn(0, 6))

    // ───────────────────────────── adaptive concurrency ─────────────────────────────

    /**
     * Called once per measurement window (~2 s) with the aggregate bytes/s observed while the
     * current concurrency was in effect. Grows only when the extra slot actually bought
     * throughput (+5 %), shrinks when it cost us (−5 %) or errors piled up; 5 s cooldown so the
     * controller cannot flap. More concurrent transfers is *not* assumed to be faster.
     */
    fun adapt(observedBps: Double, now: Long = clock()) {
        if (now - lastAdaptAt < ADAPT_COOLDOWN_MS) return
        val prev = aggregateBeforeLastChange
        lastAdaptAt = now
        aggregateBeforeLastChange = observedBps
        if (prev <= 0) return
        val cap = effectiveMaxConcurrency()
        when {
            observedBps > prev * 1.05 && concurrency < cap -> concurrency++
            observedBps < prev * 0.95 && concurrency > profile.minConcurrency -> concurrency--
        }
    }

    /** Playback-aware + policy-aware ceiling on concurrency. */
    fun effectiveMaxConcurrency(): Int {
        var cap = profile.maxConcurrency
        if (playbackActive) cap = (cap - 1).coerceAtLeast(profile.minConcurrency)
        if (profile == Profile.LOW_DATA) cap = cap.coerceAtMost(2)
        return cap.coerceAtLeast(profile.minConcurrency)
    }

    fun setProfile(p: Profile) {
        profile = p
        concurrency = concurrency.coerceIn(p.minConcurrency, p.maxConcurrency)
    }

    // ───────────────────────────── the schedule ─────────────────────────────

    /**
     * Fair, priority-aware plan:
     *  - boosted (user-priority) jobs first, then jobs starved longer than [STARVE_MS], then
     *    shortest-remaining-first (so one 2 GB file cannot block small files), then FIFO;
     *  - at most [concurrency] slots (fewer while playing back, 1 on LOW_DATA+metered);
     *  - Wi-Fi-only policy parks everything while metered;
     *  - jobs in backoff are not scheduled before [DownloadJob.nextRetryAt].
     */
    fun schedule(now: Long = clock()): SchedulePlan {
        if (wifiOnly && metered) {
            return SchedulePlan(run = emptyList(), park = jobs.values.filter { it.state == JobState.ACTIVE }.map { it.fileId })
        }
        val slots = if (profile == Profile.LOW_DATA && metered) 1 else concurrency.coerceAtMost(effectiveMaxConcurrency())
        val eligible = jobs.values.filter {
            (it.state == JobState.ACTIVE || it.state == JobState.QUEUED) && it.nextRetryAt <= now
        }
        val ordered = eligible.sortedWith(
            compareByDescending<DownloadJob> { it.boosted }
                .thenBy { starved(it, now) }   // starved jobs jump ahead
                .thenBy { it.remaining }       // short-file fairness: a 2 GB file cannot block smalls
                .thenBy { it.enqueuedAt },     // FIFO tie-break (also keeps selection stable)
        )
        // Preempting a running job is cheap: TDLib parks it at priority 0 and resumes from the
        // persisted prefix later, so no already-downloaded byte is transferred twice.
        val run = ordered.map { it.fileId }.take(slots)
        run.forEach { id ->
            val j = jobs.getValue(id)
            if (j.state == JobState.QUEUED) {
                j.state = JobState.ACTIVE
                if (j.startedAt == 0L) j.startedAt = now
            }
        }
        val park = jobs.values.filter { it.state == JobState.ACTIVE && it.fileId !in run }.map { it.fileId }
        park.forEach { jobs.getValue(it).state = JobState.QUEUED }
        return SchedulePlan(run = run, park = park)
    }

    private fun starved(j: DownloadJob, now: Long): Int =
        if (j.state == JobState.QUEUED && now - j.enqueuedAt > STARVE_MS) 0 else 1

    // ───────────────────────────── persistence ─────────────────────────────

    /** TSV snapshot so the queue survives process death; COMPLETED rows are kept as history. */
    fun toPersist(): String =
        jobs.values.joinToString("\n") { j ->
            listOf(j.fileId, j.name, j.size, j.state.name, j.downloaded, if (j.boosted) 1 else 0,
                j.enqueuedAt, j.attempts, j.localPath ?: "").joinToString("\t")
        }

    fun fromPersist(s: String) {
        for (line in s.lineSequence()) {
            val p = line.split("\t")
            if (p.size < 9) continue
            val id = p[0].toIntOrNull() ?: continue
            val state = runCatching { JobState.valueOf(p[3]) }.getOrDefault(JobState.QUEUED)
            val j = DownloadJob(
                fileId = id, name = p[1], size = p[2].toLongOrNull() ?: 0,
                // Anything in flight when the process died re-enters the queue; TDLib resumes
                // from its persisted prefix, so no completed byte is transferred twice.
                state = if (state == JobState.ACTIVE) JobState.QUEUED else state,
                downloaded = p[4].toLongOrNull() ?: 0,
                boosted = p[5] == "1",
                enqueuedAt = p[6].toLongOrNull() ?: 0,
                attempts = p[7].toIntOrNull() ?: 0,
                localPath = p[8].ifEmpty { null },
            )
            jobs[id] = j
        }
    }

    private companion object {
        const val ADAPT_COOLDOWN_MS = 5_000L
        const val STARVE_MS = 60_000L
    }
}
