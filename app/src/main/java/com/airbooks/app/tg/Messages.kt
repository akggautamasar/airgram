package com.airbooks.app.tg

/**
 * Pure chat-message model and list-structuring helpers.
 *
 * No TdApi and no Android types in the helpers below, so the parts that decide what the user
 * sees (album grouping, date separators, unread divider, chat-list previews) are unit-testable
 * on the JVM. Mapping from TDLib objects lives in [MsgMapper].
 */

enum class MsgKind {
    TEXT, PHOTO, VIDEO, AUDIO, VOICE, VIDEO_NOTE, DOCUMENT, ANIMATION, STICKER,
    LOCATION, VENUE, CONTACT, POLL, DICE, CALL, SERVICE, UNSUPPORTED,
}

enum class SendState { SENT, PENDING, FAILED }

data class Reaction(val emoji: String, val count: Int, val chosen: Boolean)

data class PollOpt(val text: String, val voters: Int, val pct: Int, val chosen: Boolean)

data class LinkCard(val url: String, val site: String, val title: String, val desc: String)

data class ReplyRef(val msgId: Long, val sender: String, val preview: String)

data class Msg(
    val id: Long,
    val chatId: Long,
    val date: Int,
    val editDate: Int = 0,
    val outgoing: Boolean = false,
    val senderId: Long = 0L,
    val senderName: String = "",
    val kind: MsgKind = MsgKind.TEXT,
    val text: String = "",
    val caption: String = "",
    val albumId: Long = 0L,
    // Media payload (file ids are TDLib ids; 0 = none).
    val fileId: Int = 0,
    val hdFileId: Int = 0,
    val thumbId: Int = 0,
    val mini: ByteArray? = null,
    val fileName: String = "",
    val mime: String = "",
    val size: Long = 0L,
    val duration: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val reply: ReplyRef? = null,
    val forwardFrom: String = "",
    val reactions: List<Reaction> = emptyList(),
    val state: SendState = SendState.SENT,
    val link: LinkCard? = null,
    val stickerEmoji: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val placeName: String = "",
    val address: String = "",
    val contactName: String = "",
    val contactPhone: String = "",
    val pollQuestion: String = "",
    val pollOptions: List<PollOpt> = emptyList(),
    val pollVoters: Int = 0,
    val pollClosed: Boolean = false,
    val pollIsQuiz: Boolean = false,
    val diceEmoji: String = "",
    val diceValue: Int = 0,
    val serviceText: String = "",
    val viewed: Boolean = false,
    val views: Int = 0,
) {
    val key: String get() = "$chatId:$id"
    val edited: Boolean get() = editDate > 0
    val hasMedia: Boolean get() = fileId != 0 || hdFileId != 0
    val body: String get() = caption.ifBlank { text }
}

/** Rows the conversation list renders, newest first. */
sealed interface ChatItem {
    val stableKey: String
}

data class DateHeader(val bucket: Int, val label: String) : ChatItem {
    override val stableKey: String get() = "day:$bucket"
}

data object UnreadHeader : ChatItem {
    override val stableKey: String get() = "unread-divider"
}

data class Single(val msg: Msg) : ChatItem {
    override val stableKey: String get() = "m:${msg.id}"
}

/** A Telegram media album (shared `mediaAlbumId`) rendered as one grid bubble. */
data class Album(val msgs: List<Msg>) : ChatItem {
    override val stableKey: String get() = "a:${msgs.first().id}"
    val head: Msg get() = msgs.first()
}

/** Day bucket for a timestamp at a given UTC offset — pure, so separators are testable. */
fun dayBucket(unixSeconds: Int, tzOffsetSeconds: Int): Int =
    Math.floorDiv(unixSeconds.toLong() + tzOffsetSeconds, 86_400L).toInt()

