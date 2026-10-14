package com.airbooks.app.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-level JVM tests for the download flow: interruption/resume, cancellation,
 * restart recovery, completed-file integrity, streaming contention and trial CSV layout.
 * TDLib itself is exercised on-device; these pin the contract the glue relies on.
 */
class DownloadFlowTest {
    private var now = 0L
    private fun s(p: DownloadScheduler.Profile = DownloadScheduler.Profile.BALANCED) =
        DownloadScheduler(p) { now }

    private val MB = 1024L * 1024

    @Test
    fun `interrupted download resumes without losing completed bytes`() {
        val sc = s()
        sc.enqueue(1, "a", 100 * MB)
        sc.schedule(0)
        sc.onProgress(1, 40 * MB, 1_000)
        sc.onError(1, "transient", 2_000)              // network blip
        assertEquals(40 * MB, sc.job(1)!!.downloaded)  // prefix retained
        assertEquals(DownloadScheduler.JobState.QUEUED, sc.job(1)!!.state)
        now = 5_000
        val plan = sc.schedule(now)                   // backoff elapsed
        assertEquals(listOf(1), plan.run)
        assertEquals(40 * MB, sc.job(1)!!.downloaded)  // resumes, never restarts from zero
    }

    @Test
    fun `cancellation stops scheduling and keeps history until removed`() {
        val sc = s()
        sc.enqueue(1, "a", 10 * MB)
        sc.schedule(0)
        sc.cancel(1)
        assertTrue(sc.schedule(1).run.isEmpty())
        assertEquals(DownloadScheduler.JobState.CANCELLED, sc.job(1)!!.state)
        sc.removeFinished(1)
        assertTrue(sc.queue.isEmpty())
    }

    @Test
    fun `process death restores queue with prefixes and history`() {
        val sc = s()
        sc.enqueue(1, "a", 10 * MB)
        sc.enqueue(2, "b", 20 * MB)
        sc.schedule(0)
        sc.onProgress(1, 5 * MB, 500)
        sc.onComplete(2, "/p/b", 900)
        val blob = sc.toPersist()
        val restored = DownloadScheduler { now }
        restored.fromPersist(blob)
        assertEquals(DownloadScheduler.JobState.QUEUED, restored.job(1)!!.state)
        assertEquals(5 * MB, restored.job(1)!!.downloaded)
        assertEquals(DownloadScheduler.JobState.COMPLETED, restored.job(2)!!.state)
        // and the restored queue schedules again
        assertEquals(1, restored.schedule(1_000).run.size)
    }

    @Test
    fun `completed files are validated before being trusted`() {
        val good = File.createTempFile("dl-good", ".bin")
        good.writeBytes(ByteArray(4096))
        val bad = File.createTempFile("dl-bad", ".bin")
        bad.writeBytes(ByteArray(1024))
        assertTrue(FileValidation.isValid(good.absolutePath, 4096))
        assertFalse(FileValidation.isValid(bad.absolutePath, 4096))   // size mismatch
        assertFalse(FileValidation.isValid("/nope/missing.bin", 10))
        assertFalse(FileValidation.isValid(null, 10))
        assertTrue(FileValidation.isValid(good.absolutePath, 0))      // unknown size → existence check
        good.delete(); bad.delete()
    }

    @Test
    fun `sha-256 matches the published vector for abc`() {
        val f = File.createTempFile("sha", ".txt")
        f.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FileValidation.sha256(f.absolutePath),
        )
        f.delete()
    }

    @Test
    fun `streaming contention shrinks background concurrency and restores it`() {
        val sc = s(DownloadScheduler.Profile.HIGH_SPEED)
        repeat(6) { sc.enqueue(it, "f$it", 500 * MB) }
        sc.schedule(0)
        val before = sc.concurrency
        sc.playbackActive = true
        assertEquals(5, sc.effectiveMaxConcurrency())   // one slot yielded to the player
        sc.playbackPressure()                           // sustained rebuffering
        assertTrue(sc.concurrency <= before)
        sc.playbackActive = false
        assertEquals(6, sc.effectiveMaxConcurrency())   // restored after playback stabilises
    }

    @Test
    fun `the same file never runs twice concurrently`() {
        val sc = s(DownloadScheduler.Profile.HIGH_SPEED)
        sc.enqueue(5, "x", 10 * MB)
        sc.enqueue(5, "x", 10 * MB)
        val plan = sc.schedule(0)
        assertEquals(1, plan.run.count { it == 5 })
        val plan2 = sc.schedule(1_000)
        assertEquals(1, plan2.run.count { it == 5 })
    }

    @Test
    fun `trial csv escapes commas and quotes and computes medians`() {
        val t = com.airbooks.app.bench.Trial(
            tsEpochMs = 1, scenario = "single", appVersion = "1.1", fileId = 3,
            name = "a, \"tricky\" name.mp4", sizeBytes = 10, ttffMs = 2, completionMs = 3,
            sustainedMBps = 4.5, peakMBps = 6.0, retries = 0, redundantBytes = 0,
            cpuMs = 7, batteryDeltaPct = -1, network = "wifi", concurrency = 2,
            profile = "Balanced", ok = true,
        )
        val row = t.toCsvRow()
        assertTrue(row.contains("\"a, \"\"tricky\"\" name.mp4\""))
        assertEquals(18, com.airbooks.app.bench.Trial.HEADER.split(",").size)
        assertEquals(4.5, com.airbooks.app.bench.Trial.median(listOf(1.0, 4.5, 8.0)), 0.0)
        assertEquals(0.0, com.airbooks.app.bench.Trial.median(emptyList()), 0.0)
    }
}
