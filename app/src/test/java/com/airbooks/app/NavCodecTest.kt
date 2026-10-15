package com.airbooks.app

import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation restoration: the destination and every pushed screen have to come back intact after
 * rotation or process death, and a payload that would be too large for a saved-instance-state
 * transaction has to degrade gracefully instead of crashing.
 */
class NavCodecTest {

    private fun media(
        id: Long = 1,
        name: String = "clip.mp4",
        kind: Kind = Kind.VIDEO,
    ) = Media(
        chatId = 7L,
        msgId = id,
        kind = kind,
        name = name,
        mime = "video/mp4",
        size = 1234L,
        duration = 90,
        fileId = 11,
        hdFileId = 12,
        thumbId = 13,
        mini = null,
        date = 1_700_000_000,
        caption = "caption",
        performer = "performer",
        trackTitle = "track",
        fileName = "file.mp4",
    )

    private fun roundTrip(dest: Int, stack: List<Screen>): Pair<Int, List<Screen>> =
        NavCodec.decode(NavCodec.encode(dest, stack))

    @Test
    fun `destination survives a round trip`() {
        for (d in 0..4) {
            val (dest, stack) = roundTrip(d, emptyList())
            assertEquals(d, dest)
            assertTrue(stack.isEmpty())
        }
    }

    @Test
    fun `conversation keeps chat id and title`() {
        val screen = Screen.Conversation(-1002345678901L, "Telegram Tips")
        val (dest, stack) = roundTrip(0, listOf(screen))
        assertEquals(0, dest)
        assertEquals(listOf(screen), stack)
    }

    @Test
    fun `chat file browser keeps its target`() {
        val screen = Screen.Files(42L, "Saved Messages")
        assertEquals(listOf(screen), roundTrip(1, listOf(screen)).second)
    }

    @Test
    fun `player keeps the item, its queue and the chat title`() {
        val queue = listOf(media(1), media(2, "b.mp4"), media(3, "c.mp4"))
        val screen = Screen.Play(media(2, "b.mp4"), queue, "Some chat")
        val (_, stack) = roundTrip(0, listOf(screen))
        assertEquals(1, stack.size)
        val restored = stack[0] as Screen.Play
        assertEquals(screen.item, restored.item)
        assertEquals(screen.chatTitle, restored.chatTitle)
        assertEquals(queue, restored.queue)
    }

    @Test
    fun `image viewer keeps the start image and the full list`() {
        val items = listOf(media(1, kind = Kind.IMAGE), media(2, kind = Kind.IMAGE))
        val screen = Screen.Image(items, items[1])
        val restored = roundTrip(0, listOf(screen)).second[0] as Screen.Image
        assertEquals(items[1], restored.start)
        assertEquals(items, restored.items)
    }

    @Test
    fun `pdf and other viewers keep their media`() {
        val pdf = Screen.Pdf(media(5, "doc.pdf", Kind.PDF))
        val other = Screen.Other(media(6, "arc.zip", Kind.OTHER))
        val stack = roundTrip(3, listOf(pdf, other)).second
        assertEquals(listOf(pdf, other), stack)
    }

    @Test
    fun `every media field is preserved`() {
        val m = media(9, "song.mp3", Kind.AUDIO)
        val restored = (roundTrip(0, listOf(Screen.Pdf(m))).second[0] as Screen.Pdf).item
        assertEquals(m.chatId, restored.chatId)
        assertEquals(m.msgId, restored.msgId)
        assertEquals(m.kind, restored.kind)
        assertEquals(m.name, restored.name)
        assertEquals(m.mime, restored.mime)
        assertEquals(m.size, restored.size)
        assertEquals(m.duration, restored.duration)
        assertEquals(m.fileId, restored.fileId)
        assertEquals(m.hdFileId, restored.hdFileId)
        assertEquals(m.thumbId, restored.thumbId)
        assertEquals(m.date, restored.date)
        assertEquals(m.caption, restored.caption)
        assertEquals(m.performer, restored.performer)
        assertEquals(m.trackTitle, restored.trackTitle)
        assertEquals(m.fileName, restored.fileName)
        assertEquals(m.key, restored.key)
    }