object ChatStruct {
    /**
     * Turns a newest-first message list into render rows: date separators between days, adjacent
     * same-album messages grouped into one [Album], and a single [UnreadHeader] between the unread
     * and the read part of the history.
     *
     * Both the input and the result are **newest-first**, which is what a `reverseLayout`
     * LazyColumn consumes: index 0 renders at the bottom, so appending older pages at the end never
     * moves what the user is reading.
     *
     * Because that list is displayed bottom-up, a day's separator has to be emitted *after* that
     * day's messages to appear above them on screen. Walking the example
     * `[5, 4, 3, 2, 1]` (ids 4 and 5 unread, all same day) the result is
     * `[S5, S4, Unread, S3, S2, S1, DateHeader]`, which renders top-to-bottom as
     * date header, 1, 2, 3, unread divider, 4, 5 — Telegram's own order.
     */
    fun build(
        msgs: List<Msg>,
        tzOffsetSeconds: Int,
        lastReadInboxId: Long,
        labelFor: (Int) -> String,
    ): List<ChatItem> {
        if (msgs.isEmpty()) return emptyList()
        val hasUnread = lastReadInboxId > 0 && msgs.any { !it.outgoing && it.id > lastReadInboxId }
        var unreadPlaced = !hasUnread
        var curDay = Int.MIN_VALUE
        val out = ArrayList<ChatItem>(msgs.size + 8)
        var i = 0
        while (i < msgs.size) {
            val m = msgs[i]
            if (!unreadPlaced && m.id <= lastReadInboxId) {
                out.add(UnreadHeader)
                unreadPlaced = true
            }
            val bucket = dayBucket(m.date, tzOffsetSeconds)
            if (bucket != curDay) {
                // Close off the newer day first, so its separator sits above its own messages.
                if (curDay != Int.MIN_VALUE) out.add(DateHeader(curDay, labelFor(curDay)))
                curDay = bucket
            }
            if (m.albumId != 0L) {
                val run = ArrayList<Msg>()
                while (i < msgs.size && msgs[i].albumId == m.albumId) { run.add(msgs[i]); i++ }
                // Albums arrive newest-first; show them in natural (oldest-first) reading order.
                out.add(Album(run.asReversed()))
            } else {
                out.add(Single(m))
                i++
            }
        }
        // Everything unread: the divider belongs above the oldest message but below the day header.
        if (!unreadPlaced) out.add(UnreadHeader)
        if (curDay != Int.MIN_VALUE) out.add(DateHeader(curDay, labelFor(curDay)))
        return out
    }
}

private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * Civil date (year, month, day) for a [dayBucket] — days since 1970-01-01 — using the standard
 * proleptic-Gregorian algorithm. Pure and dependency-free, because minSdk 24 has no `java.time`.
 */
fun civilFromDays(bucket: Int): IntArray {
    val z = bucket.toLong() + 719_468
    val era = Math.floorDiv(z, 146_097)
    val doe = z - era * 146_097                                       // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365 // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)                  // [0, 365]
    val mp = (5 * doy + 2) / 153                                        // [0, 11]
    val d = doy - (153 * mp + 2) / 5 + 1                                // [1, 31]
    val m = if (mp < 10) mp + 3 else mp - 9                             // [1, 12]
    return intArrayOf((y + if (m <= 2) 1 else 0).toInt(), m.toInt(), d.toInt())
}

/** Date-separator text: "Today", "Yesterday", "12 Mar", or "12 Mar 2024" for older years. */
fun dayLabel(bucket: Int, todayBucket: Int): String {
    val (y, m, d) = civilFromDays(bucket)
    return when (todayBucket - bucket) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> {
            val label = "$d ${MONTHS[m - 1]}"
            if (y == civilFromDays(todayBucket)[0]) label else "$label $y"
        }
    }
}

private val WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

private fun clockOf(unixSeconds: Int, tzOffsetSeconds: Int): String {
    val s = unixSeconds.toLong() + tzOffsetSeconds
    val h = Math.floorMod(s / 3600, 24)
    val m = Math.floorMod(s / 60, 60)
    return "%d:%02d".format(h, m)
}

