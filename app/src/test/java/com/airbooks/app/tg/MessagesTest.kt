package com.airbooks.app.tg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure chat-list rules: day buckets, separator labels, list structuring for a
 * `reverseLayout` conversation, previews and delivery ticks. No TdApi and no Android involved.
 */
class MessagesTest {

    private val day = 86_400
    /** 2026-10-15 00:00 UTC — the epoch-day for that date is 20741. */
    private val day2 = 20741 * 86_400
    private val day1 = day2 - day

    private fun msg(
        id: Long,
        date: Int = day2 + 3600,
        outgoing: Boolean = false,
        album: Long = 0L,
        kind: MsgKind = MsgKind.TEXT,
        text: String = "",
        caption: String = "",
        fileName: String = "",
    ) = Msg(
        id = id, chatId = 7L, date = date, outgoing = outgoing, albumId = album,
        kind = kind, text = text, caption = caption, fileName = fileName,
    )

    private fun rows(
        msgs: List<Msg>,
        lastRead: Long = 0L,
        tz: Int = 0,
    ): List<ChatItem> = ChatStruct.build(msgs, tz, lastRead) { "D$it" }

    private fun ids(rows: List<ChatItem>): List<String> = rows.map {
        when (it) {
            is DateHeader -> "day"
            is UnreadHeader -> "unread"
            is Single -> "m${it.msg.id}"
            is Album -> "album(${it.msgs.joinToString(",") { m -> m.id.toString() }})"
        }
    }

    // ─────────────────────────── day buckets ───────────────────────────

    @Test
    fun `day bucket rolls over at local midnight in utc`() {
        assertEquals(0, dayBucket(0, 0))
        assertEquals(0, dayBucket(day - 1, 0))
        assertEquals(1, dayBucket(day, 0))
    }

    @Test
    fun `day bucket honours the timezone offset`() {
        val ist = 19_800 // UTC+5:30
        // 1970-01-02 00:00 in IST happens 5.5 h before UTC midnight.
        assertEquals(1, dayBucket(day - ist, ist))
        assertEquals(0, dayBucket(day - ist - 1, ist))
    }

    @Test
    fun `negative offsets before the epoch floor correctly`() {
        assertEquals(-1, dayBucket(-1, 0))
        assertEquals(0, dayBucket(-1, 3_600))
    }

    // ─────────────────────────── calendar ───────────────────────────

    @Test
    fun `civil calendar resolves the unix epoch`() {
        val (y, m, d) = civilFromDays(0)
        assertEquals(1970, y)
        assertEquals(1, m)
        assertEquals(1, d)
    }

    @Test
    fun `civil calendar resolves a modern date`() {
        val (y, m, d) = civilFromDays(20741)
        assertEquals(2026, y)
        assertEquals(10, m)
        assertEquals(15, d)
    }

    @Test
    fun `civil calendar resolves a pre-epoch date`() {
        val (y, m, d) = civilFromDays(-1)
        assertEquals(1969, y)
        assertEquals(12, m)
        assertEquals(31, d)
    }

    @Test
    fun `day labels use today, yesterday then date`() {
        assertEquals("Today", dayLabel(20741, 20741))
        assertEquals("Yesterday", dayLabel(20740, 20741))
        assertEquals("7 Oct", dayLabel(20733, 20741))
        // 20741 - 365 = 2025-10-15, so the year has to be shown.
        assertEquals("15 Oct 2025", dayLabel(20376, 20741))
    }

    // ─────────────────────────── chat-list timestamps ───────────────────────────

    @Test
    fun `chat time shows the clock for today`() {
        val now = day2 + 14 * 3600 + 5 * 60
        assertEquals("14:05", chatTimeLabel(now, 0, now))
    }

    @Test
    fun `chat time shows the weekday within a week`() {
        val now = day2 + 3600
        // 2026-10-14 is a Wednesday.
        assertEquals("Wed", chatTimeLabel(now - day, 0, now))
    }

    @Test
    fun `chat time falls back to a short date`() {
        val now = day2 + 3600
        assertEquals("7/10/26", chatTimeLabel(now - 8 * day, 0, now))
    }

    @Test
    fun `chat time is empty without a date and shifts with the timezone`() {
        assertEquals("", chatTimeLabel(0, 0, day2))
        // 23:30 UTC is 05:00 the next day in IST.
        val lateUtc = day2 + 23 * 3600 + 30 * 60
        assertEquals("5:00", chatTimeLabel(lateUtc, 19_800, lateUtc))
    }

    // ─────────────────────────── conversation structure ───────────────────────────

    @Test
    fun `empty history produces no rows`() {
        assertTrue(rows(emptyList()).isEmpty())
    }

    @Test
    fun `date separator is emitted after its day so it renders above it`() {
        val out = ids(rows(listOf(msg(5), msg(4), msg(3), msg(2), msg(1))))
        // reverseLayout renders index 0 at the bottom: header ends up visually on top.
        assertEquals(listOf("m5", "m4", "m3", "m2", "m1", "day"), out)
    }

