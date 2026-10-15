package com.airbooks.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbooks.app.tg.Album
import com.airbooks.app.tg.ChatRepo
import com.airbooks.app.tg.ChatRow
import com.airbooks.app.tg.ChatItem
import com.airbooks.app.tg.ChatStruct
import com.airbooks.app.tg.DateHeader
import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Msg
import com.airbooks.app.tg.MsgKind
import com.airbooks.app.tg.Reaction
import com.airbooks.app.tg.SendState
import com.airbooks.app.tg.Single
import com.airbooks.app.tg.Tg
import com.airbooks.app.tg.UnreadHeader
import com.airbooks.app.tg.dayBucket
import com.airbooks.app.tg.dayLabel
import com.airbooks.app.tg.ticksFor
import com.airbooks.app.tg.toMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

/** Quick reactions offered on long-press — the same set Telegram surfaces. */
private val QUICK_REACTIONS = listOf("👍", "❤️", "🔥", "🎉", "😂", "🙏", "👎")

/** Emoji available in the built-in panel (real characters inserted into the draft). */
private val EMOJI = listOf(
    "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😜", "🤔", "🤨",
    "😎", "🥳", "😴", "😭", "😤", "😡", "🤯", "😱", "🤗", "🤝",
    "👍", "👎", "👌", "✌️", "🤞", "👏", "🙏", "💪", "👀", "🧠",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💯", "💔", "✨",
    "🔥", "🎉", "🎊", "🎁", "🏆", "⭐", "🌟", "⚡", "☀️", "🌙",
    "📌", "📎", "📁", "📷", "🎬", "🎵", "🎧", "📚", "✏️", "📝",
    "✅", "❌", "⏰", "🚀", "💬", "📞", "🍕", "☕", "🌍", "🏠",
)

