package com.airbooks.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the download scheduler: dedupe, concurrency, fairness, backoff, persistence. */
class SchedulerTest {
    private var now = 0L
    private fun s(profile: DownloadScheduler.Profile = DownloadScheduler.Profile.BALANCED) =
        DownloadScheduler(profile) { now }

    private val GB = 1024L * 1024 * 1024

    @Test
    fun `duplicate jobs are prevented per fileId`() {
        val sc = s()
        val a = sc.enqueue(7, "a.mp4", GB)
        val b = sc.enqueue(7, "a.mp4", GB)
        assertSame(a, b)
        assertEquals(1, sc.queue.size)
    }

    @Test
    fun `completed job with a local path is not re-downloaded`() {
        val sc = s()
        sc.enqueue(7, "a.mp4", GB)
        sc.schedule(0)
        sc.onComplete(7, "/cache/tdfiles/a.mp4", 1)
        val again = sc.enqueue(7, "a.mp4", GB)
        assertEquals(DownloadScheduler.JobState.COMPLETED, again.state)
        assertNotNull(again.localPath)
    }

    @Test
    fun `concurrency respects profile base and caps`() {
        val sc = s(DownloadScheduler.Profile.HIGH_SPEED)
        repeat(6) { sc.enqueue(it, "f$it", 100 * GB) }
        val plan = sc.schedule(0)
        assertEquals(4, plan.run.size) // HIGH_SPEED base concurrency
        val low = s(DownloadScheduler.Profile.LOW_DATA)
        repeat(3) { low.enqueue(it, "f$it", 100 * GB) }
        assertEquals(1, low.schedule(0).run.size)
    }

    @Test
    fun `low data on metered network never opens more than one transfer`() {
        val sc = s(DownloadScheduler.Profile.LOW_DATA)
        sc.metered = true
        repeat(4) { sc.enqueue(it, "f$it", GB) }
        assertEquals(1, sc.schedule(0).run.size)
    }

    @Test
    fun `wifi-only policy parks everything while metered`() {
        val sc = s()
        sc.enqueue(1, "a", GB)
        sc.schedule(0)
        sc.wifiOnly = true
        sc.metered = true
        val plan = sc.schedule(1)
        assertTrue(plan.run.isEmpty())
        assertEquals(listOf(1), plan.park)
    }

    @Test
    fun `playback reduces the concurrency ceiling`() {
        val sc = s(DownloadScheduler.Profile.HIGH_SPEED)
        assertEquals(6, sc.effectiveMaxConcurrency())
        sc.playbackActive = true
        assertEquals(5, sc.effectiveMaxConcurrency())
    }

    @Test
    fun `boosted user-priority job runs first with the top tdlib weight`() {
        val sc = s(DownloadScheduler.Profile.HIGH_SPEED)
        sc.enqueue(1, "big", 10 * GB)
        sc.enqueue(2, "small", GB)
        sc.boost(2)
        val plan = sc.schedule(0)
        assertEquals(2, plan.run[0])
        assertTrue(plan.priorityFor(2) > plan.priorityFor(1))
        assertTrue(plan.priorityFor(1) in 8..30)
    }

    @Test
    fun `short files are not blocked behind a huge one`() {
        val sc = s(DownloadScheduler.Profile.LOW_DATA) // one slot
        sc.enqueue(1, "huge", 2 * GB)
        sc.schedule(0)
        sc.enqueue(2, "tiny", 1024L * 1024)
        val plan = sc.schedule(1_000)
        assertEquals(listOf(2), plan.run)
        assertEquals(listOf(1), plan.park) // parked, resumes from prefix later
    }

    @Test
    fun `starved queue jumpers overtake equal-size newcomers`() {
        val sc = s(DownloadScheduler.Profile.LOW_DATA)
        sc.enqueue(1, "a", GB)
        sc.enqueue(2, "b", GB)
        sc.schedule(0) // 1 active, 2 waiting
        now = 61_000   // job 2 starved beyond STARVE_MS
        sc.enqueue(3, "c", GB)
        val plan = sc.schedule(now)
        assertEquals(2, plan.run[0])
    }

    @Test
    fun `failed jobs back off exponentially and are not scheduled early`() {
        val sc = s()
        sc.enqueue(1, "a", GB)
        sc.schedule(0)
        sc.onError(1, "net", 0)
        assertEquals(2_000L, sc.job(1)!!.nextRetryAt)   // 1 s << 1
        assertEquals(DownloadScheduler.JobState.QUEUED, sc.job(1)!!.state)
        assertTrue(sc.schedule(1_000).run.isEmpty())    // still in backoff
        assertEquals(listOf(1), sc.schedule(2_000).run) // eligible again
    }