    @Test
    fun `two days get one separator each in the right visual order`() {
        val out = ids(
            rows(
                listOf(
                    msg(20, date = day2 + 7200),
                    msg(10, date = day1 + 7200),
                ),
            ),
        )
        assertEquals(listOf("m20", "day", "m10", "day"), out)
    }

    @Test
    fun `unread divider sits between the read and unread messages`() {
        val out = ids(rows(listOf(msg(5), msg(4), msg(3), msg(2), msg(1)), lastRead = 3L))
        assertEquals(listOf("m5", "m4", "unread", "m3", "m2", "m1", "day"), out)
    }

    @Test
    fun `all-unread history puts the divider above the oldest message`() {
        val out = ids(rows(listOf(msg(5), msg(4)), lastRead = 1L))
        assertEquals(listOf("m5", "m4", "unread", "day"), out)
    }

    @Test
    fun `no divider when nothing is unread`() {
        val out = ids(rows(listOf(msg(5), msg(4)), lastRead = 9L))
        assertEquals(listOf("m5", "m4", "day"), out)
    }

    @Test
    fun `outgoing messages never count as unread`() {
        val out = ids(
            rows(
                listOf(msg(5, outgoing = true), msg(4, outgoing = true), msg(3)),
                lastRead = 3L,
            ),
        )
        assertEquals(listOf("m5", "m4", "m3", "day"), out)
    }

    @Test
    fun `adjacent album messages group into one row in reading order`() {
        val out = ids(
            rows(
                listOf(
                    msg(30),
                    msg(21, album = 99L, kind = MsgKind.PHOTO),
                    msg(20, album = 99L, kind = MsgKind.PHOTO),
                    msg(10),
                ),
            ),
        )
        assertEquals(listOf("m30", "album(20,21)", "m10", "day"), out)
    }

    @Test
    fun `different albums are not merged`() {
        val out = ids(
            rows(
                listOf(
                    msg(21, album = 2L, kind = MsgKind.PHOTO),
                    msg(20, album = 1L, kind = MsgKind.PHOTO),
                ),
            ),
        )
        assertEquals(listOf("album(21)", "album(20)", "day"), out)
    }

    @Test
    fun `stable keys are unique across days, dividers, messages and albums`() {
        val built = rows(
            listOf(
                msg(5), msg(4), msg(3),
                msg(21, album = 9L, kind = MsgKind.PHOTO), msg(20, album = 9L, kind = MsgKind.PHOTO),
                msg(2, date = day1 + 60), msg(1, date = day1 + 30),
            ),
            lastRead = 3L,
        )
        val keys = built.map { it.stableKey }
        assertEquals(keys.size, keys.distinct().size)
    }

    // ─────────────────────────── previews & ticks ───────────────────────────

    @Test
    fun `preview shows plain text as-is`() {
        assertEquals("hello there", previewOf(msg(1, text = "hello there")))
    }

    @Test
    fun `preview only keeps the first line of a multi-line message`() {
        assertEquals("first", previewOf(msg(1, text = "first\nsecond")))
    }

    @Test
    fun `preview labels media and combines it with a caption`() {
        assertEquals("Photo", previewOf(msg(1, kind = MsgKind.PHOTO)))
        assertEquals("Photo · beach", previewOf(msg(1, kind = MsgKind.PHOTO, caption = "beach")))
        assertEquals("Voice message", previewOf(msg(1, kind = MsgKind.VOICE)))
        assertEquals("GIF", previewOf(msg(1, kind = MsgKind.ANIMATION)))
        assertEquals("report.pdf", previewOf(msg(1, kind = MsgKind.DOCUMENT, fileName = "report.pdf")))
    }

    @Test
    fun `preview degrades instead of inventing content`() {
        assertEquals("No messages yet", previewOf(null))
        assertEquals("Message", previewOf(msg(1)))
        assertEquals("Unsupported message", previewOf(msg(1, kind = MsgKind.UNSUPPORTED)))
    }

    @Test
    fun `ticks report none, sent and read`() {
        assertEquals(0, ticksFor(0L, 0L, true))
        assertEquals(0, ticksFor(100L, 100L, false))
        assertEquals(1, ticksFor(100L, 90L, true))
        assertEquals(2, ticksFor(100L, 100L, true))
        assertEquals(2, ticksFor(100L, 150L, true))
    }

    @Test
    fun `message key is chat scoped so ids from different chats never collide`() {
        assertEquals("7:5", msg(5).key)
        assertEquals("7:5", msg(5, text = "ignored").key)
    }

    @Test
    fun `body prefers the caption over the text field`() {
        assertEquals("cap", msg(1, text = "t", caption = "cap").body)
        assertEquals("t", msg(1, text = "t").body)
    }
}
