package com.airbooks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbooks.app.Prefs
import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.ListState
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Repo
import com.airbooks.app.stream.StreamingMode
import com.airbooks.app.stream.StreamingRuntime
import com.airbooks.app.tg.Section
import com.airbooks.app.tg.Tg
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    chatId: Long,
    title: String,
    onBack: () -> Unit,
    onOpen: (Media, List<Media>) -> Unit,
    onDownload: (Media) -> Unit = {},
) {
    var section by remember { mutableStateOf(Section.VIDEOS) }
    var grid by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var activeQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(450); activeQuery = query.trim() }

    val st = remember(chatId, section, activeQuery) { Repo.state(chatId, section, activeQuery) }
    // Low Data / Extreme Low Data playback suspends thumbnail/preview work; Wi-Fi-only prefetch blocks it on mobile data.
    val liveMode = if (StreamingRuntime.playbackActive) StreamingRuntime.activeMode else Prefs.effectiveMode()
    val lowDataSuspends = liveMode != StreamingMode.NORMAL &&
        Prefs.suspendThumbsLowData && StreamingRuntime.playbackActive
    val thumbsOn = Prefs.autoThumbs && !lowDataSuspends && !(Prefs.wifiOnlyPrefetch && Tg.isMetered())
    val scope = rememberCoroutineScope()
    fun more() { scope.launch { Repo.loadMore(chatId, section, activeQuery, st) } }
    LaunchedEffect(st) { if (st.items.isEmpty() && !st.done) more() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("Search in $title") }, singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                    } else Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { grid = !grid }) {
                        Text(if (grid) "☰" else "▦", fontSize = 20.sp)
                    }
                    IconButton(onClick = {
                        st.reset(); more()
                    }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            ConnectionBanner()
            // Six per-chat sections; scrollable so every label stays readable on narrow phones.
            ScrollableTabRow(
                selectedTabIndex = section.ordinal,
                edgePadding = 12.dp,
            ) {
                Section.values().forEach { s ->
                    Tab(selected = section == s, onClick = { section = s }, text = { Text(s.label) })
                }
            }
            if (grid) {
                MediaGrid(st, ::more, thumbsOn, onDownload) { onOpen(it, st.items.toList()) }
            } else {
                MediaList(st, ::more, thumbsOn, onDownload) { onOpen(it, st.items.toList()) }
            }
        }
    }
}

@Composable
internal fun Footer(st: ListState, onMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            st.loading -> CircularProgressIndicator()
            st.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(st.error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                TextButton(onClick = onMore) { Text("Retry") }
            }
            st.done && st.items.isEmpty() ->
                Text("Nothing here", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MediaGrid(st: ListState, onMore: () -> Unit, thumbsOn: Boolean, onDownload: (Media) -> Unit, onClick: (Media) -> Unit) {
    val state = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val li = state.layoutInfo
            li.totalItemsCount > 0 && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, st.items.size) { if (nearEnd) onMore() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(130.dp),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(st.items, key = { it.key }) { m ->
            Box(
                Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onClick(m) }
            ) {
                TgImage(m.thumbId, m.mini, Modifier.fillMaxSize(), enabled = thumbsOn)
                if (m.thumbId == 0 && m.mini == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(kindIcon(m.kind), fontSize = 30.sp)
                    }
                }
                if (m.kind != Kind.LINK) {
                    IconButton(
                        onClick = { onDownload(m) },
                        modifier = Modifier.align(Alignment.TopEnd).size(30.dp),
                    ) {
                        Icon(Icons.Filled.Download, "Download", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                if (m.kind == Kind.VIDEO) {
                    Text(
                        "▶ ${fmtDur(m.duration)}",
                        Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White, fontSize = 11.sp,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Footer(st, onMore) }
    }
}

@Composable
internal fun MediaList(st: ListState, onMore: () -> Unit, thumbsOn: Boolean, onDownload: (Media) -> Unit, onClick: (Media) -> Unit) {
    val state = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val li = state.layoutInfo
            li.totalItemsCount > 0 && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, st.items.size) { if (nearEnd) onMore() }

    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(st.items, key = { it.key }) { m ->
            Row(
                Modifier.fillMaxWidth().clickable { onClick(m) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (m.thumbId != 0) {
                    TgImage(m.thumbId, m.mini, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), enabled = thumbsOn)
                } else {
                    Text(kindIcon(m.kind), fontSize = 24.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = if (m.kind == Kind.LINK) {
                        m.fileName.ifBlank { m.name }
                    } else {
                        buildList {
                            add(fmtSize(m.size))
                            if (m.duration > 0) add(fmtDur(m.duration))
                            add(fmtDate(m.date))
                        }.joinToString(" · ")
                    }
                    Text(meta, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (m.caption.isNotBlank()) {
                        Text(m.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (m.kind != Kind.LINK) {
                    IconButton(onClick = { onDownload(m) }) {
                        Icon(Icons.Filled.Download, "Download")
                    }
                }
            }
        }
        item { Footer(st, onMore) }
    }
}
