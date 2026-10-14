package com.airbooks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import com.airbooks.app.bench.Bench
import com.airbooks.app.bench.Trial
import com.airbooks.app.download.DownloadManager
import com.airbooks.app.download.DownloadScheduler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbooks.app.BuildConfig
import com.airbooks.app.Prefs
import com.airbooks.app.stream.StreamingMode
import com.airbooks.app.stream.StreamingRuntime
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
    var dialog by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    var clearedMsg by remember { mutableStateOf<String?>(null) }
    val connection by Tg.connection.collectAsState()
    var netStats by remember { mutableStateOf<List<Triple<String, Long, Long>>>(emptyList()) }
    LaunchedEffect(dialog) {
        if (dialog == "Data Usage") netStats = runCatching { Tg.networkStats() }.getOrDefault(emptyList())
    }

    LaunchedEffect(refresh) {
        cacheBytes = withContext(Dispatchers.IO) { Tg.cacheBytes() }
    }

    fun clearTempCache() {
        scope.launch {
            val n = withContext(Dispatchers.IO) { Tg.clearStreamingCache() }
            // Bound the on-disk cache to the user's cap as well (oldest files first).
            withContext(Dispatchers.IO) { Tg.enforceCacheLimit(Prefs.maxCacheMb * 1024L * 1024L) }
            clearedMsg = "Removed $n cached file(s). Chats and login are untouched."
            refresh++
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("⚙ Settings") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Streaming ────────────────────────────────────────────────────────
            SectionTitle("Streaming")
            SettingsGroup {
                SettingRow(Icons.Filled.PlayArrow, "Default video mode", Prefs.streamingMode.label) {
                    dialog = "Default video mode"
                }
                SwitchRow("Remember last-used mode", "Start the player in the mode you last picked there", Prefs.rememberLastMode) {
                    Prefs.updateRememberLastMode(it)
                }
                SettingRow(Icons.Filled.PlayArrow, "Playback speed", "${Prefs.playbackSpeed}×") { dialog = "Playback Speed" }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Streaming read-ahead cap (Normal)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Prefs.prefetchMb} MB · Low Data mode always uses a smaller window",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(value = Prefs.prefetchMb.toFloat(), onValueChange = { Prefs.setPrefetch(it.toInt()) }, valueRange = 4f..64f, steps = 14)
                }
                SwitchRow("Wi-Fi-only prefetch", "No thumbnail/preview prefetching on mobile data (playback itself still streams)", Prefs.wifiOnlyPrefetch) {
                    Prefs.updateWifiOnlyPrefetch(it)
                }
                SwitchRow("Mobile-data warning", "Warn in the player when streaming on mobile data", Prefs.mobileDataWarning) {
                    Prefs.updateMobileDataWarning(it)
                }
                SwitchRow("Suspend thumbnails in Low Data", "While Low Data playback is active, stop thumbnail/preview work", Prefs.suspendThumbsLowData) {
                    Prefs.updateSuspendThumbsLowData(it)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Maximum temporary cache", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Prefs.maxCacheMb} MB · currently ${fmtSize(cacheBytes)} used",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(value = Prefs.maxCacheMb.toFloat(), onValueChange = { Prefs.updateMaxCacheMb(it.toInt()) }, valueRange = 128f..4096f, steps = 30)
                }
                SettingRow(Icons.Filled.Delete, "Clear temporary streaming cache", "Files Telegram re-downloads on demand; login/chats stay") {
                    clearTempCache()
                }
                SettingRow(Icons.Filled.Settings, "Streaming diagnostics", "Throughput, buffering and cache state") {
                    dialog = "Diagnostics"
                }
            }
            clearedMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            // ── Data & storage ───────────────────────────────────────────────────
            SectionTitle("Data & Storage")
            SettingsGroup {
                SettingRow(Icons.Filled.Settings, "Storage usage", "${fmtSize(cacheBytes)} used by cached Telegram files") { dialog = "Storage Usage" }
                SettingRow(Icons.Filled.Refresh, "Enforce cache limit now", "Delete oldest cached files above ${Prefs.maxCacheMb} MB") {
                    scope.launch {
                        withContext(Dispatchers.IO) { Tg.enforceCacheLimit(Prefs.maxCacheMb * 1024L * 1024L) }
                        refresh++
                    }
                }
            }

            // ── Downloads ────────────────────────────────────────────────────────
            SectionTitle("Downloads")
            SettingsGroup {
                SettingRow(Icons.Filled.Download, "Download profile", Prefs.downloadProfile) {
                    dialog = "Download Profile"
                }
                SwitchRow("Wi-Fi-only downloads", "The queue parks instead of using mobile data", Prefs.wifiOnlyDownloads) {
                    DownloadManager.setWifiOnly(it)
                }
                SettingRow(Icons.Filled.Download, "Data usage", "TDLib network statistics + benchmark medians") {
                    dialog = "Data Usage"
                }
            }

            // ── App preferences ──────────────────────────────────────────────────
            SectionTitle("App Preferences")
            SettingsGroup {
                SwitchRow("Load media thumbnails", "Turn off to save data in media lists", Prefs.autoThumbs) {
                    Prefs.updateAutoThumbs(it)
                }
                SettingRow(Icons.Filled.Settings, "App Theme", Prefs.themeMode) { dialog = "App Theme" }
            }

            // ── Account ──────────────────────────────────────────────────────────
            SectionTitle("Account")
            SettingsGroup {
                SettingRow(Icons.Filled.Delete, "Log Out / Switch Account", destructive = true) { confirmLogout = true }
            }
            Text(
                "AirGram ${BuildConfig.VERSION_NAME} · Streaming via Telegram/TDLib + Media3 ExoPlayer",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    when (dialog) {
        "Default video mode", "App Theme", "Playback Speed", "Download Profile" -> {
            val choices: List<String> = when (dialog) {
                "Default video mode" -> StreamingMode.values().map { it.label }
                "App Theme" -> listOf("System Default", "Light", "Dark")
                "Download Profile" -> DownloadScheduler.Profile.values().map { it.label }
                else -> listOf("0.5", "0.75", "1.0", "1.25", "1.5", "2.0")
            }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text(dialog) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        choices.forEach { choice ->
                            val selected = when (dialog) {
                                "Default video mode" -> Prefs.streamingMode.label == choice
                                "App Theme" -> Prefs.themeMode == choice
                                "Download Profile" -> Prefs.downloadProfile == choice
                                else -> Prefs.playbackSpeed == choice.toFloatOrNull()
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    when (dialog) {
                                        "Default video mode" -> Prefs.setDefaultMode(
                                            StreamingMode.values().first { it.label == choice }
                                        )
                                        "App Theme" -> Prefs.setTheme(choice)
                                        "Download Profile" -> DownloadManager.setProfile(
                                            DownloadScheduler.Profile.values().first { it.label == choice }
                                        )
                                        else -> Prefs.updatePlaybackSpeed(choice.toFloatOrNull() ?: 1f)
                                    }
                                    dialog = ""
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(choice, style = MaterialTheme.typography.titleMedium)
                                    if (dialog == "Default video mode") {
                                        Text(
                                            if (choice == StreamingMode.NORMAL.label) {
                                                "Original quality, larger read-ahead for smooth playback."
                                            } else {
                                                "Faster start, smaller read-ahead, less data on slow networks."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (selected) Text("✓ Selected", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
            )
        }
        "Data Usage" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Data usage") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (netStats.isEmpty()) Text("No statistics yet.")
                    netStats.forEach { (label, rx, tx) ->
                        Text("$label:  ↓ ${fmtSize(rx)}  ·  ↑ ${fmtSize(tx)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    val trials = Bench.all()
                    if (trials.isNotEmpty()) {
                        Text("Benchmark medians (sustained):", style = MaterialTheme.typography.titleSmall)
                        trials.groupBy { it.scenario }.forEach { (sc, ts) ->
                            Text(
                                "$sc: %.2f MB/s median over ${ts.size} trial(s), %.0f s median completion"
                                    .format(Trial.median(ts.map { it.sustainedMBps }), Trial.median(ts.map { it.completionMs.toDouble() }) / 1000),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
        )
        "Storage Usage" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Storage usage") },
            text = { Text("${fmtSize(cacheBytes)} currently used by local Telegram files (streaming cache). Limit: ${Prefs.maxCacheMb} MB.") },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
        )
        "Diagnostics" -> {
            val kbps = (StreamingRuntime.estimator.ewmaBps / 1000).toInt()
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Streaming diagnostics") },
                text = {
                    Text(
                        buildString {
                            appendLine("Active mode: ${StreamingRuntime.activeMode.label} (effective: ${Prefs.effectiveMode().label})")
                            appendLine("Playback active: ${StreamingRuntime.playbackActive}")
                            appendLine("Active file ID: ${StreamingRuntime.activeFileId}")
                            appendLine("Estimated throughput: ${if (kbps > 0) "$kbps kbps" else "no samples yet"} (${StreamingRuntime.estimator.sampleCount} samples)")
                            appendLine("Rebuffers this session: ${StreamingRuntime.rebufferCount}")
                            appendLine("Last streaming error: ${StreamingRuntime.lastError ?: "none"}")
                            appendLine("Telegram connection: ${connection.ifBlank { "connected" }}")
                            appendLine("Metered connection: ${Tg.isMetered()}")
                            appendLine("Streaming cache: ${fmtSize(cacheBytes)} / ${Prefs.maxCacheMb} MB")
                            appendLine("Cache folder: ${Tg.filesDir.absolutePath}")
                            append("App: AirGram ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        }
                    )
                },
                confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
            )
        }
    }

    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("Log out?") },
        text = { Text("This ends the Telegram session on this phone.") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; Tg.logout() }) { Text("Log out") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxIcon(Icons.Filled.Settings)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) { content() }
}

@Composable
private fun BoxIcon(icon: ImageVector, destructive: Boolean = false) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(17.dp))
            .background(if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer) }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxIcon(icon, destructive)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
