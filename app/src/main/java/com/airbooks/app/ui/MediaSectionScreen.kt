package com.airbooks.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.airbooks.app.Prefs
import com.airbooks.app.stream.StreamingMode
import com.airbooks.app.stream.StreamingRuntime
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Repo
import com.airbooks.app.tg.Section
import com.airbooks.app.tg.Tg
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Top-level media library across **every** chat: the Videos / Audio / Docs / Photos destinations.
 *
 * Same look, filters and download behaviour as [FilesScreen] (it reuses its grid, list and footer),
 * but backed by a global Telegram media search instead of a per-chat one, so no chat has to be
 * chosen first. Paging is incremental — Telegram returns one page at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSectionScreen(
    initial: Section = Section.VIDEOS,
    onOpen: (Media, List<Media>) -> Unit,
    onDownload: (Media) -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(initial) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var activeQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(450)
        activeQuery = query.trim()
    }

    val st = remember(section, activeQuery) { Repo.globalState(section, activeQuery) }
    val liveMode = if (StreamingRuntime.playbackActive) StreamingRuntime.activeMode else Prefs.effectiveMode()
    val lowDataSuspends = liveMode != StreamingMode.NORMAL &&
        Prefs.suspendThumbsLowData && StreamingRuntime.playbackActive
    val thumbsOn = Prefs.autoThumbs && !lowDataSuspends && !(Prefs.wifiOnlyPrefetch && Tg.isMetered())
    val scope = rememberCoroutineScope()
    fun more() {
        scope.launch { Repo.loadMoreGlobal(section, activeQuery, st) }
    }
    LaunchedEffect(st) {
        if (st.items.isEmpty() && !st.done) more()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("Search all ${section.label.lowercase()}") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Text(
                            section.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { grid = !grid }) {
                        Text(if (grid) "☰" else "▦", fontSize = 20.sp)
                    }
                    IconButton(onClick = { st.reset(); more() }) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            ConnectionBanner()
            TabRow(selectedTabIndex = section.ordinal) {
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
