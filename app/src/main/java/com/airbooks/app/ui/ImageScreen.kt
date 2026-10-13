package com.airbooks.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Tg
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageScreen(items: List<Media>, start: Media, onBack: () -> Unit) {
    val startIdx = items.indexOfFirst { it.key == start.key }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startIdx) { items.size }
    var hd by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { i ->
            ImagePage(items[i], hd, active = pager.currentPage == i)
        }
        Row(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text("${pager.currentPage + 1} / ${items.size}", color = Color.White)
        }
        TextButton(
            onClick = { hd = !hd },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
        ) { Text(if (hd) "Full quality ✓" else "Load full quality", color = Color.White) }
    }
}

@Composable
private fun ImagePage(m: Media, hd: Boolean, active: Boolean) {
    val id = if (hd) m.hdFileId else m.fileId
    var progress by remember(id) { mutableFloatStateOf(0f) }
    val path by produceState<String?>(Tg.completedPath(id), id, active) {
        if (active) value = Tg.awaitDownload(id, 16) { progress = it }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Tiny inline preview and the grid thumbnail are shown first, so something appears immediately.
        TgImage(m.thumbId, m.mini, Modifier.fillMaxSize(), scale = ContentScale.Fit)
        path?.let { AsyncImage(File(it), m.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (path == null && active) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
            )
        }
        if (m.caption.isNotBlank() && path != null) {
            Column(Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(12.dp)) {
                Text(m.caption.take(300), color = Color.White)
            }
        }
    }
}
