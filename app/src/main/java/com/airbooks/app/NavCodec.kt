package com.airbooks.app

import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Media

/**
 * String codec for the navigation state, so the destination and any pushed screens survive
 * rotation and process recreation.
 *
 * Deliberately pure Kotlin (no Android, no TdApi) — the codec is covered by JVM unit tests.
 * Everything is flattened into one delimited string: [Media] is always exactly [MEDIA_FIELDS]
 * fields in a fixed order, and lists are stored as a count followed by that many groups, which
 * keeps parsing positional and unambiguous.
 */
internal object NavCodec {
    private const val F = '\u0001' // field separator
    private const val S = '\u0003' // screen separator

    /**
     * Binder transactions are limited to ~1 MB; a media queue from a huge folder could otherwise
     * exceed it. When the payload is too big it is re-encoded with single-item queues, so the
     * screen that was open still restores exactly — only its next/previous queue is shortened.
     */
    private const val LIMIT = 90_000
    private const val MEDIA_FIELDS = 15

    fun encode(dest: Int, stack: List<Screen>): String {
        val full = build(dest, stack, trim = false)
        return if (full.length <= LIMIT) full else build(dest, stack, trim = true)
    }

    /** @return destination index and the restored stack; malformed input degrades to defaults. */
    fun decode(s: String): Pair<Int, List<Screen>> {
        val segments = s.split(S)
        val dest = segments.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(0, 4) ?: 0
        val out = ArrayList<Screen>()
        for (i in 1 until segments.size) {
            val f = segments[i].split(F)
            val screen = decodeScreen(f) ?: continue
            out.add(screen)
        }
        return dest to out
    }

    // ─────────────────────────── encode ───────────────────────────

    private fun build(dest: Int, stack: List<Screen>, trim: Boolean): String {
        val sb = StringBuilder()
        sb.append(dest)
        for (s in stack) {
            sb.append(S)
            when (s) {
                Screen.Downloads -> sb.append("downloads")
                Screen.Settings -> sb.append("settings")
                is Screen.Conversation -> sb.append(tag("conv", s.chatId, esc(s.title)))
                is Screen.Files -> sb.append(tag("files", s.chatId, esc(s.title)))
                is Screen.Pdf -> sb.append("pdf").append(F).append(media(s.item))
                is Screen.Other -> sb.append("other").append(F).append(media(s.item))
                is Screen.Play -> {
                    sb.append("play").append(F).append(media(s.item))
                        .append(F).append(esc(s.chatTitle))
                    val q = if (trim) listOf(s.item) else s.queue
                    appendList(sb, q)
                }
                is Screen.Image -> {
                    sb.append("image").append(F).append(media(s.start))
                    val l = if (trim) listOf(s.start) else s.items
                    appendList(sb, l)
                }
            }
        }
        return sb.toString()
    }

    private fun tag(name: String, vararg parts: Any?): String =
        listOf(name, *parts).joinToString(F.toString())

    private fun appendList(sb: StringBuilder, items: List<Media>) {
        sb.append(F).append(items.size)
        items.forEach { sb.append(F).append(media(it)) }
    }

    private fun media(m: Media): String = listOf(
        m.chatId, m.msgId, m.kind.name, esc(m.name), esc(m.mime), m.size, m.duration,
        m.fileId, m.hdFileId, m.thumbId, m.date, esc(m.caption), esc(m.performer),
        esc(m.trackTitle), esc(m.fileName),
    ).joinToString(F.toString())

    /** Control characters used as separators are stripped so fields can never split themselves. */
    private fun esc(s: String): String = s.replace(F, ' ').replace(S, ' ').replace('\u0002', ' ')

    // ─────────────────────────── decode ───────────────────────────

    private fun decodeScreen(f: List<String>): Screen? = when (f.getOrNull(0)) {
        "downloads" -> Screen.Downloads
        "settings" -> Screen.Settings
        "conv" -> Screen.Conversation(f.long(1), f.str(2))
        "files" -> Screen.Files(f.long(1), f.str(2))
        "pdf" -> Screen.Pdf(f.media(1))
        "other" -> Screen.Other(f.media(1))
        "play" -> {
            val item = f.media(1)
            val queue = f.list(1 + MEDIA_FIELDS + 1)
            Screen.Play(item, queue.ifEmpty { listOf(item) }, f.str(1 + MEDIA_FIELDS))
        }
        "image" -> {
            val start = f.media(1)
            val items = f.list(1 + MEDIA_FIELDS)
            Screen.Image(items.ifEmpty { listOf(start) }, start)
        }
        else -> null
    }

    private fun List<String>.str(i: Int): String = getOrNull(i).orEmpty()
    private fun List<String>.long(i: Int): Long = getOrNull(i)?.toLongOrNull() ?: 0L
    private fun List<String>.int(i: Int): Int = getOrNull(i)?.toIntOrNull() ?: 0

    private fun List<String>.media(at: Int): Media = Media(
        chatId = long(at),
        msgId = long(at + 1),
        kind = runCatching { Kind.valueOf(str(at + 2)) }.getOrDefault(Kind.OTHER),
        name = str(at + 3),
        mime = str(at + 4),
        size = long(at + 5),
        duration = int(at + 6),
        fileId = int(at + 7),
        hdFileId = int(at + 8),
        thumbId = int(at + 9),
        date = int(at + 10),
        caption = str(at + 11),
        performer = str(at + 12),
        trackTitle = str(at + 13),
        fileName = str(at + 14),
        // The ~300-byte inline preview is not worth persisting; it is re-fetched by file id.
        mini = null,
    )

    /** Reads a count at [countAt] followed by that many [MEDIA_FIELDS]-wide groups. */
    private fun List<String>.list(countAt: Int): List<Media> {
        val n = int(countAt).coerceIn(0, 5_000)
        val out = ArrayList<Media>(n)
        for (k in 0 until n) {
            val at = countAt + 1 + k * MEDIA_FIELDS
            if (at + MEDIA_FIELDS > size) break
            out.add(media(at))
        }
        return out
    }
}
