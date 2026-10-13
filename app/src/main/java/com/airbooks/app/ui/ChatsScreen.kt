package com.airbooks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.airbooks.app.tg.ChatRow
import com.airbooks.app.tg.Tg

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(onOpen: (ChatRow) -> Unit, onSettings: () -> Unit) {
    val chats by Tg.chats.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var selectedNav by remember { mutableStateOf("Chats") }
    val shown = remember(chats, q) {
        if (q.isBlank()) chats else chats.filter { it.title.contains(q.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) TextField(
                        value = q, onValueChange = { q = it }, placeholder = { Text("Search chats") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                    ) else Text(if (selectedNav == "Discover") "Discover" else if (selectedNav == "Search") "Search" else "Chats")
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
                NavigationBarItem(selected = selectedNav == "Discover", onClick = { selectedNav = "Discover" }, icon = { Icon(Icons.Filled.Explore, null) }, label = { Text("Discover") })
                NavigationBarItem(selected = selectedNav == "Chats", onClick = { selectedNav = "Chats" }, icon = { Icon(Icons.Filled.Chat, null) }, label = { Text("Chats") })
                NavigationBarItem(selected = selectedNav == "Search", onClick = { selectedNav = "Search"; searching = true }, icon = { Icon(Icons.Filled.Search, null) }, label = { Text("Search") })
                NavigationBarItem(selected = false, onClick = onSettings, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            ConnectionBanner()
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (chats.isEmpty()) "Loading your chats…" else "No chats match", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
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
