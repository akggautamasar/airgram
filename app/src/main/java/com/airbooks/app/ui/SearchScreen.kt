package com.airbooks.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbooks.app.tg.ChatRow
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Tg
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Global search destination: matching conversations plus real cross-chat message/media results
 * from Telegram's own `searchMessages` (paged — nothing is loaded up front).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenChat: (ChatRow) -> Unit,
    onOpenMedia: (Media, List<Media>) -> Unit,
) {
    var q by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Media>>(emptyList()) }
    var offset by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val lists by Tg.chatsByList.collectAsState()
    val chatHits = remember(lists, q) {
        val needle = q.trim()
        if (needle.isBlank()) emptyList()
        else lists.values.asSequence().flatten()
            .filter {
                it.title.contains(needle, ignoreCase = true) ||
                    it.preview.contains(needle, ignoreCase = true)
            }
            .take(20)
            .distinctBy { it.id }
            .toList()
    }

    LaunchedEffect(q) {
        if (q.isBlank()) {
            items = emptyList(); offset = ""; searched = false
            return@LaunchedEffect
        }
        delay(450)
        loading = true
        val r = runCatching { Tg.searchGlobal(q.trim(), "") }
        val (found, next) = r.getOrDefault(emptyList<Media>() to "")
        items = found
        offset = next
        loading = false
        searched = true
    }

    Scaffold(
        topBar = {
            TextField(
                value = q, onValueChange = { q = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Search messages and chats") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            ConnectionBanner()
            if (q.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Search across all your Telegram chats",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (chatHits.isNotEmpty()) {
                    item(key = "chats-header") {
                        Text(
                            "Chats", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    lazyItems(chatHits, key = { "c:${it.id}" }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenChat(c) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(c.title, c.photoId, 44)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    c.preview.ifBlank { c.subtitle }, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (c.unread > 0) {
                                Text("${c.unread}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                item(key = "results-header") {
                    Text(
                        "Messages and media",
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                lazyItems(items, key = { it.key }) { m ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onOpenMedia(m, items.filter { it.kind == m.kind }) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(42.dp)) {
                            TgImage(m.thumbId, m.mini, Modifier.fillMaxSize(), enabled = m.thumbId != 0)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(
                                    if (m.size > 0) fmtSize(m.size) else "",
                                    fmtDate(m.date),
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(kindIcon(m.kind), fontSize = 20.sp)
                    }
                }
                item(key = "results-footer") {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        when {
                            loading -> CircularProgressIndicator()
                            offset.isNotEmpty() -> Button(onClick = {
                                scope.launch {
                                    loading = true
                                    val r = runCatching { Tg.searchGlobal(q.trim(), offset) }
                                    val (more, next) = r.getOrDefault(emptyList<Media>() to "")
                                    val known = items.mapTo(HashSet()) { it.key }
                                    items = items + more.filter { it.key !in known }
                                    offset = next
                                    loading = false
                                }
                            }) { Text("More results") }
                            searched && items.isEmpty() && chatHits.isEmpty() ->
                                Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
