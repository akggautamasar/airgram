package com.airbooks.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Player gesture rules: zones, axis locking, brightness/volume/seek mapping and the option cycles.
 * Pure math — the player only executes what these return.
 */
class GestureMathTest {

    private val w = 1080f
    private val h = 2400f

    // ─────────────────────────── zones ───────────────────────────

    @Test
    fun `screen splits into brightness, neutral and volume zones`() {
        assertEquals(GestureZone.LEFT, GestureMath.zoneOf(100f, w))
        assertEquals(GestureZone.CENTER, GestureMath.zoneOf(w / 2f, w))
        assertEquals(GestureZone.RIGHT, GestureMath.zoneOf(w - 100f, w))
    }

    @Test
    fun `zone edges are inclusive of the outer bands`() {
        assertEquals(GestureZone.LEFT, GestureMath.zoneOf(w * GestureMath.EDGE_FRACTION - 1f, w))
        assertEquals(GestureZone.CENTER, GestureMath.zoneOf(w * GestureMath.EDGE_FRACTION, w))
        assertEquals(GestureZone.RIGHT, GestureMath.zoneOf(w * (1f - GestureMath.EDGE_FRACTION) + 1f, w))
    }

    @Test
    fun `degenerate width is treated as the centre`() {
        assertEquals(GestureZone.CENTER, GestureMath.zoneOf(10f, 0f))
    }

    @Test
    fun `audio playback never offers a brightness gesture`() {
        assertTrue(GestureMath.allowsBrightness(isAudio = false))
        assertFalse(GestureMath.allowsBrightness(isAudio = true))
    }

    // ─────────────────────────── vertical drags ───────────────────────────

    @Test
    fun `dragging up raises brightness and down lowers it`() {
        assertEquals(0.75f, GestureMath.brightnessFrom(0.5f, -h / 4f, h), 0.001f)
        assertEquals(0.25f, GestureMath.brightnessFrom(0.5f, h / 4f, h), 0.001f)
    }

    @Test
    fun `brightness stays inside the usable window`() {
        assertEquals(1f, GestureMath.brightnessFrom(0.9f, -h * 5f, h), 0.001f)
        // Never fully black: the floor keeps the picture visible.
        assertEquals(0.01f, GestureMath.brightnessFrom(0.2f, h * 5f, h), 0.001f)
    }

    @Test
    fun `dragging up raises volume and clamps to the stream maximum`() {
        assertEquals(15, GestureMath.volumeFrom(10, 15, -h / 2f, h))
        assertEquals(5, GestureMath.volumeFrom(10, 15, h / 3f, h))
        assertEquals(7, GestureMath.volumeFrom(10, 15, h / 6f, h))
        assertEquals(0, GestureMath.volumeFrom(10, 15, h * 3f, h))
    }

    @Test
    fun `volume never goes negative or past the real maximum`() {
        assertEquals(0, GestureMath.volumeFrom(0, 15, h, h))
        assertEquals(15, GestureMath.volumeFrom(15, 15, -h, h))
        assertEquals(0, GestureMath.volumeFrom(3, 0, -h, h))
    }

    @Test
    fun `swipe fraction is normalised to half the screen and clamped`() {
        assertEquals(0f, GestureMath.swipeFraction(0f, h), 0.001f)
        assertEquals(0.5f, GestureMath.swipeFraction(h / 4f, h), 0.001f)
        assertEquals(1f, GestureMath.swipeFraction(h * 4f, h), 0.001f)
        assertEquals(1f, GestureMath.swipeFraction(-h * 4f, h), 0.001f)
    }

    // ─────────────────────────── seeking ───────────────────────────

    @Test
    fun `horizontal drag seeks proportionally across the width`() {
        assertEquals(60, GestureMath.seekFromDrag(w / 2f, w))
        assertEquals(-30, GestureMath.seekFromDrag(-w / 4f, w))
        assertEquals(0, GestureMath.seekFromDrag(0f, w))
    }

