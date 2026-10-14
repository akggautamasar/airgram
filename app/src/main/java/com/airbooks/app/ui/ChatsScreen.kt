package com.airbooks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbooks.app.Prefs
import com.airbooks.app.home.Recent
import com.airbooks.app.tg.ChatRow
import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Tg
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onOpen: (ChatRow) -> Unit,
    onSettings: () -> Unit,
    onDownloads: () -> Unit,
    onOpenMedia: (Media) -> Unit,
) {
    val lists by Tg.chatsByList.collectAsState()
    var selectedList by remember { mutableStateOf("main") }
    var searching by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var selectedNav by remember { mutableStateOf("Chats") }
    var chatFilter by remember { mutableStateOf("All") }

    // Folder ids -> human names (archive/main are fixed labels).
    var folderNames by remember { mutableStateOf(mapOf<String, String>()) }
    LaunchedEffect(lists.keys) {
        val ids = lists.keys.filter { it.startsWith("folder:") }.mapNotNull { it.removePrefix("folder:").toIntOrNull() }
        if (ids.isNotEmpty()) folderNames = ids.associate { it.toString() to Tg.folderName(it) }
    }
    LaunchedEffect(selectedList) { Tg.ensureList(selectedList) }

    val chats = lists[selectedList] ?: emptyList()
    val shown = remember(chats, q, chatFilter, selectedNav) {
        chats.filter { c ->
            val matchesQuery = q.isBlank() || c.title.contains(q.trim(), ignoreCase = true)
            val matchesFilter = when (chatFilter) {
                "Private" -> c.subtitle == "Private chat" || c.subtitle == "Saved messages"
                "Groups" -> c.subtitle == "Group"
                "Channels" -> c.subtitle == "Channel"
                else -> true
            }
            matchesQuery && matchesFilter && (selectedNav != "Discover" || c.subtitle == "Channel")
        }
    }

    // Global (cross-chat) message search while on the Search tab with a query.
    var global by remember { mutableStateOf<List<Media>>(emptyList()) }
    var globalOffset by remember { mutableStateOf("") }
    var globalLoading by remember { mutableStateOf(false) }
    LaunchedEffect(q, selectedNav) {
        if (selectedNav != "Search" || q.isBlank()) { global = emptyList(); globalOffset = ""; return@LaunchedEffect }
        delay(450)
        globalLoading = true
        val (items, next) = runCatching { Tg.searchGlobal(q.trim(), "") }.getOrDefault(emptyList<Media>() to "")
        global = items
        globalOffset = next
        globalLoading = false
    }

    val recent = remember(Prefs.recentMedia) { Recent.decode(Prefs.recentMedia).filter { it.positionMs > 5_000 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) TextField(
                        value = q, onValueChange = { q = it },
                        placeholder = { Text(if (selectedNav == "Search") "Search messages everywhere" else "Search chats") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                    ) else Text(
                        when {
                            selectedNav == "Discover" -> "Discover"
                            selectedNav == "Search" -> "Search"
                            else -> lists.keys.let { if (selectedList == "main") "Chats" else folderNames[selectedList.removePrefix("folder:")] ?: Tg.listLabel(selectedList) }
                        },
                    )
                },
                actions = {
                    IconButton(onClick = { searching = !searching; selectedNav = if (searching) "Search" else "Chats"; if (!searching) q = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedNav == "Discover", onClick = { selectedNav = "Discover"; chatFilter = "All" }, icon = { Icon(Icons.Filled.Explore, null) }, label = { Text("Discover") })
                NavigationBarItem(selected = selectedNav == "Chats", onClick = { selectedNav = "Chats"; chatFilter = "All" }, icon = { Icon(Icons.Filled.Chat, null) }, label = { Text("Chats") })
                NavigationBarItem(selected = selectedNav == "Search", onClick = { selectedNav = "Search"; searching = true }, icon = { Icon(Icons.Filled.Search, null) }, label = { Text("Search") })
                NavigationBarItem(selected = false, onClick = onDownloads, icon = { Icon(Icons.Filled.Download, null) }, label = { Text("Downloads") })
                NavigationBarItem(selected = false, onClick = onSettings, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            ConnectionBanner()
            // Chat lists: main + archive + user folders (only shown when non-empty).
            if (lists.size > 1 && selectedNav != "Search") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lists.keys.sorted().forEach { key ->
                        val label = if (key.startsWith("folder:")) folderNames[key.removePrefix("folder:")] ?: "Folder" else Tg.listLabel(key)
                        FilterChip(selected = selectedList == key, onClick = { selectedList = key; selectedNav = "Chats" }, label = { Text(label) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Private", "Groups", "Channels").forEach { filter ->
                    FilterChip(selected = chatFilter == filter, onClick = { chatFilter = filter; if (filter != "All") selectedNav = "Chats" }, label = { Text(filter) })
                }
            }
            // Continue watching / listening rail.
            if (recent.isNotEmpty() && selectedNav != "Search" && q.isBlank()) {
                Text("Continue", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.titleSmall)
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    lazyItems(recent, key = { it.key }) { e ->
                        Surface(
                            Modifier.width150().clickable { onOpenMedia(Recent.toMedia(e)) },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text((if (e.kind == Kind.AUDIO) "♪ " else "▶ ") + e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("at ${fmtDur((e.positionMs / 1000).toInt())}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            when {
                selectedNav == "Search" && q.isNotBlank() -> GlobalResults(global, globalLoading, globalOffset, {
                    globalLoading = true
                    val (more, next) = runCatching { Tg.searchGlobal(q.trim(), globalOffset) }.getOrDefault(emptyList<Media>() to "")
                    global = global + more
                    globalOffset = next
                    globalLoading = false
                }, onOpenMedia)
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (lists.values.all { it.isEmpty() }) "Loading your chats…" else "No chats match", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it.id }) { c ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(c) },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shadowElevation = 3.dp,
                        ) {
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(0.94f).background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (c.photoId != 0) Avatar(c.title, c.photoId, 84) else Text(c.title.take(1).uppercase(), fontSize = 36.sp)
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
                                    Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.size(7.dp))
                                    Text(c.subtitle.ifBlank { "Chat" }.take(16), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 7.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.width150(): Modifier = this.then(androidx.compose.foundation.layout.width(150.dp))

@Composable
private fun GlobalResults(
    items: List<Media>,
    loading: Boolean,
    offset: String,
    onMore: () -> Unit,
    onOpen: (Media) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
            lazyItems(items, key = { it.key }) { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(m) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(kindIcon(m.kind), fontSize = 22.sp)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${fmtSize(m.size)} · ${fmtDate(m.date)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                    if (loading) CircularProgressIndicator()
                    else if (offset.isNotEmpty()) Button(onClick = onMore) { Text("More results") }
                    else if (items.isEmpty()) Text("No messages found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
