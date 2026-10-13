package com.airbooks.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airbooks.app.Prefs
import com.airbooks.app.tg.Tg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        cacheBytes = withContext(Dispatchers.IO) {
            Tg.filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Streaming prefetch", style = MaterialTheme.typography.titleMedium)
            Text(
                "How much of a video or song is requested ahead of where you are. " +
                    "Lower uses less data if you often skip around; higher rides out a slow, bumpy connection.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            listOf(4 to "Low data (4 MB)", 16 to "Balanced (16 MB)", 48 to "Smooth on slow links (48 MB)").forEach { (mb, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { Prefs.setPrefetch(mb) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = Prefs.prefetchMb == mb, onClick = { Prefs.setPrefetch(mb) })
                    Text(label)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Load thumbnails", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Off = only the tiny built-in previews are shown (almost no data).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = Prefs.autoThumbs, onCheckedChange = { Prefs.setAutoThumbs(it) })
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text("Cache: ${fmtSize(cacheBytes)}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Watched parts and thumbnails are kept so they don't need to be fetched twice.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { Tg.filesDir.deleteRecursively() }
                    Tg.dropFileCache()
                    refresh++
                }
            }) { Text("Clear cache") }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Button(onClick = { confirmLogout = true }) { Text("Log out") }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("This ends the Telegram session on this phone and clears local data.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; Tg.logout() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}