/**
 * A real Telegram conversation.
 *
 * Everything here is live TDLib data: history is paged (never fully loaded), incoming messages
 * arrive through updates, sending goes through `sendMessage`, and attachments reuse the existing
 * player / viewer / downloader pipeline via [Msg.toMedia].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    chat: ChatRow,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenMedia: (Media, List<Media>) -> Unit,
    onDownload: (Media) -> Unit,
) {
    val st = remember(chat.id) { ChatRepo.state(chat.id) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }

    // Chat info is re-read from the live list so title/avatar/presence/permissions stay current.
    val lists by Tg.chatsByList.collectAsState()
    val live = remember(lists, chat.id) {
        lists.values.asSequence().flatten().firstOrNull { it.id == chat.id } ?: chat
    }

    var text by rememberSaveable(chat.id) { mutableStateOf("") }
    var replying by remember { mutableStateOf<Msg?>(null) }
    var editing by remember { mutableStateOf<Msg?>(null) }
    var menuMsg by remember { mutableStateOf<Msg?>(null) }
    var emojiOpen by remember { mutableStateOf(false) }

    LaunchedEffect(chat.id) { ChatRepo.loadOlder(chat.id, st) }

    // Older pages load while scrolling up. With reverseLayout the oldest item is the last index,
    // and appending there never moves what the user is reading.
    val needOlder by remember {
        derivedStateOf {
            val last = st.items.size - 1
            last >= 0 && !st.done && listState.layoutInfo.visibleItemsInfo.any { it.index >= last - 4 }
        }
    }
    LaunchedEffect(needOlder) {
        if (needOlder) ChatRepo.loadOlder(chat.id, st)
    }

    // Read receipts for whatever is on screen.
    LaunchedEffect(st.items.size, live.lastReadInboxId) {
        val unread = st.items.filter { !it.outgoing && it.id > live.lastReadInboxId }.map { it.id }
        if (unread.isNotEmpty()) Tg.markRead(chat.id, unread.toLongArray())
    }

    val tz = remember { TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000 }
    val rows = remember(st.items.size, st.items.hashCode(), live.lastReadInboxId, tz) {
        val today = dayBucket((System.currentTimeMillis() / 1000).toInt(), tz)
        ChatStruct.build(st.items, tz, live.lastReadInboxId) { dayLabel(it, today) }
    }

    fun open(m: Msg) {
        val media = m.toMedia() ?: return
        Tg.openContent(m.chatId, m.id)
        val all = st.items.mapNotNull { it.toMedia() }.filter { it.kind == media.kind }
        onOpenMedia(media, all.ifEmpty { listOf(media) })
    }

    fun jumpTo(msgId: Long) {
        val here = st.items.indexOfFirst { it.id == msgId }
        if (here >= 0) {
            scope.launch { listState.animateScrollToItem(here) }
            return
        }
        scope.launch {
            // The reply target may be older than what is loaded: page back until it shows up.
            var guard = 0
            while (guard++ < 25 && !st.done) {
                ChatRepo.loadOlder(chat.id, st)
                val i = st.items.indexOfFirst { it.id == msgId }
                if (i >= 0) {
                    listState.animateScrollToItem(i)
                    return@launch
                }
            }
            snackbar.showSnackbar("That message is outside the loaded history")
        }
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        val editTarget = editing
        val replyTo = replying?.id ?: 0L
        text = ""
        editing = null
        replying = null
        emojiOpen = false
        scope.launch {
            val r = runCatching {
                if (editTarget != null) Tg.editText(chat.id, editTarget.id, body)
                else Tg.sendText(chat.id, body, replyTo)
            }
            r.onSuccess { m -> ChatRepo.onNew(m) }
                .onFailure { e -> snackbar.showSnackbar(e.message ?: "Send failed") }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val caption = text.trim()
        text = ""
        scope.launch {
            val f = withContext(Dispatchers.IO) { uploadCopy(ctx, uri) }
            if (f == null) {
                snackbar.showSnackbar("Couldn't read that file")
                return@launch
            }
            val r = runCatching { Tg.sendFile(chat.id, f.first, f.second, f.third, caption) }
            r.onSuccess { m -> ChatRepo.onNew(m) }
                .onFailure { e -> snackbar.showSnackbar(e.message ?: "Upload failed") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // ime union keeps the composer above the keyboard without double-padding the nav bar.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        topBar = {
            ChatTopBar(
                live,
                onBack = onBack,
                onOpenFiles = onOpenFiles,
                onPin = { Tg.togglePin(chat.id, !live.pinned) },
                onMute = { Tg.setMuted(chat.id, !live.muted) },
                onUnread = { Tg.toggleUnread(chat.id, !live.markedUnread) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    st.items.isEmpty() && st.loading ->
                        Centered { CircularProgressIndicator() }

                    st.items.isEmpty() && st.error != null -> Centered {
                        Text(
                            st.error ?: "", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Button(onClick = {
                            scope.launch { ChatRepo.loadOlder(chat.id, st) }
                        }) { Text("Retry") }
                    }

                    st.items.isEmpty() -> Centered {
                        Text(
                            "No messages yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        lazyItems(rows, key = { it.stableKey }) { row ->
                            when (row) {
                                is DateHeader -> DateDivider(row.label)
                                is UnreadHeader -> UnreadDivider()
                                is Single -> Bubble(
                                    row.msg, live, showAvatar = true,
                                    onOpen = ::open, onDownload = onDownload,
                                    onLongPress = { menuMsg = it },
                                    onReact = { m, e -> Tg.react(m.chatId, m.id, e) },
                                    onReplyJump = ::jumpTo,
                                )
                                is Album -> AlbumBubble(
                                    row, live, onOpen = ::open, onLongPress = { menuMsg = it },
                                )
                            }
                        }
                        if (!st.done) {
                            item(key = "older-spinner") {
                                Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                                    if (st.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                }

                // Scroll-to-latest.
                val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
                if (showJump) {
                    Surface(
                        Modifier.align(Alignment.BottomEnd).padding(14.dp).size(44.dp)
                            .clip(CircleShape).clickable {
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 3.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Scroll to latest")
                        }
                    }
                }
            }

            // Reply / edit banner.
            val banner = editing ?: replying
            if (banner != null) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (editing != null) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Reply,
                        null, Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (editing != null) "Editing message" else "Reply to ${banner.senderName.ifBlank { "message" }}",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            banner.body.lineSequence().firstOrNull().orEmpty().ifBlank { "Message" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { replying = null; editing = null; text = "" }) {
                        Icon(Icons.Filled.Close, "Cancel", Modifier.size(18.dp))
                    }
                }
            }

            if (emojiOpen) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(44.dp),
                    modifier = Modifier.fillMaxWidth().height(190.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    gridItems(EMOJI, key = { it }) { e ->
                        Box(
                            Modifier.size(42.dp).clip(CircleShape)
                                .clickable {
                                    text += e
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                }
            }

            if (live.canSend) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(onClick = { emojiOpen = !emojiOpen }) {
                        Icon(
                            Icons.Filled.EmojiEmotions, "Emoji",
                            tint = if (emojiOpen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { picker.launch("*/*") }) {
                        Icon(Icons.Filled.AttachFile, "Attach file")
                    }
                    TextField(
                        value = text,
                        onValueChange = { if (it.length <= 4096) text = it },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp),
                        placeholder = { Text("Message") },
                        maxLines = 5,
                        shape = RoundedCornerShape(22.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { send() },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                            .background(
                                if (text.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send, "Send",
                            tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    if (live.kind == "channel") "Channel — posting is not allowed"
                    else "You can't send messages in this chat",
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Long-press menu for a message.
    menuMsg?.let { m ->
        MsgActions(
            msg = m,
            chat = live,
            onDismiss = { menuMsg = null },
            onReply = { menuMsg = null; replying = m; editing = null },
            onEdit = {
                menuMsg = null; editing = m; replying = null; text = m.text
            },
            onCopy = {
                menuMsg = null
                if (m.body.isNotBlank()) clipboard.setText(AnnotatedString(m.body))
            },
            onDelete = {
                menuMsg = null
                Tg.deleteMessage(m.chatId, m.id)
            },
            onDownload = { menuMsg = null; m.toMedia()?.let(onDownload) },
            onRetry = {
                menuMsg = null
                scope.launch {
                    val r = runCatching { Tg.sendText(chat.id, m.body, 0L) }
                    r.onSuccess { ChatRepo.onNew(it) }
                        .onFailure { e -> snackbar.showSnackbar(e.message ?: "Send failed") }
                    Tg.deleteMessage(m.chatId, m.id)
                }
            },
            onReact = { e -> menuMsg = null; Tg.react(m.chatId, m.id, e) },
        )
    }
}

@Composable
private fun Centered(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    chat: ChatRow,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onUnread: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Avatar(chat.title, chat.photoId, 38)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val sub = when {
                        chat.presence.isNotBlank() -> chat.presence
                        chat.saved -> "Saved messages"
                        chat.kind == "bot" -> "Bot"
                        else -> chat.subtitle
                    }
                    Text(
                        sub, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                        color = if (chat.presence == "online") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = {
            IconButton(onClick = onOpenFiles) { Icon(Icons.Filled.Folder, "Media and files") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (chat.pinned) "Unpin chat" else "Pin chat") },
                    leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                    onClick = { menu = false; onPin() },
                )
                DropdownMenuItem(
                    text = { Text(if (chat.muted) "Unmute" else "Mute") },
                    leadingIcon = { Icon(Icons.Filled.NotificationsOff, null) },
                    onClick = { menu = false; onMute() },
                )
                DropdownMenuItem(
                    text = { Text(if (chat.markedUnread) "Mark as read" else "Mark as unread") },
                    leadingIcon = {
                        Icon(
                            if (chat.markedUnread) Icons.Filled.MarkChatRead
                            else Icons.Filled.MarkChatUnread, null,
                        )
                    },
                    onClick = { menu = false; onUnread() },
                )
                DropdownMenuItem(
                    text = { Text("Media and files") },
                    leadingIcon = { Icon(Icons.Filled.Folder, null) },
                    onClick = { menu = false; onOpenFiles() },
                )
            }
        },
    )
}

