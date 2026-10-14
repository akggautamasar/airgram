package com.airbooks.app.tg

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Kind { VIDEO, AUDIO, PDF, IMAGE, OTHER }

enum class Section(val label: String) {
    VIDEOS("Videos"), AUDIO("Audio"), DOCS("Docs"), PHOTOS("Photos")
}

data class ChatRow(
    val id: Long,
    val title: String,
    val subtitle: String,
    val photoId: Int,
    val order: Long,
    val unread: Int = 0,
)

data class Media(
    val chatId: Long,
    val msgId: Long,
    val kind: Kind,
    val name: String,
    val mime: String,
    val size: Long,
    val duration: Int,
    val fileId: Int,     // file used for streaming / viewing
    val hdFileId: Int,   // full-quality variant (photos), else same as fileId
    val thumbId: Int,    // small preview file (0 = none)
    val mini: ByteArray?, // tiny inline JPEG that ships with the message (~300 bytes)
    val date: Int,
    val caption: String,
    val performer: String = "",
    val trackTitle: String = "",
    val fileName: String = "",
) {
    val key: String get() = "$chatId:$msgId"
}

data class Page(val items: List<Media>, val next: Long)

class ListState {
    val items = mutableStateListOf<Media>()
    var next: Long = -1L
    var done by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun reset() {
        items.clear(); next = -1L; done = false; error = null
    }
}

/** In-memory cache of file lists so going back to a chat is instant. */
object Repo {
    private val map = HashMap<String, ListState>()

    fun state(chatId: Long, s: Section, q: String): ListState =
        map.getOrPut("$chatId|${s.name}|$q") { ListState() }

    suspend fun loadMore(chatId: Long, s: Section, q: String, st: ListState) {
        if (st.loading || st.done) return
        st.loading = true
        st.error = null
        try {
            val p = Tg.searchMedia(chatId, s, q, if (st.next < 0) 0L else st.next)
            val known = st.items.mapTo(HashSet()) { it.msgId }
            st.items.addAll(p.items.filter { it.msgId !in known })
            if (p.next == 0L) st.done = true else st.next = p.next
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            st.error = e.message ?: "Failed to load"
        } finally {
            st.loading = false
        }
    }
}
