package com.airbooks.app.tg

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Message state for one conversation.
 *
 * [items] is **newest-first** (TDLib's history order), which pairs with a `reverseLayout`
 * LazyColumn: index 0 renders at the bottom, and appending older pages grows the list upwards
 * without moving what the user is currently reading.
 */
class MsgList {
    val items = mutableStateListOf<Msg>()
    /** Cursor for the next older page (`fromMessageId`); 0 = start from the newest. */
    var oldestId by mutableStateOf(0L)
    var loading by mutableStateOf(false)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var total by mutableStateOf(0)

    fun reset() {
        items.clear(); oldestId = 0L; done = false; error = null; loading = false; total = 0
    }
}

/**
 * In-memory conversation cache, fed by TDLib updates. Only chats that were actually opened have
 * state here, so a long account history never costs memory until it is viewed, and closing a
 * conversation frees it ([forget]).
 */
object ChatRepo {
    private val PAGE = 40

    /**
     * Bound on how many conversations stay in memory at once. Access-ordered, so opening a new
     * chat evicts the one used least recently — a long account history never grows this map.
     */
    private const val MAX_CACHED = 6
    private val map = LinkedHashMap<Long, MsgList>(8, 0.75f, true)

    @Synchronized fun state(chatId: Long): MsgList {
        val st = map.getOrPut(chatId) { MsgList() }
        val it = map.entries.iterator()
        while (map.size > MAX_CACHED && it.hasNext()) {
            if (it.next().key != chatId) it.remove()
        }
        return st
    }

    @Synchronized fun peek(chatId: Long): MsgList? = map[chatId]

    @Synchronized fun forget(chatId: Long) {
        map.remove(chatId)
    }

    /** Loads one older page. Never loads whole history: TDLib pages from [MsgList.oldestId]. */
    suspend fun loadOlder(chatId: Long, st: MsgList) {
        if (st.loading || st.done) return
        st.loading = true
        st.error = null
        try {
            val (msgs, total) = Tg.history(chatId, st.oldestId, 0, PAGE)
            if (msgs.isEmpty()) {
                st.done = true
            } else {
                val known = st.items.mapTo(HashSet()) { it.id }
                st.items.addAll(msgs.filter { it.id !in known })
                st.oldestId = msgs.minOf { it.id }
                if (total > 0) st.total = total
                if (msgs.size < PAGE) st.done = true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            st.error = e.message ?: "Failed to load messages"
        } finally {
            st.loading = false
        }
    }

    /** A message arrived for a chat we have materialised (live update or our own send). */
    fun onNew(m: Msg) {
        val st = peek(m.chatId) ?: return
        val i = st.items.indexOfFirst { it.id == m.id }
        if (i >= 0) st.items[i] = m else st.items.add(0, m)
    }

    /**
     * No hand-made local echo: `sendMessage` makes TDLib emit the outgoing message with a
     * temporary id and PENDING state through [onNew], then [onSendSucceeded] swaps in the real id.
     * That keeps one source of truth, so a bubble can never appear twice.
     */
    fun onReplace(m: Msg) = onNew(m)

    fun onDelete(chatId: Long, ids: LongArray) {
        val st = peek(chatId) ?: return
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        st.items.removeAll { it.id in set }
    }

    fun onReactions(chatId: Long, id: Long, reactions: List<Reaction>) {
        val st = peek(chatId) ?: return
        val i = st.items.indexOfFirst { it.id == id }
        if (i >= 0) st.items[i] = st.items[i].copy(reactions = reactions)
    }

    fun onEditDate(chatId: Long, id: Long, editDate: Int) {
        val st = peek(chatId) ?: return
        val i = st.items.indexOfFirst { it.id == id }
        if (i >= 0 && st.items[i].editDate != editDate) st.items[i] = st.items[i].copy(editDate = editDate)
    }

    /** Telegram replaced our temporary id with the real message. */
    fun onSendSucceeded(oldId: Long, m: Msg) {
        val st = peek(m.chatId) ?: return
        val i = st.items.indexOfFirst { it.id == oldId || it.id == m.id }
        if (i >= 0) st.items[i] = m else st.items.add(0, m)
    }

    fun onSendFailed(oldId: Long, m: Msg) {
        val st = peek(m.chatId) ?: return
        val i = st.items.indexOfFirst { it.id == oldId || it.id == m.id }
        val failed = m.copy(state = SendState.FAILED)
        if (i >= 0) st.items[i] = failed else st.items.add(0, failed)
    }
}