    @Test
    fun `backoff is bounded at sixty seconds`() {
        val sc = s()
        assertEquals(60_000L, sc.backoffMs(6))
        assertEquals(60_000L, sc.backoffMs(40))
        assertEquals(1_000L, sc.backoffMs(0))
    }

    @Test
    fun `error pressure shrinks concurrency immediately`() {
        val sc = s()
        assertEquals(2, sc.concurrency)
        sc.enqueue(1, "a", GB)
        sc.schedule(0)
        sc.onError(1, "boom", 0)
        assertEquals(1, sc.concurrency)
    }

    @Test
    fun `adaptive controller grows only when the extra slot bought throughput`() {
        val sc = s()
        sc.adapt(1_000_000.0, 0)          // first window: baseline
        sc.adapt(2_000_000.0, 6_000)      // +100 % → grow
        assertEquals(3, sc.concurrency)
        sc.adapt(1_000_000.0, 12_000)     // −50 % → shrink
        assertEquals(2, sc.concurrency)
        sc.adapt(500_000.0, 13_000)       // inside cooldown → ignored
        assertEquals(2, sc.concurrency)
    }

    @Test
    fun `adaptive controller never leaves profile bounds`() {
        val sc = s(DownloadScheduler.Profile.LOW_DATA)
        repeat(10) { sc.adapt(1_000_000.0 * (it + 2), it * 6_000L) }
        assertTrue(sc.concurrency <= 2)
        repeat(10) { sc.adapt(100.0, 100_000 + it * 6_000L) }
        assertTrue(sc.concurrency >= 1)
    }

    @Test
    fun `progress speed and eta are computed from deltas`() {
        val sc = s()
        val j = sc.enqueue(1, "a", 100L * 1024 * 1024)
        sc.schedule(0)
        sc.onProgress(1, 1L * 1024 * 1024, 1_000)
        sc.onProgress(1, 2L * 1024 * 1024, 2_000) // 1 MB in 1 s → 8 Mbit/s
        assertTrue(j.speedBps > 0)
        assertTrue(j.etaMs() > 0)
        assertTrue(j.fraction in 0.01f..0.05f)
        assertTrue(sc.aggregateSpeedBps() > 0)
    }

    @Test
    fun `pause resume cancel behave`() {
        val sc = s()
        sc.enqueue(1, "a", GB)
        sc.schedule(0)
        sc.pause(1)
        assertEquals(DownloadScheduler.JobState.PAUSED, sc.job(1)!!.state)
        assertTrue(sc.schedule(1).run.isEmpty())
        sc.resume(1)
        assertEquals(listOf(1), sc.schedule(2).run)
        sc.cancel(1)
        assertEquals(DownloadScheduler.JobState.CANCELLED, sc.job(1)!!.state)
        sc.removeFinished(1)
        assertNull(sc.job(1))
    }

    @Test
    fun `queue survives process death and re-enters as queued`() {
        val sc = s()
        sc.enqueue(1, "a", GB)
        sc.enqueue(2, "b", 2 * GB)
        sc.schedule(0)
        sc.onProgress(1, 500_000, 1_000)
        sc.onComplete(2, "/p/b", 1_500)
        val blob = sc.toPersist()

        val restored = DownloadScheduler { now }
        restored.fromPersist(blob)
        assertEquals(DownloadScheduler.JobState.QUEUED, restored.job(1)!!.state) // was ACTIVE
        assertEquals(500_000L, restored.job(1)!!.downloaded)                      // prefix kept
        assertEquals(DownloadScheduler.JobState.COMPLETED, restored.job(2)!!.state)
        assertEquals("/p/b", restored.job(2)!!.localPath)
    }

    @Test
    fun `plan priorities stay inside tdlib range and streaming headroom`() {
        val sc = s(DownloadScheduler.Profile.HIGH_SPEED)
        repeat(6) { sc.enqueue(it, "f$it", GB) }
        val plan = sc.schedule(0)
        plan.run.forEach { assertTrue(plan.priorityFor(it) in 8..30) }
        assertEquals(0, plan.priorityFor(999)) // not scheduled → priority 0 (parked)
        assertFalse(plan.run.isEmpty())
    }
}
