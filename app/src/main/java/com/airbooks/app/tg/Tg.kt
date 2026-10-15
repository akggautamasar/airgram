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
    /** Chats grouped by list: "main", "archive", "folder:<id>" — powers folders & archive UI. */
    val chatsByList = MutableStateFlow<Map<String, List<ChatRow>>>(emptyMap())

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
                if (c != null) {
                    val rest = c.positions.filter { p -> !sameList(p.list, o.position.list) }.toMutableList()
                    if (o.position.order != 0L) rest.add(o.position)
                    c.positions = rest.toTypedArray()
                    dirty.trySend(Unit)
                }
            }
            is TdApi.UpdateChatReadInbox -> {
                chatMap[o.chatId]?.let {
                    it.unreadCount = o.unreadCount
                    it.lastReadInboxMessageId = o.lastReadInboxMessageId
                }
                dirty.trySend(Unit)
            }
            is TdApi.UpdateChatReadOutbox -> {
                chatMap[o.chatId]?.let { it.lastReadOutboxMessageId = o.lastReadOutboxMessageId }
                dirty.trySend(Unit)
            }
            is TdApi.UpdateChatIsMarkedAsUnread -> {
                chatMap[o.chatId]?.let { it.isMarkedAsUnread = o.isMarkedAsUnread }
                dirty.trySend(Unit)
            }
            is TdApi.UpdateChatNotificationSettings -> {
                chatMap[o.chatId]?.let { it.notificationSettings = o.notificationSettings }
                dirty.trySend(Unit)
            }
            is TdApi.UpdateChatDraftMessage -> {
                chatMap[o.chatId]?.let {
                    it.draftMessage = o.draftMessage
                    it.positions = o.positions
                }
                dirty.trySend(Unit)
            }
            is TdApi.UpdateUser -> {
                userMap[o.user.id] = o.user
                presenceMap[o.user.id] = presenceText(o.user.status)
                dirty.trySend(Unit)
            }
            is TdApi.UpdateUserStatus -> {
                presenceMap[o.userId] = presenceText(o.status)
                dirty.trySend(Unit)
            }
            // ── live messages ──
            is TdApi.UpdateNewMessage -> {
                ChatRepo.onNew(MsgMapper.map(o.message, names) { f -> seen(f) })
                dirty.trySend(Unit)
            }
            is TdApi.UpdateDeleteMessages -> ChatRepo.onDelete(o.chatId, o.messageIds)
            is TdApi.UpdateMessageContent -> refetchMessage(o.chatId, o.messageId)
            is TdApi.UpdateMessageEdited -> ChatRepo.onEditDate(o.chatId, o.messageId, o.editDate)
            is TdApi.UpdateMessageReactions ->
                ChatRepo.onReactions(o.chatId, o.messageId, reactionsOf(o.reactions))
            is TdApi.UpdateMessageSendSucceeded ->
                ChatRepo.onSendSucceeded(o.oldMessageId, MsgMapper.map(o.message, names) { f -> seen(f) })
            is TdApi.UpdateMessageSendFailed ->
                ChatRepo.onSendFailed(o.oldMessageId, MsgMapper.map(o.message, names) { f -> seen(f) })
            is TdApi.UpdateFile -> {
                fileMap[o.file.id] = o.file
                _fileEvents.tryEmit(o.file)
            }
            else -> {}
        }
    }

    private fun publishChats() {
        val byList = HashMap<String, MutableList<ChatRow>>()
        for (c in chatMap.values) {
            val t = c.type
            val self = t is TdApi.ChatTypePrivate && t.userId == meId
            val peerId = (t as? TdApi.ChatTypePrivate)?.userId ?: 0L
            val kind = when (t) {
                is TdApi.ChatTypePrivate ->
                    if (self) "saved" else if (isBotUser(peerId)) "bot" else "private"
                is TdApi.ChatTypeBasicGroup -> "group"
                is TdApi.ChatTypeSupergroup -> if (t.isChannel) "channel" else "group"
                else -> "other"
            }
            // Labels the existing Chats filters match on — keep them byte-identical.
            val sub = when (t) {
                is TdApi.ChatTypePrivate -> if (self) "Saved messages" else "Private chat"
                is TdApi.ChatTypeBasicGroup -> "Group"
                is TdApi.ChatTypeSupergroup -> if (t.isChannel) "Channel" else "Group"
                else -> ""
            }
            val last = c.lastMessage
            val draft = (c.draftMessage?.content as? TdApi.DraftMessageContentText)?.text?.text.orEmpty()
            val muted = c.notificationSettings?.let { !it.useDefaultMuteFor && it.muteFor > 0 } == true
            val base = ChatRow(
                id = c.id,
                title = if (self) "Saved Messages" else c.title,
                subtitle = sub,
                photoId = seen(c.photo?.small),
                order = 0L,
                unread = c.unreadCount,
                muted = muted,
                markedUnread = c.isMarkedAsUnread,
                preview = if (draft.isNotBlank()) "Draft: $draft" else MsgMapper.preview(last?.content),
                lastDate = last?.date ?: 0,
                lastSender = if (last != null && !last.isOutgoing && (kind == "group" || kind == "channel")) {
                    names.sender(last.senderId)
                } else "",
                lastOutgoing = last?.isOutgoing == true,
                lastMsgId = last?.id ?: 0L,
                lastReadOutboxId = c.lastReadOutboxMessageId,
                lastReadInboxId = c.lastReadInboxMessageId,
                draft = draft,
                presence = if (kind == "private" || kind == "bot") presence(peerId) else "",
                // Only hide the composer when Telegram explicitly forbids posting (channels etc.).
                canSend = self || c.permissions?.canSendBasicMessages != false,
                saved = self,
                kind = kind,
            )
            for (pos in c.positions) {
                if (pos.order == 0L) continue
                val key = listKey(pos.list) ?: continue
                seen(c.photo?.small)
                byList.getOrPut(key) { mutableListOf() }
                    .add(base.copy(order = pos.order, pinned = pos.isPinned))
            }
        }
        // Telegram ordering: pinned chats first, then descending position order.
        byList.values.forEach { list ->
            list.sortWith(compareByDescending<ChatRow> { it.pinned }.thenByDescending { it.order })
        }
        chatsByList.value = byList
        chats.value = byList["main"] ?: emptyList()
    }

    private fun listKey(l: TdApi.ChatList): String? = when (l) {
        is TdApi.ChatListMain -> "main"
        is TdApi.ChatListArchive -> "archive"
        is TdApi.ChatListFolder -> "folder:${l.chatFolderId}"
        else -> null
    }

    private fun sameList(a: TdApi.ChatList, b: TdApi.ChatList): Boolean = when {
        a is TdApi.ChatListMain && b is TdApi.ChatListMain -> true
        a is TdApi.ChatListArchive && b is TdApi.ChatListArchive -> true
        a is TdApi.ChatListFolder && b is TdApi.ChatListFolder -> a.chatFolderId == b.chatFolderId
        else -> false
    }

    // ───────────────────────────── lists: archive & folders ─────────────────────────────

    private val loadedLists = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun listLabel(key: String): String = when {
        key == "main" -> "Chats"
        key == "archive" -> "Archive"
        else -> key.removePrefix("folder:")
    }

    /** Lazily page a non-main list (archive / folder) until TDLib reports it exhausted. */
    private val mainDone = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Loads the next batch of the main chat list. TDLib resumes where the previous call stopped and
     * answers 404 once every chat is loaded, so the list can grow incrementally — no chat history
     * or attachment is fetched for the list itself.
     */
    suspend fun loadMoreChats(): Boolean {
        if (!isReady || mainDone.get()) return false
        return try {
            send(TdApi.LoadChats(TdApi.ChatListMain(), 100))
            true
        } catch (e: TdException) {
            if (e.code == 404) mainDone.set(true)
            false
        }
    }

    suspend fun ensureList(key: String) {
        if (key == "main" || !loadedLists.add(key)) return
        val list: TdApi.ChatList = when {
            key == "archive" -> TdApi.ChatListArchive()
            key.startsWith("folder:") ->
                TdApi.ChatListFolder(key.removePrefix("folder:").toIntOrNull() ?: return)
            else -> return
        }
        var fails = 0
        while (fails < 3) {
            try {
                send(TdApi.LoadChats(list, 100))
            } catch (e: TdException) {
                if (e.code == 404) break
                fails++
                kotlinx.coroutines.delay(1500)
            }
        }
    }

    suspend fun folderName(id: Int): String = runCatching {
        send(TdApi.GetChatFolder(id)).name.text.text
    }.getOrDefault("Folder $id")

    // ───────────────────────────── global search & stats ─────────────────────────────

    /** Cross-chat message search (media messages become openable rows). */
    suspend fun searchGlobal(query: String, offset: String): Pair<List<Media>, String> {
        val req = TdApi.SearchMessages()
        req.chatList = null
        req.query = query
        req.offset = offset
        req.limit = 40
        val r = send(req)
        return r.messages.mapNotNull { toMedia(it) } to (r.nextOffset ?: "")
    }

    /** Aggregated TDLib network statistics: label → (rx bytes, tx bytes). */
    suspend fun networkStats(): List<Triple<String, Long, Long>> {
        val r = send(TdApi.GetNetworkStatistics(false))
        val acc = LinkedHashMap<String, LongArray>()
        for (e in r.entries) {
            if (e !is TdApi.NetworkStatisticsEntryFile) continue
            val label = when (e.networkType) {
                is TdApi.NetworkTypeWiFi -> "Wi-Fi"
                is TdApi.NetworkTypeMobile, is TdApi.NetworkTypeMobileRoaming -> "Mobile data"
                else -> "Other"
            }
            val a = acc.getOrPut(label) { LongArray(2) }
            a[0] += e.receivedBytes
            a[1] += e.sentBytes
        }
        return acc.map { Triple(it.key, it.value[0], it.value[1]) }
    }

    private fun seen(f: TdApi.File?): Int {
        if (f == null) return 0
        fileMap[f.id] = f
        return f.id
    }

    /** Registers files from a message so previews and the player can resolve them. */
    internal fun track(f: TdApi.File?): Int = seen(f)

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

    /** Hook for download-error responses (TdApi.Error replies to DownloadFile). */
    @Volatile var onDownloadError: ((Int, String) -> Unit)? = null

    /**
     * Ask TDLib to fetch [limit] bytes starting at [offset] (limit 0 = to the end).
     * Progress is delivered through UpdateFile events. Priority 1..32 (32 = most urgent),
     * **0 parks the transfer** (TDLib stops fetching but keeps the downloaded prefix, so a
     * later re-issue resumes without re-transferring a single byte).
     */
    /** False until createClient() ran (i.e. TG_API_ID != 0); guards background loops. */
    val isReady: Boolean get() = ::client.isInitialized

    fun download(id: Int, priority: Int = 1, offset: Long = 0, limit: Long = 0) {
        if (!isReady) return
        client.send(TdApi.DownloadFile(id, priority, offset, limit, false)) { r ->
            when (r) {
                is TdApi.File -> fileMap[r.id] = r
                is TdApi.Error -> onDownloadError?.invoke(id, "${r.code}: ${r.message}")
                else -> {}
            }
        }
    }

    /**
     * Documented as mandatory by TDLib ("must be called whenever the network is changed, even if
     * the network type remains the same"): forces connections to reopen instead of stalling on
     * stale sockets after Wi-Fi <-> mobile-data or access-point changes.
     */
    fun setNetworkType(type: TdApi.NetworkType?) {
        runCatching { client.send(TdApi.SetNetworkType(type)) {} }
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
            // Never cancel while a download job still needs this file.
            if ((downloadUsers[id]?.get() ?: 0) <= 0) cancelDownload(id)
        }
    }

    fun isStreaming(id: Int): Boolean = (streamUsers[id]?.get() ?: 0) > 0

    // ── download sessions: refcounted separately from streaming so cancelling a download can
    //    never cancel the player's window, and vice versa.
    private val downloadUsers = ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()

    fun beginDownload(id: Int) {
        downloadUsers.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
    }

    fun endDownload(id: Int) {
        val n = downloadUsers[id]?.decrementAndGet() ?: 0
        if (n <= 0) {
            downloadUsers.remove(id)
            if (!isStreaming(id)) cancelDownload(id)
        }
    }

    fun isDownloadActive(id: Int): Boolean = (downloadUsers[id]?.get() ?: 0) > 0

    /** Event-fed map lookup only — never blocks on GetFile (used by the download loop). */
    fun peekFile(id: Int): TdApi.File? = fileMap[id]

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

    // ────────────────────── users, presence & messages ──────────────────────

    private val userMap = ConcurrentHashMap<Long, TdApi.User>()
    private val presenceMap = ConcurrentHashMap<Long, String>()
    private val nameRequested = ConcurrentHashMap.newKeySet<Long>()

    /** Display name for a user; asks TDLib once when it is not cached yet. */
    fun userName(id: Long): String {
        if (id <= 0L) return ""
        val u = userMap[id] ?: run { requestUser(id); return "" }
        return listOf(u.firstName, u.lastName).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { u.phoneNumber.orEmpty().ifBlank { "User $id" } }
    }

    private fun requestUser(id: Long) {
        if (!isReady || id <= 0L || !nameRequested.add(id)) return
        client.send(TdApi.GetUser(id)) { r ->
            if (r is TdApi.User) {
                userMap[r.id] = r
                presenceMap[r.id] = presenceText(r.status)
                dirty.trySend(Unit)
            }
        }
    }

    fun presence(userId: Long): String = presenceMap[userId] ?: ""

    fun isBotUser(userId: Long): Boolean = userMap[userId]?.type is TdApi.UserTypeBot

    private fun presenceText(s: TdApi.UserStatus?): String = when (s) {
        is TdApi.UserStatusOnline -> "online"
        is TdApi.UserStatusOffline -> "last seen ${relTime(s.wasOnline)}"
        is TdApi.UserStatusRecently -> "last seen recently"
        is TdApi.UserStatusLastWeek -> "last seen within a week"
        is TdApi.UserStatusLastMonth -> "last seen within a month"
        else -> ""
    }

    private fun relTime(unix: Int): String {
        if (unix <= 0) return "recently"
        val mins = (System.currentTimeMillis() / 1000 - unix) / 60
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            mins < 60 * 24 * 7 -> "${mins / (60 * 24)}d ago"
            else -> {
                // Uses the pure calendar helpers in this package (no Android/i18n dependency).
                val now = (System.currentTimeMillis() / 1000).toInt()
                val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
                dayLabel(dayBucket(unix, tz), dayBucket(now, tz))
            }
        }
    }

    private val names = object : NameResolver {
        override fun sender(s: TdApi.MessageSender?): String = when (s) {
            is TdApi.MessageSenderUser -> if (s.userId == meId) "You" else userName(s.userId)
            is TdApi.MessageSenderChat -> chatMap[s.chatId]?.title.orEmpty()
            else -> ""
        }

        override fun origin(o: TdApi.MessageOrigin?): String = when (o) {
            is TdApi.MessageOriginUser -> userName(o.senderUserId)
            is TdApi.MessageOriginChat ->
                chatMap[o.senderChatId]?.title ?: o.authorSignature.orEmpty()
            is TdApi.MessageOriginChannel ->
                o.authorSignature?.takeIf { it.isNotBlank() } ?: chatMap[o.chatId]?.title.orEmpty()
            else -> ""
        }
    }

    /** One older page of a conversation (newest-first), plus TDLib's total when known. */
    suspend fun history(chatId: Long, fromId: Long, offset: Int, limit: Int): Pair<List<Msg>, Int> {
        val req = TdApi.GetChatHistory()
        req.chatId = chatId
        req.fromMessageId = fromId
        req.offset = offset
        req.limit = limit
        req.onlyLocal = false
        val r = send(req)
        return r.messages.map { MsgMapper.map(it, names) { f -> seen(f) } } to r.totalCount
    }

    suspend fun getMessage(chatId: Long, msgId: Long): Msg? = runCatching {
        val m = send(TdApi.GetMessage(chatId, msgId))
        MsgMapper.map(m, names) { f -> seen(f) }
    }.getOrNull()

    suspend fun sendText(chatId: Long, text: String, replyToId: Long = 0L): Msg {
        val req = TdApi.SendMessage()
        req.chatId = chatId
        if (replyToId > 0L) req.replyTo = TdApi.InputMessageReplyToMessage().apply { messageId = replyToId }
        req.inputMessageContent = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText().apply { this.text = text; entities = emptyArray() }
            clearDraft = true
        }
        return MsgMapper.map(send(req), names) { f -> seen(f) }
    }

    /**
     * Sends a local file from the system picker. The mime/name decide the Telegram content type so
     * photos, videos, GIFs and documents all arrive the way the recipient expects them.
     */
    suspend fun sendFile(chatId: Long, path: String, mime: String, name: String, caption: String = ""): Msg {
        val cap = TdApi.FormattedText().apply { text = caption; entities = emptyArray() }
        val local = TdApi.InputFileLocal().apply { this.path = path }
        val gif = mime == "image/gif" || name.endsWith(".gif", true)
        val content: TdApi.InputMessageContent = when {
            gif -> TdApi.InputMessageAnimation().apply {
                animation = TdApi.InputAnimation().apply { this.animation = local }
                this.caption = cap
            }
            mime.startsWith("image/") -> TdApi.InputMessagePhoto().apply {
                photo = TdApi.InputPhoto().apply { this.photo = local }
                this.caption = cap
            }
            mime.startsWith("video/") -> TdApi.InputMessageVideo().apply {
                video = TdApi.InputVideo().apply { this.video = local; supportsStreaming = true }
                this.caption = cap
            }
            mime.startsWith("audio/") -> TdApi.InputMessageAudio().apply {
                audio = TdApi.InputAudio().apply { this.audio = local }
                this.caption = cap
            }
            else -> TdApi.InputMessageDocument().apply {
                document = TdApi.InputDocument().apply { this.document = local }
                this.caption = cap
            }
        }
        val req = TdApi.SendMessage()
        req.chatId = chatId
        req.inputMessageContent = content
        return MsgMapper.map(send(req), names) { f -> seen(f) }
    }

    /** Marks messages read (and clears the unread badge) — the same call Telegram clients make. */
    fun markRead(chatId: Long, ids: LongArray) {
        if (!isReady || ids.isEmpty()) return
        val req = TdApi.ViewMessages()
        req.chatId = chatId
        req.messageIds = ids
        req.source = TdApi.MessageSourceChatHistory()
        req.forceRead = true
        client.send(req) {}
    }

    fun openContent(chatId: Long, msgId: Long) {
        if (!isReady) return
        client.send(TdApi.OpenMessageContent(chatId, msgId)) {}
    }

    fun togglePin(chatId: Long, pinned: Boolean) {
        if (!isReady) return
        client.send(TdApi.ToggleChatIsPinned(TdApi.ChatListMain(), chatId, pinned)) { }
    }

    fun toggleUnread(chatId: Long, unread: Boolean) {
        if (!isReady) return
        client.send(TdApi.ToggleChatIsMarkedAsUnread(chatId, unread)) {}
    }

    /** Mute for a year (Telegram's "mute" default) or unmute. */
    fun setMuted(chatId: Long, muted: Boolean) {
        if (!isReady) return
        val s = TdApi.ChatNotificationSettings()
        s.useDefaultMuteFor = false
        s.muteFor = if (muted) 31536000 else 0
        client.send(TdApi.SetChatNotificationSettings(chatId, s)) {}
    }

    fun react(chatId: Long, msgId: Long, emoji: String) {
        if (!isReady) return
        val req = TdApi.AddMessageReaction()
        req.chatId = chatId
        req.messageId = msgId
        req.reactionType = TdApi.ReactionTypeEmoji().apply { this.emoji = emoji }
        req.updateRecentReactions = false
        client.send(req) {}
    }

    /** Edits the text of a message the current user sent (Telegram rejects anything else). */
    suspend fun editText(chatId: Long, msgId: Long, text: String): Msg {
        val req = TdApi.EditMessageText()
        req.chatId = chatId
        req.messageId = msgId
        req.inputMessageContent = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText().apply { this.text = text; entities = emptyArray() }
        }
        return MsgMapper.map(send(req), names) { f -> seen(f) }
    }

    /** Deletes messages, revoking them for the other side where Telegram allows it. */
    fun deleteMessage(chatId: Long, msgId: Long, revoke: Boolean = true) {
        if (!isReady) return
        client.send(TdApi.DeleteMessages(chatId, longArrayOf(msgId), revoke)) {}
    }

    private fun refetchMessage(chatId: Long, msgId: Long) {
        if (!isReady) return
        scope.launch {
            val m = getMessage(chatId, msgId)
            if (m != null) ChatRepo.onReplace(m)
        }
    }

    private fun reactionsOf(rs: Array<TdApi.MessageReaction>?): List<Reaction> = rs?.mapNotNull { r ->
        val e = (r.type as? TdApi.ReactionTypeEmoji)?.emoji ?: return@mapNotNull null
        Reaction(e, r.totalCount, r.isChosen)
    }.orEmpty()

    // ───────────────────────────── files: listing ─────────────────────────────

    suspend fun searchMedia(chatId: Long, section: Section, query: String, from: Long): Page {
        val filter: TdApi.SearchMessagesFilter = when (section) {
            Section.VIDEOS -> TdApi.SearchMessagesFilterVideo()
            Section.AUDIO -> TdApi.SearchMessagesFilterAudio()
            Section.DOCS -> TdApi.SearchMessagesFilterDocument()
            Section.PHOTOS -> TdApi.SearchMessagesFilterPhoto()
            // Real Telegram filters, scoped to this one chat id — never cross-chat.
            Section.LINKS -> TdApi.SearchMessagesFilterUrl()
            Section.VOICE -> TdApi.SearchMessagesFilterVoiceNote()
        }
        val req = TdApi.SearchChatMessages()
        req.chatId = chatId
        req.query = query
        req.fromMessageId = from
        req.offset = 0
        req.limit = 40
        req.filter = filter
        val r = send(req)
        val items = r.messages.mapNotNull {
            if (section == Section.LINKS) toLinkMedia(it) else toMedia(it)
        }
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
            // Previously dropped (returned null) — these are real, playable attachments that
            // belong in the chat's media sections even though they were never "files".
            is TdApi.MessageVoiceNote -> {
                val v = c.voiceNote
                Media(
                    m.chatId, m.id, Kind.AUDIO,
                    "Voice message", v.mimeType,
                    maxOf(v.voice.size, v.voice.expectedSize), v.duration,
                    seen(v.voice), v.voice.id, 0, null, m.date, "",
                )
            }
            is TdApi.MessageVideoNote -> {
                val v = c.videoNote
                Media(
                    m.chatId, m.id, Kind.VIDEO,
                    "Video message", "",
                    maxOf(v.video.size, v.video.expectedSize), v.duration,
                    seen(v.video), v.video.id, seen(v.thumbnail?.file),
                    v.minithumbnail?.data, m.date, "",
                )
            }
            is TdApi.MessageAnimation -> {
                val a = c.animation
                Media(
                    m.chatId, m.id, Kind.VIDEO,
                    nameOf(a.fileName, c.caption.text, "GIF", m.date), a.mimeType,
                    maxOf(a.animation.size, a.animation.expectedSize), a.duration,
                    seen(a.animation), a.animation.id, seen(a.thumbnail?.file),
                    a.minithumbnail?.data, m.date, c.caption.text,
                )
            }
            else -> null
        }
    }

    private val URL_RE = Regex("""https?://[^\s<>"']+""")

    /**
     * One Links-section item: the webpage preview when Telegram parsed one, otherwise the first
     * URL in the message/caption text. The URL travels in `fileName` (persisted by NavCodec).
     */
    private fun toLinkMedia(m: TdApi.Message): Media? {
        val text: String
        val page: TdApi.LinkPreview?
        when (val c = m.content) {
            is TdApi.MessageText -> { text = c.text?.text.orEmpty(); page = c.linkPreview }
            is TdApi.MessagePhoto -> { text = c.caption?.text.orEmpty(); page = null }
            is TdApi.MessageVideo -> { text = c.caption?.text.orEmpty(); page = null }
            is TdApi.MessageAnimation -> { text = c.caption?.text.orEmpty(); page = null }
            is TdApi.MessageDocument -> { text = c.caption?.text.orEmpty(); page = null }
            else -> return null
        }
        val url = page?.url?.takeIf { it.isNotBlank() } ?: URL_RE.find(text)?.value ?: return null
        val name = page?.let { pg ->
            val site = pg.siteName?.takeIf { it.isNotBlank() }
            val ttl = pg.title?.takeIf { it.isNotBlank() }
            when {
                site != null && ttl != null -> "$site — $ttl"
                site != null -> site
                ttl != null -> ttl
                else -> null
            }
        } ?: url
        val desc = page?.description?.text?.takeIf { it.isNotBlank() } ?: text
        return Media(
            m.chatId, m.id, Kind.LINK, name, "text/uri-list", 0L, 0,
            0, 0, 0, null, m.date, desc.take(300), fileName = url,
        )
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