/**
 * Chat-list timestamp, Telegram style: clock time today, weekday name within the last week,
 * short date otherwise. Pure so the rule is unit-testable without a device locale.
 */
fun chatTimeLabel(unixSeconds: Int, tzOffsetSeconds: Int, nowSeconds: Int): String {
    if (unixSeconds <= 0) return ""
    val today = dayBucket(nowSeconds, tzOffsetSeconds)
    val that = dayBucket(unixSeconds, tzOffsetSeconds)
    return when (val back = today - that) {
        0 -> clockOf(unixSeconds, tzOffsetSeconds)
        in 1..6 -> WEEKDAYS[Math.floorMod(that + 4, 7)]
        else -> {
            val (y, m, d) = civilFromDays(that)
            if (back < 0) clockOf(unixSeconds, tzOffsetSeconds) else "$d/${m}/${y % 100}"
        }
    }
}

/** Chat-list one-line preview for a last message — no fabricated content, type + text only. */
fun previewOf(m: Msg?): String {
    if (m == null) return "No messages yet"
    val label = when (m.kind) {
        MsgKind.PHOTO -> "Photo"
        MsgKind.VIDEO -> "Video"
        MsgKind.AUDIO -> if (m.body.isNotBlank()) "Music" else "Audio"
        MsgKind.VOICE -> "Voice message"
        MsgKind.VIDEO_NOTE -> "Video message"
        MsgKind.DOCUMENT -> m.fileName.ifBlank { "File" }
        MsgKind.ANIMATION -> "GIF"
        MsgKind.STICKER -> "Sticker ${m.stickerEmoji}".trim()
        MsgKind.LOCATION -> "Location"
        MsgKind.VENUE -> m.placeName.ifBlank { "Venue" }
        MsgKind.CONTACT -> "Contact"
        MsgKind.POLL -> m.pollQuestion.ifBlank { "Poll" }
        MsgKind.DICE -> "${m.diceEmoji} ${m.diceValue}".trim()
        MsgKind.CALL -> "Call"
        MsgKind.SERVICE -> m.serviceText
        MsgKind.UNSUPPORTED -> "Unsupported message"
        MsgKind.TEXT -> ""
    }
    val text = m.body.lineSequence().firstOrNull().orEmpty()
    return when {
        label.isBlank() -> text.ifBlank { "Message" }
        text.isBlank() -> label
        else -> "$label · ${text.take(60)}"
    }
}

/** Delivery/read ticks for an outgoing last message: 0 none, 1 sent, 2 read. */
fun ticksFor(lastMsgId: Long, lastReadOutboxId: Long, outgoing: Boolean): Int = when {
    !outgoing || lastMsgId <= 0L -> 0
    lastMsgId <= lastReadOutboxId -> 2
    else -> 1
}

/** Converts a message into the existing [Media] model so the player/downloader are reused. */
fun Msg.toMedia(): Media? {
    val kind = when (this.kind) {
        MsgKind.VIDEO, MsgKind.ANIMATION -> Kind.VIDEO
        MsgKind.VIDEO_NOTE -> Kind.VIDEO
        MsgKind.AUDIO, MsgKind.VOICE -> Kind.AUDIO
        MsgKind.PHOTO -> Kind.IMAGE
        MsgKind.DOCUMENT -> Tg.kindFor(fileName, mime, Kind.OTHER)
        else -> return null
    }
    if (fileId == 0 && hdFileId == 0) return null
    val label = when {
        fileName.isNotBlank() -> fileName
        kind == Kind.AUDIO && body.isBlank() -> "Audio ${date}"
        body.isNotBlank() -> body.lineSequence().first().take(80)
        else -> "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} ${date}"
    }
    return Media(
        chatId = chatId,
        msgId = id,
        kind = kind,
        name = label,
        mime = mime,
        size = size,
        duration = duration,
        fileId = if (fileId != 0) fileId else hdFileId,
        hdFileId = if (hdFileId != 0) hdFileId else fileId,
        thumbId = thumbId,
        mini = mini,
        date = date,
        caption = caption,
    )
}
