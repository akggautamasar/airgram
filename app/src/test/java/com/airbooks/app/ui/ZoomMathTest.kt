package com.airbooks.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {
    @Test
    fun `scale is clamped to supported range`() {
        assertEquals(ZoomMath.MIN_SCALE, ZoomMath.clampScale(0.1f), 0f)
        assertEquals(ZoomMath.MAX_SCALE, ZoomMath.clampScale(99f), 0f)
        assertEquals(2f, ZoomMath.clampScale(2f), 0f)
    }

    @Test
    fun `fitted image cannot pan`() {
        val (x, y) = ZoomMath.clampPan(1f, 500f, -500f, 1080f, 1920f)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    @Test
    fun `zoomed image pans within bounds`() {
        val (mx, my) = ZoomMath.maxPan(3f, 1000f, 1000f)
        assertEquals(1000f, mx, 0.01f)
        val (x, y) = ZoomMath.clampPan(3f, 5000f, -5000f, 1000f, 1000f)
        assertEquals(mx, x, 0.01f)
        assertEquals(-my, y, 0.01f)
    }

    @Test
    fun `double tap toggles fit and zoom`() {
        assertEquals(2.5f, ZoomMath.doubleTapScale(1f), 0f)
        assertEquals(ZoomMath.MIN_SCALE, ZoomMath.doubleTapScale(3f), 0f)
        assertTrue(ZoomMath.isFitted(1f))
        assertTrue(!ZoomMath.isFitted(2.5f))
    }
}