    @Test
    fun `seek drag is capped so a long swipe cannot jump arbitrarily`() {
        assertEquals(
            GestureMath.MAX_SWIPE_SEEK_SECONDS,
            GestureMath.seekFromDrag(w * 50f, w),
        )
        assertEquals(
            -GestureMath.MAX_SWIPE_SEEK_SECONDS,
            GestureMath.seekFromDrag(-w * 50f, w),
        )
    }

    @Test
    fun `double tap seeks ten seconds on the edges and nothing in the centre`() {
        assertEquals(-GestureMath.SEEK_STEP_SECONDS, GestureMath.doubleTapSeconds(80f, w))
        assertEquals(GestureMath.SEEK_STEP_SECONDS, GestureMath.doubleTapSeconds(w - 80f, w))
        assertEquals(0, GestureMath.doubleTapSeconds(w / 2f, w))
    }

    @Test
    fun `seek targets are clamped to the item bounds`() {
        assertEquals(0L, GestureMath.clampSeek(10_000L, -20, 60_000L))
        assertEquals(60_000L, GestureMath.clampSeek(50_000L, 20, 60_000L))
        assertEquals(30_000L, GestureMath.clampSeek(20_000L, 10, 60_000L))
    }

    @Test
    fun `unknown duration does not invent a seekable range`() {
        // Live/non-seekable sources report 0 or unset: stay where the playhead already is.
        assertEquals(10_000L, GestureMath.clampSeek(10_000L, 30, 0L))
        assertEquals(10_000L, GestureMath.clampSeek(10_000L, -30, -1L))
        assertEquals(10_000L, GestureMath.clampSeek(10_000L, -30, Long.MIN_VALUE))
    }

    // ─────────────────────────── axis lock ───────────────────────────

    @Test
    fun `nothing is decided before the touch slop is passed`() {
        val lock = GestureAxisLock(slopPx = 24f)
        lock.down(500f, 500f)
        assertEquals(GestureAxis.NONE, lock.move(505f, 510f))
        assertFalse(lock.locked)
    }

    @Test
    fun `a vertical-first drag locks vertical`() {
        val lock = GestureAxisLock(24f)
        lock.down(500f, 500f)
        assertEquals(GestureAxis.VERTICAL, lock.move(502f, 560f))
        assertTrue(lock.locked)
    }

    @Test
    fun `a horizontal-first drag locks horizontal`() {
        val lock = GestureAxisLock(24f)
        lock.down(500f, 500f)
        assertEquals(GestureAxis.HORIZONTAL, lock.move(560f, 502f))
    }

    @Test
    fun `the lock cannot flip mid-gesture when the finger drifts diagonally`() {
        val lock = GestureAxisLock(24f)
        lock.down(500f, 500f)
        assertEquals(GestureAxis.VERTICAL, lock.move(500f, 560f))
        assertEquals(GestureAxis.VERTICAL, lock.move(900f, 560f))
        assertEquals(GestureAxis.VERTICAL, lock.move(900f, 200f))
        assertEquals(400f, lock.deltaX, 0.01f)
        assertEquals(-300f, lock.deltaY, 0.01f)
    }

    @Test
    fun `lifting the finger releases the lock for the next gesture`() {
        val lock = GestureAxisLock(24f)
        lock.down(0f, 0f)
        lock.move(0f, 100f)
        lock.up()
        assertEquals(GestureAxis.NONE, lock.axis)
        lock.down(0f, 0f)
        assertEquals(GestureAxis.HORIZONTAL, lock.move(100f, 0f))
    }

    @Test
    fun `an exactly diagonal drag stays undecided rather than guessing`() {
        val lock = GestureAxisLock(24f)
        lock.down(0f, 0f)
        assertEquals(GestureAxis.NONE, lock.move(50f, 50f))
    }

    // ─────────────────────────── option cycles ───────────────────────────