/** Long-press action sheet for one message. */
@Composable
private fun MsgActions(
    msg: Msg,
    chat: ChatRow,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onReact: (String) -> Unit,
) {
    val own = msg.outgoing || chat.saved
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = {
            Text(
                msg.body.lineSequence().take(2).joinToString(" ").ifBlank { kindLabel(msg.kind) },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    QUICK_REACTIONS.forEach { e ->
                        Surface(
                            Modifier.size(40.dp).clip(CircleShape).clickable { onReact(e) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) { Box(contentAlignment = Alignment.Center) { Text(e, fontSize = 19.sp) } }
                    }
                }
                Spacer(Modifier.height(6.dp))
                MenuLine("Reply", onReply)
                if (own && msg.kind == MsgKind.TEXT) MenuLine("Edit", onEdit)
                if (msg.body.isNotBlank()) MenuLine("Copy text", onCopy)
                if (msg.hasMedia) MenuLine("Download", onDownload)
                if (msg.state == SendState.FAILED) MenuLine("Retry sending", onRetry)
                if (own) MenuLine("Delete", onDelete, destructive = true)
            }
        },
    )
}

@Composable
private fun MenuLine(label: String, onClick: () -> Unit, destructive: Boolean = false) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 11.dp, horizontal = 4.dp),
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        fontSize = 15.sp,
    )
}

