package com.airbooks.app.ui

import kotlin.math.abs
import kotlin.math.round

/** Which part of the screen a touch landed in. */
enum class GestureZone { LEFT, CENTER, RIGHT }

/** Axis an in-progress drag is locked to. */
enum class GestureAxis { NONE, VERTICAL, HORIZONTAL }

/**
 * Pure gesture math for the player: screen zones, drag-axis locking, and how a drag maps onto
 * brightness, volume and seek.
 *
 * Deliberately free of Compose, Android and player state, so the rules are unit-testable and the
 * gesture layer can never drift into playback logic — [GestureAxisLock] decides *which* gesture is
 * happening, the player only executes the resulting numbers.
 */
object GestureMath {
    /** The outer bands own brightness/volume; the middle is left to tap and double-tap. */
    const val EDGE_FRACTION = 0.35f

    /** Double-tap and on-screen button seek distance. */
    const val SEEK_STEP_SECONDS = 10

    /** A full-width horizontal drag seeks this far (either direction). */
    const val MAX_SWIPE_SEEK_SECONDS = 120

    /** Practical playback rates, in cycle order. */
    val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

    /**
     * media3 `AspectRatioFrameLayout` resize modes: FIT = 0, FILL = 3, ZOOM = 4.
     * Cycled in the order people expect: fit → crop → stretch.
     */
    val RESIZE_MODES = intArrayOf(0, 4, 3)
    val RESIZE_LABELS = arrayOf("Fit", "Crop", "Stretch")

    val SLEEP_MINUTES = intArrayOf(5, 10, 15, 30, 45, 60)

    fun zoneOf(x: Float, width: Float): GestureZone = when {
        width <= 0f -> GestureZone.CENTER
        x < width * EDGE_FRACTION -> GestureZone.LEFT
        x > width * (1f - EDGE_FRACTION) -> GestureZone.RIGHT
        else -> GestureZone.CENTER
    }

    /** Audio has no picture, so a brightness gesture there would be meaningless. */
    fun allowsBrightness(isAudio: Boolean): Boolean = !isAudio

    /** 0..1 progress of a vertical drag against half the viewport height (for overlays). */
    fun swipeFraction(deltaY: Float, height: Float): Float =
        if (height <= 0f) 0f else (abs(deltaY) / (height / 2f)).coerceIn(0f, 1f)

    /**
     * Window brightness from a vertical drag: dragging up brightens. The current value is the
     * anchor, and the whole screen height maps to the full 0..1 range.
     */
    fun brightnessFrom(current: Float, deltaY: Float, height: Float): Float {
        if (height <= 0f) return current.coerceIn(0.01f, 1f)
        return (current - deltaY / height).coerceIn(0.01f, 1f)
    }

    /** Media-stream volume from a vertical drag, clamped to the stream's real maximum. */
    fun volumeFrom(current: Int, max: Int, deltaY: Float, height: Float): Int {
        if (max <= 0) return 0
        if (height <= 0f) return current.coerceIn(0, max)
        val delta = (-deltaY / height) * max
        return (current + delta).coerceIn(0f, max.toFloat()).toInt().coerceIn(0, max)
    }

    /** Horizontal drag → signed seek in seconds, proportional to how far across the screen. */
    fun seekFromDrag(deltaX: Float, width: Float): Int {
        if (width <= 0f) return 0
        val raw = (deltaX / width) * MAX_SWIPE_SEEK_SECONDS
        return round(raw).toInt().coerceIn(-MAX_SWIPE_SEEK_SECONDS, MAX_SWIPE_SEEK_SECONDS)
    }

    /**
     * Clamps a relative seek to what the media actually supports. When the real duration is
     * unknown (live or non-seekable sources) there is no honest range to clamp to, so the playhead
     * is left alone instead of guessing — callers show the seek controls as unavailable.
     */
    fun clampSeek(positionMs: Long, deltaSeconds: Int, durationMs: Long): Long {
        if (durationMs <= 0L) return positionMs
        return (positionMs + deltaSeconds * 1000L).coerceIn(0L, durationMs)
    }

    fun doubleTapSeconds(x: Float, width: Float): Int = when (zoneOf(x, width)) {
        GestureZone.LEFT -> -SEEK_STEP_SECONDS
        GestureZone.RIGHT -> SEEK_STEP_SECONDS
        GestureZone.CENTER -> 0
    }

    /** Next practical rate; an unsupported current value resets to normal speed. */
    fun nextSpeed(current: Float): Float {
        val i = SPEEDS.indexOfFirst { abs(it - current) < 0.01f }
        return if (i < 0) 1f else SPEEDS[(i + 1) % SPEEDS.size]
    }

    fun nextResizeMode(current: Int): Int {
        val i = RESIZE_MODES.indexOf(current)
        return if (i < 0) RESIZE_MODES[0] else RESIZE_MODES[(i + 1) % RESIZE_MODES.size]
    }

    fun resizeLabel(mode: Int): String {
        val i = RESIZE_MODES.indexOf(mode)
        return if (i < 0) RESIZE_LABELS[0] else RESIZE_LABELS[i]
    }

    /**
     * Percentage of the item buffered ahead of the playhead, or -1 when the real duration is
     * unknown — callers must show "unknown" rather than guessing.
     */
    fun bufferedPercent(positionMs: Long, bufferedMs: Long, durationMs: Long): Int =
        if (durationMs <= 0L) -1
        else (((bufferedMs - positionMs) * 100) / durationMs).toInt().coerceIn(0, 100)

    /** Sleep timer deadline, or 0 for "off"; `endOfItem` is handled by the player, not here. */
    fun sleepDeadline(minutes: Int, nowMs: Long): Long =
        if (minutes <= 0) 0L else nowMs + minutes * 60_000L
}

/**
 * Decides which gesture a drag is, once, and keeps that decision for the whole gesture.
 *
 * The first movement past the touch slop wins: vertical → brightness/volume, horizontal → seek.
 * Locking prevents diagonal finger travel from changing behaviour mid-drag.
 */
class GestureAxisLock(private val slopPx: Float = 24f) {
    var axis: GestureAxis = GestureAxis.NONE
        private set
    var startX = 0f
        private set
    var startY = 0f
        private set
    var lastX = 0f
        private set
    var lastY = 0f
        private set

    val locked: Boolean get() = axis != GestureAxis.NONE
    val deltaX: Float get() = lastX - startX
    val deltaY: Float get() = lastY - startY

    fun down(x: Float, y: Float) {
        axis = GestureAxis.NONE
        startX = x; startY = y
        lastX = x; lastY = y
    }

    fun move(x: Float, y: Float): GestureAxis {
        lastX = x; lastY = y
        if (axis != GestureAxis.NONE) return axis
        val dx = abs(x - startX)
        val dy = abs(y - startY)
        axis = when {
            dy >= slopPx && dy > dx -> GestureAxis.VERTICAL
            dx >= slopPx && dx > dy -> GestureAxis.HORIZONTAL
            else -> GestureAxis.NONE
        }
        return axis
    }

    fun up() {
        axis = GestureAxis.NONE
    }
}
