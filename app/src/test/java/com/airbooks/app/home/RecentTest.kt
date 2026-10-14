package com.airbooks.app.home

import com.airbooks.app.tg.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentTest {
    private fun e(msg: Long, pos: Long) = Recent.Entry(1, msg, msg.toInt() * 7, Kind.VIDEO, "v$msg", 1000, pos, 1700000000000 + msg)

    @Test
    fun `note moves to front and updates position`() {
        var list = Recent.note(emptyList(), e(1, 5000))
        list = Recent.note(list, e(2, 6000))
        list = Recent.note(list, e(1, 9000))
        assertEquals(2, list.size)
        assertEquals(1, list[0].msgId)
        assertEquals(9000, list[0].positionMs)
    }

    @Test
    fun `list is capped`() {
        var list = emptyList<Recent.Entry>()
        for (i in 1L..30L) list = Recent.note(list, e(i, i * 1000))
        assertEquals(Recent.MAX_ENTRIES, list.size)
        assertEquals(30, list[0].msgId)
    }

    @Test
    fun `encode decode roundtrip survives names with spaces`() {
        val list = listOf(e(1, 1234).copy(name = "lecture 5 - diffraction.mp4"), e(2, 0))
        val back = Recent.decode(Recent.encode(list))
        assertEquals(list, back)
    }

    @Test
    fun `corrupt lines are skipped not crashed`() {
        val good = Recent.encode(listOf(e(3, 77)))
        val back = Recent.decode("garbage\n" + good + "\n1\t2\n")
        assertEquals(1, back.size)
        assertEquals(3, back[0].msgId)
    }

    @Test
    fun `toMedia is playable`() {
        val m = Recent.toMedia(e(4, 42000))
        assertEquals(28, m.fileId)
        assertEquals(Kind.VIDEO, m.kind)
        assertTrue(m.key.isNotBlank())
    }
}
