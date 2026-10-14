package com.airbooks.app.tg

import android.content.Context
import android.os.Build
import com.airbooks.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdException(val code: Int, message: String) : Exception(message)

/**
 * Thin wrapper around TDLib (Telegram's official client library).
 * Everything — login, chat list, file listing, streaming — talks to Telegram directly
 * from the phone over MTProto. There is no backend.
 */
object Tg {
    private lateinit var appCtx: Context
    @Volatile private lateinit var client: Client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    val authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val connection = MutableStateFlow("")
    val chats = MutableStateFlow<List<ChatRow>>(emptyList())

    private var meId = 0L
    private val chatMap = ConcurrentHashMap<Long, TdApi.Chat>()
    private val fileMap = ConcurrentHashMap<Int, TdApi.File>()
    private val _fileEvents = MutableSharedFlow<TdApi.File>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val fileEvents: SharedFlow<TdApi.File> = _fileEvents.asSharedFlow()

    val filesDir: File get() = File(appCtx.cacheDir, "tdfiles")

    // ───────────────────────────── lifecycle ─────────────────────────────

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        if (BuildConfig.TG_API_ID == 0) return
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        scope.launch {
            for (u in dirty) {
                delay(150)
                publishChats()
            }
        }
        createClient()
    }

    private fun createClient() {
        client = Client.create({ obj -> onUpdate(obj) }, null, null)
    }

    // ───────────────────────────── requests ─────────────────────────────

    suspend fun <R : TdApi.Object> send(f: TdApi.Function<R>): R =
        suspendCancellableCoroutine { cont ->
            client.send(f) { r ->
                if (r is TdApi.Error) {
                    cont.resumeWithException(TdException(r.code, r.message))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(r as R)
                }
            }
        }

    fun <R : TdApi.Object> sendBlocking(f: TdApi.Function<R>): R = runBlocking { send(f) }

    // ───────────────────────────── auth ─────────────────────────────

    suspend fun submitPhone(phone: String) { send(TdApi.SetAuthenticationPhoneNumber(phone, null)) }
    suspend fun submitCode(code: String) { send(TdApi.CheckAuthenticationCode(code)) }
    suspend fun submitPassword(pw: String) { send(TdApi.CheckAuthenticationPassword(pw)) }
    fun startOver() { client.send(TdApi.Destroy()) {} }
    fun logout() { client.send(TdApi.LogOut()) {} }

    private fun onAuth(s: TdApi.AuthorizationState) {
        authState.value = s
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val p = TdApi.SetTdlibParameters()
                p.useTestDc = false
                p.databaseDirectory = File(appCtx.filesDir, "tdlib").absolutePath
                p.filesDirectory = filesDir.absolutePath
                p.databaseEncryptionKey = ByteArray(0)
                p.useFileDatabase = true
                p.useChatInfoDatabase = true
                p.useMessageDatabase = true
                p.useSecretChats = false
                p.apiId = BuildConfig.TG_API_ID
                p.apiHash = BuildConfig.TG_API_HASH
                p.systemLanguageCode = "en"
                p.deviceModel = Build.MODEL
                p.systemVersion = Build.VERSION.RELEASE
                p.applicationVersion = BuildConfig.VERSION_NAME
                client.send(p) {}
            }
            is TdApi.AuthorizationStateReady -> onReady()
            is TdApi.AuthorizationStateClosed -> {
                chatMap.clear(); fileMap.clear(); chats.value = emptyList()
                scope.launch { delay(300); createClient() }
            }
            else -> {}
        }
    }

    private fun onReady() {
        scope.launch {
            try { meId = send(TdApi.GetMe()).id } catch (_: Exception) {}
            var fails = 0
            while (fails < 5) {
                try {
                    send(TdApi.LoadChats(TdApi.ChatListMain(), 100))
                } catch (e: TdException) {
                    if (e.code == 404) break // all chats loaded
                    fails++
                    delay(2000)
                }
            }
        }
    }

    // ───────────────────────────── updates ─────────────────────────────

    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> onAuth(o.authorizationState)
            is TdApi.UpdateConnectionState -> {
                connection.value = when (o.state) {
                    is TdApi.ConnectionStateWaitingForNetwork -> "Waiting for network…"
                    is TdApi.ConnectionStateConnectingToProxy -> "Connecting to proxy…"
                    is TdApi.ConnectionStateConnecting -> "Connecting…"
                    is TdApi.ConnectionStateUpdating -> "Updating…"
                    else -> ""
                }
            }
            is TdApi.UpdateNewChat -> {
                chatMap[o.chat.id] = o.chat
                dirty.trySend(Unit)
            }
            is TdApi.UpdateChatTitle -> {
                chatMap[o.chatId]?.title = o.title
                dirty.trySend(Unit)
            }
            is TdApi.UpdateChatPhoto -> {
                chatMap[o.chatId]?.photo = o.photo
                dirty.trySend(Unit)
            }
            is TdApi.UpdateChatLastMessage -> {
                chatMap[o.chatId]?.let { it.lastMessage = o.lastMessage; it.positions = o.positions }
                dirty.trySend(Unit)
            }
            is TdApi.UpdateChatPosition -> {
                val c = chatMap[o.chatId]
                if (c != null && o.position.list is TdApi.ChatListMain) {
                    val rest = c.positions.filter { it.list !is TdApi.ChatListMain }.toMutableList()
                    if (o.position.order != 0L) rest.add(o.position)
                    c.positions = rest.toTypedArray()
                    dirty.trySend(Unit)
                }
            }
            is TdApi.UpdateFile -> {
                fileMap[o.file.id] = o.file
                _fileEvents.tryEmit(o.file)
            }
            else -> {}
        }
    }

    private fun publishChats() {
        val rows = chatMap.values.mapNotNull { c ->
            val pos = c.positions.firstOrNull { it.list is TdApi.ChatListMain } ?: return@mapNotNull null
            if (pos.order == 0L) return@mapNotNull null
            seen(c.photo?.small)
            val t = c.type
            val self = t is TdApi.ChatTypePrivate && t.userId == meId
            val sub = when (t) {
                is TdApi.ChatTypePrivate -> if (self) "Saved messages" else "Private chat"
                is TdApi.ChatTypeBasicGroup -> "Group"
                is TdApi.ChatTypeSupergroup -> if (t.isChannel) "Channel" else "Group"
                else -> ""
            }
            ChatRow(c.id, if (self) "Saved Messages" else c.title, sub, c.photo?.small?.id ?: 0, pos.order)
        }.sortedByDescending { it.order }
        chats.value = rows
    }

    // ───────────────────────────── files: metadata ─────────────────────────────

    private fun seen(f: TdApi.File?): Int {
        if (f == null) return 0
        fileMap[f.id] = f
        return f.id
    }

    suspend fun getFile(id: Int): TdApi.File =
        fileMap[id] ?: send(TdApi.GetFile(id)).also { fileMap[id] = it }

    suspend fun getFileFresh(id: Int): TdApi.File =
        send(TdApi.GetFile(id)).also { fileMap[id] = it }

    fun cachedFile(id: Int): TdApi.File =
        fileMap[id] ?: sendBlocking(TdApi.GetFile(id)).also { fileMap[id] = it }

    fun completedPath(id: Int): String? =
        fileMap[id]?.local?.takeIf { it.isDownloadingCompleted && it.path.isNotEmpty() }?.path

    fun dropFileCache() = fileMap.clear()

    // ───────────────────────────── files: downloading ─────────────────────────────

    /**
     * Ask TDLib to fetch [limit] bytes starting at [offset] (limit 0 = to the end).
     * Progress is delivered through UpdateFile events. Priority 1..32 (32 = most urgent).
     */
    fun download(id: Int, priority: Int = 1, offset: Long = 0, limit: Long = 0) {
        client.send(TdApi.DownloadFile(id, priority, offset, limit, false)) { r ->
            if (r is TdApi.File) fileMap[r.id] = r
        }
    }

    fun cancelDownload(id: Int) {
        client.send(TdApi.CancelDownloadFile(id, false)) {}
    }

    // ── streaming sessions: reference-counted so concurrent readers of the same file
    //    never cancel each other's window. Only the last reader stops the download.
    private val streamUsers = ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()

    fun beginStream(id: Int) {
        streamUsers.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
    }

    fun endStream(id: Int) {
        val n = streamUsers[id]?.decrementAndGet() ?: 0
        if (n <= 0) {
            streamUsers.remove(id)
            cancelDownload(id)
        }
    }

    fun isStreaming(id: Int): Boolean = (streamUsers[id]?.get() ?: 0) > 0

    /** True on mobile/metered data (best effort; false if it cannot be determined). */
    fun isMetered(): Boolean = try {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.isActiveNetworkMetered
    } catch (_: Exception) {
        false
    }

    /** Force a fresh GetFile (recovers from expired/invalid remote file references). */
    fun refreshFileBlocking(id: Int): TdApi.File = sendBlocking(TdApi.GetFile(id)).also { fileMap[id] = it }

    fun cacheBytes(): Long = try {
        filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) {
        0L
    }

    /**
     * Deletes fully-downloaded Telegram files that are not currently streaming, via TDLib's own
     * DeleteFile (safe: TDLib keeps its database consistent, unlike deleting files on disk).
     * Returns how many files were deleted.
     */
    fun clearStreamingCache(): Int {
        var n = 0
        for ((id, f) in fileMap) {
            if (isStreaming(id)) continue
            if (f.local.isDownloadingCompleted || f.local.downloadedSize > 0) {
                client.send(TdApi.DeleteFile(id)) {}
                n++
            }
        }
        return n
    }

    /** Best-effort enforcement of the user's temporary-cache cap: oldest known files first. */
    fun enforceCacheLimit(maxBytes: Long) {
        if (maxBytes <= 0) return
        var over = cacheBytes() - maxBytes
        if (over <= 0) return
        val candidates = fileMap.entries
            .filter { (id, f) -> !isStreaming(id) && (f.local.isDownloadingCompleted || f.local.downloadedSize > 0) }
            .sortedBy { it.value.local.downloadedSize }
        for ((id, f) in candidates) {
            if (over <= 0) break
            client.send(TdApi.DeleteFile(id)) {}
            over -= f.local.downloadedSize
        }
    }

    /** Downloads a whole (small) file and returns its local path. Used for thumbnails, images, PDFs. */
    suspend fun awaitDownload(id: Int, priority: Int = 1, onProgress: ((Float) -> Unit)? = null): String {
        var f = getFileFresh(id)
        if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) return f.local.path
        download(id, priority)
        var lastKick = System.currentTimeMillis()
        while (true) {
            delay(250)
            f = fileMap[id] ?: f
            val l = f.local
            if (l.isDownloadingCompleted && l.path.isNotEmpty()) {
                onProgress?.invoke(1f)
                return l.path
            }
            val total = maxOf(f.size, f.expectedSize).toFloat()
            if (total > 0f) onProgress?.invoke((l.downloadedSize / total).coerceIn(0f, 1f))
            val now = System.currentTimeMillis()
            if (!l.isDownloadingActive && now - lastKick > 6000) {
                download(id, priority)
                lastKick = now
            }
        }
    }

    // ───────────────────────────── files: listing ─────────────────────────────

    suspend fun searchMedia(chatId: Long, section: Section, query: String, from: Long): Page {
        val filter: TdApi.SearchMessagesFilter = when (section) {
            Section.VIDEOS -> TdApi.SearchMessagesFilterVideo()
            Section.AUDIO -> TdApi.SearchMessagesFilterAudio()
            Section.DOCS -> TdApi.SearchMessagesFilterDocument()
            Section.PHOTOS -> TdApi.SearchMessagesFilterPhoto()
        }
        val req = TdApi.SearchChatMessages()
        req.chatId = chatId
        req.query = query
        req.fromMessageId = from
        req.offset = 0
        req.limit = 40
        req.filter = filter
        val r = send(req)
        val items = r.messages.mapNotNull { toMedia(it) }
        return Page(items, if (r.messages.isEmpty()) 0L else r.nextFromMessageId)
    }

    private fun toMedia(m: TdApi.Message): Media? {
        return when (val c = m.content) {
            is TdApi.MessageVideo -> {
                val v = c.video
                Media(
                    m.chatId, m.id,
                    kindFor(v.fileName, v.mimeType, Kind.VIDEO),
                    nameOf(v.fileName, c.caption.text, "Video", m.date), v.mimeType,
                    maxOf(v.video.size, v.video.expectedSize), v.duration,
                    seen(v.video), v.video.id, seen(v.thumbnail?.file),
                    v.minithumbnail?.data, m.date, c.caption.text
                )
            }
            is TdApi.MessageAudio -> {
                val a = c.audio
                val label = listOf(a.performer, a.title).filter { it.isNotBlank() }.joinToString(" – ")
                Media(
                    m.chatId, m.id,
                    kindFor(a.fileName, a.mimeType, Kind.AUDIO),
                    label.ifBlank { nameOf(a.fileName, c.caption.text, "Audio", m.date) }, a.mimeType,
                    maxOf(a.audio.size, a.audio.expectedSize), a.duration,
                    seen(a.audio), a.audio.id, seen(a.albumCoverThumbnail?.file),
                    a.albumCoverMinithumbnail?.data, m.date, c.caption.text
                )
            }
            is TdApi.MessageDocument -> {
                val d = c.document
                Media(
                    m.chatId, m.id,
                    kindFor(d.fileName, d.mimeType, Kind.OTHER),
                    nameOf(d.fileName, c.caption.text, "File", m.date), d.mimeType,
                    maxOf(d.document.size, d.document.expectedSize), 0,
                    seen(d.document), d.document.id, seen(d.thumbnail?.file),
                    d.minithumbnail?.data, m.date, c.caption.text
                )
            }
            is TdApi.MessagePhoto -> {
                val sizes = c.photo.sizes.sortedBy { maxOf(it.width, it.height) }
                if (sizes.isEmpty()) return null
                val thumb = sizes.firstOrNull { maxOf(it.width, it.height) >= 300 } ?: sizes.last()
                val main = sizes.lastOrNull { maxOf(it.width, it.height) <= 1280 } ?: sizes.first()
                val hd = sizes.last()
                Media(
                    m.chatId, m.id, Kind.IMAGE,
                    nameOf("", c.caption.text, "Photo", m.date), "image/jpeg",
                    maxOf(main.photo.size, main.photo.expectedSize), 0,
                    seen(main.photo), seen(hd.photo), seen(thumb.photo),
                    c.photo.minithumbnail?.data, m.date, c.caption.text
                )
            }
            else -> null
        }
    }

    private fun nameOf(name: String, caption: String, fallback: String, date: Int): String =
        name.ifBlank { caption.lineSequence().firstOrNull().orEmpty().take(80) }
            .ifBlank { "$fallback ${java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(date * 1000L))}" }

    fun kindFor(name: String, mime: String, fallback: Kind): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || mime == "application/pdf" -> Kind.PDF
            mime.startsWith("video/") || ext in VIDEO_EXT -> Kind.VIDEO
            mime.startsWith("audio/") || ext in AUDIO_EXT -> Kind.AUDIO
            mime.startsWith("image/") || ext in IMAGE_EXT -> Kind.IMAGE
            else -> fallback
        }
    }

    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "ts", "3gp")
    private val AUDIO_EXT = setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")
}
