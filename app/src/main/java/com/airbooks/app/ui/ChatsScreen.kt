package com.airbooks.app.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbooks.app.Prefs
import com.airbooks.app.home.Recent
import com.airbooks.app.tg.ChatRow
import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Tg
import com.airbooks.app.tg.chatTimeLabel
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * The chat list: every real Telegram conversation, newest activity first.
 *
 * Rows carry the actual last message (preview, sender in groups, time, unread count, pinned and
 * muted markers, delivery ticks and drafts). Nothing is fetched beyond chat metadata — history and
 * attachments load only when a conversation or its media library is opened.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    onOpen: (ChatRow) -> Unit,
    onOpenFiles: (ChatRow) -> Unit,
    onOpenMedia: (Media) -> Unit,
    onAccount: () -> Unit,
) {
    val lists by Tg.chatsByList.collectAsState()
    var selectedList by rememberSaveable { mutableStateOf("main") }
    var chatFilter by rememberSaveable { mutableStateOf("All") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    var menuChat by remember { mutableStateOf<ChatRow?>(null) }
    var moreAvailable by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val tz = remember { TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000 }

    // Folder ids -> human names (main/archive have fixed labels).
    var folderNames by remember { mutableStateOf(mapOf<String, String>()) }
    LaunchedEffect(lists.keys) {
        val ids = lists.keys.filter { it.startsWith("folder:") }
            .mapNotNull { it.removePrefix("folder:").toIntOrNull() }
        if (ids.isNotEmpty()) folderNames = ids.associate { it.toString() to Tg.folderName(it) }
    }
    LaunchedEffect(selectedList) { Tg.ensureList(selectedList) }

    val chats = lists[selectedList] ?: emptyList()
    val archiveCount = (lists["archive"] ?: emptyList()).size
    val shown = remember(chats, q, chatFilter) {
        chats.filter { c ->
            val needle = q.trim()
            val matchesQuery = needle.isBlank() ||
                c.title.contains(needle, ignoreCase = true) ||
                c.preview.contains(needle, ignoreCase = true)
            val matchesFilter = when (chatFilter) {
                "Private" -> c.subtitle == "Private chat" || c.subtitle == "Saved messages"
                "Groups" -> c.subtitle == "Group"
                "Channels" -> c.subtitle == "Channel"
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    // Load the next batch of chats when the list is scrolled near its end.
    val nearEnd by remember {
        derivedStateOf {
            val li = listState.layoutInfo
            li.totalItemsCount > 0 &&
                (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 6
        }
    }
    var loadingMore by remember { mutableStateOf(false) }
    LaunchedEffect(nearEnd, shown.size, selectedList) {
        if (nearEnd && selectedList == "main" && moreAvailable && q.isBlank() && !loadingMore) {
            loadingMore = true
            moreAvailable = Tg.loadMoreChats()
            loadingMore = false
        }
    }

    val recent = remember(Prefs.recentMedia) {
        Recent.decode(Prefs.recentMedia).filter { it.positionMs > 5_000 }
    }
    val now = remember { (System.currentTimeMillis() / 1000).toInt() }

    fun title(): String = when {
        searching -> "Search chats"
        selectedList == "main" -> "Chats"
        selectedList == "archive" -> "Archive"
        else -> folderNames[selectedList.removePrefix("folder:")] ?: Tg.listLabel(selectedList)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = q, onValueChange = { q = it },
                            placeholder = { Text("Search chats") }, singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                    } else Text(title())
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) q = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search chats")
                    }
                    IconButton(onClick = { grid = !grid }) {
                        Icon(
                            if (grid) Icons.Filled.ViewList else Icons.Filled.GridView,
                            if (grid) "List view" else "Grid view",
                        )
                    }
                    IconButton(onClick = onAccount) {
                        Icon(Icons.Filled.Person, "Account and settings")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            ConnectionBanner()
            // Chat lists: main + archive + user folders (only shown when there is more than one).
            if (lists.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lists.keys.sorted().forEach { key ->
                        val label = if (key.startsWith("folder:")) {
                            folderNames[key.removePrefix("folder:")] ?: "Folder"
                        } else Tg.listLabel(key)
                        FilterChip(
                            selected = selectedList == key,
                            onClick = { selectedList = key },
                            label = { Text(label) },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("All", "Private", "Groups", "Channels").forEach { filter ->
                    FilterChip(
                        selected = chatFilter == filter,
                        onClick = { chatFilter = filter },
                        label = { Text(filter) },
                    )
                }
            }

            if (grid) {
                Column(Modifier.fillMaxSize()) {
                    ChatGrid(
                        shown, onOpen, menuChat = { menuChat = it },
                        modifier = Modifier.weight(1f),
                    )
                    if (selectedList == "main" && (moreAvailable || loadingMore)) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Loading more chats…", fontSize = 13.sp)
                            } else {
                                TextButton(onClick = {
                                    scope.launch {
                                        loadingMore = true
                                        moreAvailable = Tg.loadMoreChats()
                                        loadingMore = false
                                    }
                                }) { Text("Load more chats") }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    // Continue watching / listening.
                    if (recent.isNotEmpty() && q.isBlank() && selectedList == "main") {
                        item(key = "continue") {
                            Column {
                                Text(
                                    "Continue",
                                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    lazyItems(recent, key = { it.key }) { e ->
                                        Surface(
                                            Modifier.width(150.dp).clip(RoundedCornerShape(14.dp))
                                                .clickable { onOpenMedia(Recent.toMedia(e)) },
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                        ) {
                                            Column(Modifier.padding(10.dp)) {
                                                Text(
                                                    (if (e.kind == Kind.AUDIO) "♪ " else "▶ ") + e.name,
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                    fontSize = 12.sp,
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    "at ${fmtDur((e.positionMs / 1000).toInt())}",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Archived chats entry.
                    if (archiveCount > 0 && selectedList != "archive" && q.isBlank()) {
                        item(key = "archive-entry") {
                            Row(
                                Modifier.fillMaxWidth().clickable { selectedList = "archive" }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Archive, "Archive",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(14.dp))
                                Text("Archived chats", Modifier.weight(1f))
                                Text(
                                    "$archiveCount", fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    lazyItems(shown, key = { it.id }) { c ->
                        ChatListItem(
                            c, tz, now,
                            onClick = { onOpen(c) },
                            onLongClick = { menuChat = c },
                            onOpenFiles = { onOpenFiles(c) },
                        )
                    }
                    item(key = "list-footer") {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            when {
                                shown.isEmpty() && chats.isEmpty() ->
                                    Text(
                                        "Loading your chats…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                shown.isEmpty() ->
                                    Text(
                                        "No chats match",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                loadingMore -> {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "Loading more chats…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                    )
                                }
                                selectedList == "main" && moreAvailable -> TextButton(onClick = {
                                    scope.launch {
                                        loadingMore = true
                                        moreAvailable = Tg.loadMoreChats()
                                        loadingMore = false
                                    }
                                }) { Text("Load more chats") }
                                else -> Text(
                                    "All chats loaded",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Long-press menu: pin / mute / read state / media library.
    menuChat?.let { c ->
        ChatMenu(
            chat = c,
            onDismiss = { menuChat = null },
            onOpen = { menuChat = null; onOpen(c) },
            onFiles = { menuChat = null; onOpenFiles(c) },
            onPin = { menuChat = null; Tg.togglePin(c.id, !c.pinned) },
            onMute = { menuChat = null; Tg.setMuted(c.id, !c.muted) },
            onUnread = {
                menuChat = null
                if (c.markedUnread || c.unread > 0) {
                    Tg.toggleUnread(c.id, false)
                    // Marking read needs the real last message id, which the chat row carries.
                    if (c.lastMsgId > 0) Tg.markRead(c.id, longArrayOf(c.lastMsgId))
                } else Tg.toggleUnread(c.id, true)
            },
        )
    }
}

@Composable
private fun ChatMenu(
    chat: ChatRow,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onFiles: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onUnread: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = {
            Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column {
                MenuRow("Open chat", onOpen)
                MenuRow("Media and files", onFiles)
                MenuRow(if (chat.pinned) "Unpin" else "Pin to top", onPin)
                MenuRow(if (chat.muted) "Unmute" else "Mute notifications", onMute)
                MenuRow(
                    if (chat.markedUnread || chat.unread > 0) "Mark as read" else "Mark as unread",
                    onUnread,
                )
            }
        },
    )
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        fontSize = 15.sp,
    )
}

/** One Telegram-style conversation row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListItem(
    c: ChatRow,
    tz: Int,
    now: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val prefix = when {
        c.saved && c.lastOutgoing -> ""
        c.lastOutgoing -> "You: "
        c.lastSender.isNotBlank() -> "${c.lastSender}: "
        else -> ""
    }
    val line = (prefix + c.preview).trim()
    val unreadShown = c.unread > 0 || c.markedUnread
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(c.title, c.photoId, 52)
            if (c.pinned) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PushPin, "Pinned", Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.title, Modifier.weight(1f, fill = false),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unreadShown) FontWeight.Bold else FontWeight.Medium,
                )
                if (c.muted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.NotificationsOff, "Muted", Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    chatTimeLabel(c.lastDate, tz, now), fontSize = 12.sp,
                    color = if (unreadShown) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.draft.isNotBlank()) {
                    Text(
                        "Draft: ", fontSize = 14.sp, color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    line.ifBlank { c.subtitle },
                    Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                when {
                    c.unread > 0 -> Text(
                        if (c.unread > 99) "99+" else "${c.unread}",
                        Modifier.clip(RoundedCornerShape(11.dp))
                            .background(
                                if (c.muted) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.primary,
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        fontSize = 12.sp,
                        color = if (c.muted) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onPrimary,
                    )
                    c.markedUnread -> Box(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                    c.lastOutgoing && c.ticks > 0 -> Text(
                        if (c.ticks == 2) "✓✓" else "✓", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        IconButton(onClick = onOpenFiles, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Folder, "Media and files", Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The familiar card grid, kept as an alternative view of the same real chat data. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatGrid(
    chats: List<ChatRow>,
    onOpen: (ChatRow) -> Unit,
    menuChat: (ChatRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chats.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(chats, key = { it.id }) { c ->
            Surface(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onOpen(c) },
                    onLongClick = { menuChat(c) },
                ),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 3.dp,
            ) {
                Column {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.94f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (c.photoId != 0) Avatar(c.title, c.photoId, 84)
                        else Text(c.title.take(1).uppercase(), fontSize = 36.sp)
                        if (c.unread > 0) {
                            Text(
                                if (c.unread > 99) "99+" else "${c.unread}",
                                color = MaterialTheme.colorScheme.onError,
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            c.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.size(7.dp))
                        Text(
                            (if (c.preview.isNotBlank()) c.preview else c.subtitle.ifBlank { "Chat" }).take(28),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