    @Test
    fun `speed cycles through the practical rates and wraps`() {
        assertEquals(1.25f, GestureMath.nextSpeed(1f), 0.001f)
        assertEquals(1.5f, GestureMath.nextSpeed(1.25f), 0.001f)
        assertEquals(GestureMath.SPEEDS[0], GestureMath.nextSpeed(GestureMath.SPEEDS.last()), 0.001f)
    }

    @Test
    fun `an unsupported speed resets to normal instead of compounding`() {
        assertEquals(1f, GestureMath.nextSpeed(1.1f), 0.001f)
    }

    @Test
    fun `aspect ratio cycles fit, crop, stretch`() {
        assertEquals(4, GestureMath.nextResizeMode(0))
        assertEquals(3, GestureMath.nextResizeMode(4))
        assertEquals(0, GestureMath.nextResizeMode(3))
        assertEquals("Fit", GestureMath.resizeLabel(0))
        assertEquals("Crop", GestureMath.resizeLabel(4))
        assertEquals("Stretch", GestureMath.resizeLabel(3))
        assertEquals(0, GestureMath.nextResizeMode(99))
        assertEquals("Fit", GestureMath.resizeLabel(99))
    }

    // ─────────────────────────── buffer reporting ───────────────────────────

    @Test
    fun `buffered percent measures what is ahead of the playhead`() {
        assertEquals(20, GestureMath.bufferedPercent(10_000L, 30_000L, 100_000L))
        assertEquals(0, GestureMath.bufferedPercent(30_000L, 10_000L, 100_000L))
        assertEquals(100, GestureMath.bufferedPercent(0L, 200_000L, 100_000L))
    }

    @Test
    fun `buffered percent reports unknown instead of guessing for live sources`() {
        assertEquals(-1, GestureMath.bufferedPercent(10_000L, 30_000L, 0L))
        assertEquals(-1, GestureMath.bufferedPercent(10_000L, 30_000L, -1L))
    }

    @Test
    fun `sleep timer deadline is zero when off`() {
        assertEquals(0L, GestureMath.sleepDeadline(0, 1_000L))
        assertEquals(301_000L, GestureMath.sleepDeadline(5, 1_000L))
        assertEquals(0L, GestureMath.sleepDeadline(-3, 1_000L))
    }

    @Test
    fun `sleep presets are practical durations`() {
        assertTrue(GestureMath.SLEEP_MINUTES.all { it in 1..180 })
        assertTrue(GestureMath.SLEEP_MINUTES.contains(15))
    }

    // ─────────────────────────── single-tap policy ───────────────────────────

    @Test
    fun `first tap only reveals controls, centre tap then toggles play`() {
        // Controls hidden: any tap reveals them — never an accidental play/pause (§5.E).
        assertFalse(GestureMath.centerTapTogglesPlay(false, 0.5f))
        assertFalse(GestureMath.centerTapTogglesPlay(false, 0.05f))
        assertFalse(GestureMath.centerTapTogglesPlay(false, 0.95f))
        // Controls visible: centre toggles play/pause…
        assertTrue(GestureMath.centerTapTogglesPlay(true, 0.5f))
        assertTrue(GestureMath.centerTapTogglesPlay(true, GestureMath.EDGE_FRACTION + 0.001f))
        assertTrue(GestureMath.centerTapTogglesPlay(true, 1f - GestureMath.EDGE_FRACTION - 0.001f))
        // …while the edge bands (the double-tap seek zones) hide the controls instead, so a
        // single tap there can never be confused with a seek double-tap.
        assertFalse(GestureMath.centerTapTogglesPlay(true, GestureMath.EDGE_FRACTION))
        assertFalse(GestureMath.centerTapTogglesPlay(true, 0.1f))
        assertFalse(GestureMath.centerTapTogglesPlay(true, 0.95f))
        assertFalse(GestureMath.centerTapTogglesPlay(true, 1f - GestureMath.EDGE_FRACTION))
    }
}