private fun kindLabel(k: MsgKind): String = when (k) {
    MsgKind.PHOTO -> "Photo"
    MsgKind.VIDEO -> "Video"
    MsgKind.AUDIO -> "Audio"
    MsgKind.VOICE -> "Voice message"
    MsgKind.VIDEO_NOTE -> "Video message"
    MsgKind.DOCUMENT -> "File"
    MsgKind.ANIMATION -> "GIF"
    MsgKind.STICKER -> "Sticker"
    MsgKind.LOCATION -> "Location"
    MsgKind.VENUE -> "Venue"
    MsgKind.CONTACT -> "Contact"
    MsgKind.POLL -> "Poll"
    MsgKind.DICE -> "Dice"
    MsgKind.CALL -> "Call"
    MsgKind.SERVICE -> "Service message"
    MsgKind.TEXT, MsgKind.UNSUPPORTED -> "Message"
}

@Composable
private fun DateDivider(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnreadDivider() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)))
        Text(
            "Unread messages",
            Modifier.padding(horizontal = 8.dp),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Msg,
    chat: ChatRow,
    showAvatar: Boolean,
    onOpen: (Msg) -> Unit,
    onDownload: (Media) -> Unit,
    onLongPress: (Msg) -> Unit,
    onReact: (Msg, String) -> Unit,
    onReplyJump: (Long) -> Unit,
) {
    if (m.kind == MsgKind.SERVICE) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                m.serviceText.ifBlank { "Service message" },
                Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val out = m.outgoing
    val shape = RoundedCornerShape(
        topStart = if (out) 16.dp else 5.dp,
        topEnd = if (out) 5.dp else 16.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp,
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = if (out) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!out && chat.isGroup && showAvatar) {
            Avatar(m.senderName.ifBlank { "?" }, chat.photoId, 30)
            Spacer(Modifier.width(6.dp))
        }
        Surface(
            shape = shape,
            color = if (out) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 0.5.dp,
            modifier = Modifier.widthIn(max = 520.dp).combinedClickable(
                onClick = { if (m.hasMedia) onOpen(m) },
                onLongClick = { onLongPress(m) },
            ),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (!out && chat.isGroup && m.senderName.isNotBlank()) {
                    Text(
                        m.senderName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (m.forwardFrom.isNotBlank()) {
                    Text(
                        "Forwarded from ${m.forwardFrom}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                }
                m.reply?.let { r ->
                    ReplyPreview(r, onReplyJump)
                    Spacer(Modifier.height(4.dp))
                }
                MessageBody(m, chat, onOpen, onDownload)
                Footer(m, chat)
                if (m.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        m.reactions.take(8).forEach { r -> ReactionChip(m, r, onReact) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyPreview(r: com.airbooks.app.tg.ReplyRef, onJump: (Long) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp))
            .clickable { if (r.msgId != 0L) onJump(r.msgId) }
            .padding(vertical = 2.dp),
    ) {
        Box(
            Modifier.width(3.dp).height(30.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
        )
        Column(Modifier.padding(start = 7.dp).widthIn(max = 260.dp)) {
            Text(
                r.sender.ifBlank { "Message" }, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                r.preview.ifBlank { "…" }, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReactionChip(m: Msg, r: Reaction, onReact: (Msg, String) -> Unit) {
    val chosen = r.chosen
    Surface(
        Modifier.clip(RoundedCornerShape(11.dp)).clickable { onReact(m, r.emoji) },
        shape = RoundedCornerShape(11.dp),
        color = if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(r.emoji, fontSize = 14.sp)
            Spacer(Modifier.width(3.dp))
            Text("${r.count}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Time + delivery state row inside a bubble. */
@Composable
private fun Footer(m: Msg, chat: ChatRow) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (m.kind == MsgKind.TEXT && m.text.isNotBlank()) Spacer(Modifier.width(8.dp))
        if (m.views > 0) {
            Text("${m.views} views", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(5.dp))
        }
        if (m.edited) {
            Text("edited", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(5.dp))
        }
        Text(
            timeOf(m.date), fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (m.outgoing) {
            Spacer(Modifier.width(3.dp))
            val tint = if (m.state == SendState.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
            when {
                m.state == SendState.PENDING ->
                    Icon(Icons.Filled.Schedule, "Sending", Modifier.size(14.dp), tint = tint)
                m.state == SendState.FAILED ->
                    Icon(Icons.Filled.ErrorOutline, "Failed to send", Modifier.size(14.dp), tint = tint)
                ticksFor(m.id, chat.lastReadOutboxId, true) == 2 ->
                    Icon(Icons.Filled.DoneAll, "Read", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                else -> Icon(Icons.Filled.Check, "Sent", Modifier.size(14.dp), tint = tint)
            }
        }
    }
}

private fun timeOf(unix: Int): String {
    if (unix <= 0) return ""
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = unix * 1000L
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val min = cal.get(java.util.Calendar.MINUTE)
    return "%d:%02d".format(h, min)
}

/** The media-specific part of a bubble. */
@Composable
private fun MessageBody(
    m: Msg,
    chat: ChatRow,
    onOpen: (Msg) -> Unit,
    onDownload: (Media) -> Unit,
) {
    val ctx = LocalContext.current
    when (m.kind) {
        MsgKind.TEXT, MsgKind.UNSUPPORTED -> {
            if (m.text.isNotBlank()) {
                Text(m.text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            } else if (m.kind == MsgKind.UNSUPPORTED) {
                Text(
                    "Unsupported message type", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            m.link?.let { l ->
                Spacer(Modifier.height(6.dp))
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        Tg.openContent(m.chatId, m.id)
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url))) }
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        if (l.site.isNotBlank()) {
                            Text(
                                l.site, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (l.title.isNotBlank()) {
                            Text(
                                l.title, fontSize = 14.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (l.desc.isNotBlank()) {
                            Text(
                                l.desc, fontSize = 13.sp, maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            l.url, fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        MsgKind.PHOTO, MsgKind.VIDEO, MsgKind.ANIMATION, MsgKind.VIDEO_NOTE -> {
            val ar = if (m.width > 0 && m.height > 0) {
                (m.width.toFloat() / m.height).coerceIn(0.55f, 1.8f)
            } else 1.3f
            val w = (300f * minOf(ar, 1f)).coerceIn(140f, 300f).dp
            Box(
                Modifier.width(w).aspectRatio(ar).clip(RoundedCornerShape(10.dp))
                    .clickable { onOpen(m) },
            ) {
                TgImage(
                    fileId = if (m.thumbId != 0) m.thumbId else m.fileId,
                    mini = m.mini,
                    modifier = Modifier.fillMaxSize(),
                )
                if (m.kind == MsgKind.VIDEO || m.kind == MsgKind.ANIMATION || m.kind == MsgKind.VIDEO_NOTE) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            Modifier.size(46.dp), shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.PlayArrow, "Play",
                                    tint = Color.White, modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
                if (m.duration > 0) {
                    Text(
                        fmtDur(m.duration),
                        Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        fontSize = 11.sp, color = Color.White,
                    )
                }
            }
            if (m.caption.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(m.caption, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        MsgKind.AUDIO, MsgKind.VOICE -> {
            Row(
                Modifier.widthIn(min = 200.dp, max = 320.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { onOpen(m) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    Modifier.size(42.dp), shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PlayArrow, "Play",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        m.body.ifBlank { if (m.kind == MsgKind.VOICE) "Voice message" else "Audio" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                    )
                    Text(
                        listOf(
                            if (m.duration > 0) fmtDur(m.duration) else "",
                            if (m.size > 0) fmtSize(m.size) else "",
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { m.toMedia()?.let(onDownload) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Download, "Download",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        MsgKind.DOCUMENT, MsgKind.STICKER -> {
            if (m.kind == MsgKind.STICKER && m.thumbId == 0 && m.fileId == 0) {
                Text(m.stickerEmoji.ifBlank { "Sticker" }, fontSize = 44.sp)
            } else if (m.kind == MsgKind.STICKER) {
                TgImage(
                    m.fileId, null,
                    Modifier.width(140.dp).aspectRatio(
                        if (m.width > 0 && m.height > 0) m.width.toFloat() / m.height else 1f,
                    ).clickable { onOpen(m) },
                )
            } else {
                Row(
                    Modifier.widthIn(min = 210.dp, max = 330.dp).clip(RoundedCornerShape(10.dp))
                        .clickable { onOpen(m) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val docKind = remember(m.fileName, m.mime) {
                        Tg.kindFor(m.fileName, m.mime, Kind.OTHER)
                    }
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(kindIcon(docKind), fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.fileName.ifBlank { "File" }, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                        )
                        Text(
                            listOf(
                                if (m.size > 0) fmtSize(m.size) else "",
                                m.mime.substringAfterLast('/').uppercase().take(6),
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { m.toMedia()?.let(onDownload) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.Download, "Download", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (m.caption.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(m.caption, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        MsgKind.LOCATION, MsgKind.VENUE -> {
            Column(
                Modifier.widthIn(min = 200.dp, max = 300.dp).clip(RoundedCornerShape(10.dp))
                    .clickable {
                        val uri = Uri.parse("geo:${m.lat},${m.lon}?q=${m.lat},${m.lon}")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }.padding(vertical = 2.dp),
            ) {
                Text("📍 ${m.placeName.ifBlank { "Location" }}", fontSize = 14.sp, maxLines = 2)
                if (m.address.isNotBlank()) {
                    Text(
                        m.address, fontSize = 12.sp, maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "%.5f, %.5f".format(m.lat, m.lon), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        MsgKind.CONTACT -> {
            Column(
                Modifier.widthIn(min = 190.dp, max = 290.dp).clip(RoundedCornerShape(10.dp))
                    .clickable {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${m.contactPhone}")))
                        }
                    }.padding(vertical = 2.dp),
            ) {
                Text("👤 ${m.contactName.ifBlank { "Contact" }}", fontSize = 14.sp, maxLines = 1)
                Text(
                    m.contactPhone, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        MsgKind.POLL -> {
            Column(Modifier.widthIn(min = 220.dp, max = 330.dp)) {
                Text(
                    (if (m.pollIsQuiz) "Quiz: " else "Poll: ") + m.pollQuestion,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 4,
                )
                Spacer(Modifier.height(5.dp))
                m.pollOptions.forEach { o ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (o.chosen) "☑ " else "☐ ", fontSize = 14.sp)
                            Text(
                                o.text, fontSize = 14.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${o.pct}%", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth().height(3.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(o.pct.coerceIn(0, 100) / 100f).height(3.dp)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
                Text(
                    "${m.pollVoters} voted${if (m.pollClosed) " · closed" else ""}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        MsgKind.DICE -> Text("${m.diceEmoji} ${m.diceValue}".trim(), fontSize = 28.sp)

        MsgKind.CALL -> Text(
            "📞 ${m.serviceText.ifBlank { "Call" }}" +
                if (m.duration > 0) " · ${fmtDur(m.duration)}" else "",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MsgKind.SERVICE -> {}
    }
}

/** A media album: sibling messages sharing a `mediaAlbumId`, rendered as one grid bubble. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumBubble(
    a: Album,
    chat: ChatRow,
    onOpen: (Msg) -> Unit,
    onLongPress: (Msg) -> Unit,
) {
    val out = a.head.outgoing
    val shape = RoundedCornerShape(
        topStart = if (out) 16.dp else 5.dp,
        topEnd = if (out) 5.dp else 16.dp,
        bottomStart = 16.dp, bottomEnd = 16.dp,
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = if (out) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!out && chat.isGroup) {
            Avatar(a.head.senderName.ifBlank { "?" }, chat.photoId, 30)
            Spacer(Modifier.width(6.dp))
        }
        Surface(
            shape = shape,
            color = if (out) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(6.dp)) {
                // Sized exactly to its content: a nested vertical scroller inside the message list
                // would crash, and clipping would hide album items. The list itself scrolls.
                val cols = 2
                val rowsN = (a.msgs.size + cols - 1) / cols
                val cell = 150.dp
                val gap = 3.dp
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    modifier = Modifier
                        .width(cell * cols + gap * (cols - 1))
                        .height(cell * rowsN + gap * (rowsN - 1).coerceAtLeast(0)),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    userScrollEnabled = false,
                ) {
                    gridItems(a.msgs, key = { it.key }) { m ->
                        Box(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { onOpen(m) },
                                    onLongClick = { onLongPress(m) },
                                ),
                        ) {
                            TgImage(
                                fileId = if (m.thumbId != 0) m.thumbId else m.fileId,
                                mini = m.mini,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (m.kind == MsgKind.VIDEO && m.duration > 0) {
                                Text(
                                    fmtDur(m.duration),
                                    Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(horizontal = 4.dp),
                                    fontSize = 10.sp, color = Color.White,
                                )
                            }
                        }
                    }
                }
                if (a.head.caption.isNotBlank()) {
                    Text(
                        a.head.caption, fontSize = 15.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().padding(end = 4.dp), contentAlignment = Alignment.CenterEnd) {
                    Footer(a.head, chat)
                }
            }
        }
    }
}

/**
 * Copies a picked `content://` file into the app cache so TDLib (native code) can read a real
 * path. Returns (path, mime, display name); null when the file can't be read.
 */
private fun uploadCopy(ctx: Context, uri: Uri): Triple<String, String, String>? {
    val cr = ctx.contentResolver
    val mime = cr.getType(uri) ?: "*/*"
    var name = "upload_${System.currentTimeMillis()}"
    runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.takeIf { it.isNotBlank() }?.let { name = it }
            }
        }
    }
    val safe = name.replace(Regex("[^A-Za-z0-9._\\- ]"), "_").takeLast(80)
    val dir = File(ctx.cacheDir, "uploads").apply { mkdirs() }
    val out = File(dir, safe)
    val ok = runCatching {
        val input = cr.openInputStream(uri) ?: return@runCatching false
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        true
    }.getOrDefault(false)
    return if (ok && out.length() > 0) Triple(out.absolutePath, mime, safe) else null
}
