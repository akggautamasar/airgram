package com.airbooks.app.home

import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Media

/**
 * "Continue watching/listening" rail: a bounded, persistence-friendly record of recently played
 * media with resume positions. Encode/decode are pure (TSV) so they are unit-tested; the Android
 * side only reads/writes the string.
 */
object Recent {
    const val MAX_ENTRIES = 12

    data class Entry(
        val chatId: Long,
        val msgId: Long,
        val fileId: Int,
        val kind: Kind,
        val name: String,
        val size: Long,
        val positionMs: Long,
        val atMs: Long,
    ) {
        val key: String get() = "$chatId:$msgId"
    }

    /** Insert/move-to-front with a position update; capped at [MAX_ENTRIES]. */
    fun note(entries: List<Entry>, e: Entry): List<Entry> =
        (listOf(e) + entries.filterNot { it.key == e.key }).take(MAX_ENTRIES)

    fun encode(entries: List<Entry>): String =
        entries.joinToString("\n") {
            listOf(it.chatId, it.msgId, it.fileId, it.kind.name, it.name, it.size, it.positionMs, it.atMs)
                .joinToString("\t")
        }

    fun decode(s: String): List<Entry> =
        s.lineSequence().mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 8) return@mapNotNull null
            Entry(
                chatId = p[0].toLongOrNull() ?: return@mapNotNull null,
                msgId = p[1].toLongOrNull() ?: return@mapNotNull null,
                fileId = p[2].toIntOrNull() ?: return@mapNotNull null,
                kind = runCatching { Kind.valueOf(p[3]) }.getOrNull() ?: return@mapNotNull null,
                name = p[4],
                size = p[5].toLongOrNull() ?: 0,
                positionMs = p[6].toLongOrNull() ?: 0,
                atMs = p[7].toLongOrNull() ?: 0,
            )
        }.toList()

    /** Reconstruct a playable Media row (thumbnails re-resolve through TDLib by fileId). */
    fun toMedia(e: Entry): Media = Media(
        chatId = e.chatId, msgId = e.msgId, kind = e.kind, name = e.name, mime = "",
        size = e.size, duration = 0, fileId = e.fileId, hdFileId = e.fileId, thumbId = 0,
        mini = null, date = (e.atMs / 1000).toInt(), caption = "",
    )
}