    @Test
    fun `deep stacks restore in order`() {
        val stack = listOf(
            Screen.Conversation(1L, "A"),
            Screen.Files(1L, "A"),
            Screen.Play(media(1), listOf(media(1)), ""),
            Screen.Other(media(2, "f.bin", Kind.OTHER)),
        )
        assertEquals(stack, roundTrip(2, stack).second)
    }

    @Test
    fun `an oversized queue is trimmed instead of blowing the save transaction`() {
        val huge = (1..5_000).map { media(it.toLong(), "item-$it.mp4") }
        val encoded = NavCodec.encode(0, listOf(Screen.Play(huge[0], huge, "chat")))
        assertTrue("payload should stay inside the budget: ${encoded.length}", encoded.length <= 90_000)
        val restored = NavCodec.decode(encoded).second[0] as Screen.Play
        // The open item still restores exactly; only its next/previous queue is shortened.
        assertEquals(huge[0], restored.item)
        assertEquals(listOf(huge[0]), restored.queue)
    }

    @Test
    fun `a normal queue is never trimmed`() {
        val q = (1..50).map { media(it.toLong()) }
        val restored = roundTrip(0, listOf(Screen.Play(q[0], q, ""))).second[0] as Screen.Play
        assertEquals(50, restored.queue.size)
    }

    @Test
    fun `malformed payloads degrade to the chats destination`() {
        assertEquals(0 to emptyList<Screen>(), NavCodec.decode(""))
        assertEquals(0 to emptyList<Screen>(), NavCodec.decode("not-a-number"))
        val (dest, stack) = NavCodec.decode("1\u0003nonsense\u0003alsobad")
        assertEquals(1, dest)
        assertTrue(stack.isEmpty())
    }

    @Test
    fun `destination index is clamped into range`() {
        assertEquals(4, NavCodec.decode("99").first)
        assertEquals(0, NavCodec.decode("-5").first)
    }

    @Test
    fun `truncated numeric fields fall back to defaults`() {
        // A payload cut short must not throw while restoring.
        val (dest, stack) = NavCodec.decode("0\u0003conv\u0001")
        assertEquals(0, dest)
        assertEquals(listOf(Screen.Conversation(0L, "")), stack)
    }

    @Test
    fun `separator characters inside text cannot corrupt the payload`() {
        val nasty = "bad\u0001title\u0003with\u0002separators"
        val restored = roundTrip(0, listOf(Screen.Conversation(3L, nasty))).second[0] as Screen.Conversation
        assertEquals(3L, restored.chatId)
        assertTrue(restored.title.contains("bad"))
        assertTrue(restored.title.contains("separators"))
        // The decoded stack must still be exactly one screen, not three.
        assertEquals(1, roundTrip(0, listOf(Screen.Conversation(3L, nasty))).second.size)
    }

    @Test
    fun `unknown media kinds fall back to a playable default`() {
        // The item really has to carry a PDF kind for the corruption below to hit it (the screen
        // tag itself is lower-case, and replace() is case-sensitive).
        val encoded = NavCodec.encode(0, listOf(Screen.Pdf(media(1, "doc.pdf", Kind.PDF))))
        assertTrue(encoded.contains(Kind.PDF.name))
        val corrupted = encoded.replace(Kind.PDF.name, "NOT_A_KIND")
        val restored = NavCodec.decode(corrupted).second[0] as Screen.Pdf
        assertEquals(Kind.OTHER, restored.item.kind)
    }

    @Test
    fun `a hostile queue count cannot make decoding allocate without limit`() {
        val f = "\u0001"
        val item = (1..15).joinToString(f) { "0" } // 15 placeholder media fields
        val payload = "play" + f + item + f + f + "999999"
        val (dest, stack) = NavCodec.decode("0\u0003" + payload)
        assertEquals(0, dest)
        val play = stack[0] as Screen.Play
        // The count is bounded and the missing groups are skipped, so the open item still restores.
        assertEquals(1, play.queue.size)
        assertEquals(0L, play.item.chatId)
    }
}
