package com.airbooks.app.ui

import kotlin.math.abs

/**
 * Pure pan/zoom clamp math for the image viewer: keeps the picture covering (or centered in)
 * the viewport at any scale, so gestures can never fling it off-screen. Unit-tested.
 */
object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 6f

    fun clampScale(s: Float): Float = s.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Max pan (px) allowed at [scale] for a [viewW]x[viewH] viewport; 0 when fitted. */
    fun maxPan(scale: Float, viewW: Float, viewH: Float): Pair<Float, Float> {
        val sx = (viewW * (scale - 1f)) / 2f
        val sy = (viewH * (scale - 1f)) / 2f
        return maxOf(0f, sx) to maxOf(0f, sy)
    }

    fun clampPan(scale: Float, panX: Float, panY: Float, viewW: Float, viewH: Float): Pair<Float, Float> {
        val (mx, my) = maxPan(scale, viewW, viewH)
        return panX.coerceIn(-mx, mx) to panY.coerceIn(-my, my)
    }

    /** Double-tap target: fitted -> zoomed at the tap point, zoomed -> fitted. */
    fun doubleTapScale(current: Float): Float = if (current > 1.01f) MIN_SCALE else 2.5f

    fun isFitted(scale: Float): Boolean = abs(scale - MIN_SCALE) < 0.01f
}
