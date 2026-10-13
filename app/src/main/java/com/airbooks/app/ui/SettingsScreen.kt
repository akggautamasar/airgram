package com.airbooks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
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
    var dialog by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    var hideLabels by remember { mutableStateOf(false) }
    var playback by remember { mutableStateOf("ExoPlayer") }
    var decoder by remember { mutableStateOf("Auto (Recommended)") }
    var theme by remember { mutableStateOf("System Default") }

    LaunchedEffect(refresh) {
        cacheBytes = withContext(Dispatchers.IO) {
            Tg.filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
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
            SectionTitle("Community")
            SettingsGroup {
                SettingRow(Icons.Filled.Chat, "Join Community", "Get help and discuss features") { dialog = "Community" }
            }
            SectionTitle("Data & Storage")
            SettingsGroup {
                SettingRow(Icons.Filled.Folder, "Storage Usage", "${fmtSize(cacheBytes)} used") { dialog = "Storage Usage" }
                SettingRow(Icons.Filled.Refresh, "Clear Cache", "Free up storage space") {
                    scope.launch {
                        withContext(Dispatchers.IO) { Tg.filesDir.deleteRecursively() }
                        Tg.dropFileCache()
                        refresh++
                    }
                }
                SettingRow(Icons.Filled.Download, "Export Database", "Experimental") { dialog = "Export Database" }
                SettingRow(Icons.Filled.Upload, "Import Database", "Experimental") { dialog = "Import Database" }
                SettingRow(Icons.Filled.Delete, "Reset Database", "Experimental", destructive = true) { dialog = "Reset Database" }
            }
            SectionTitle("App Preferences")
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingIcon(Icons.Filled.VisibilityOff)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) { Text("Hide Navigation Labels") }
                    Switch(checked = hideLabels, onCheckedChange = { hideLabels = it })
                }
                SettingRow(Icons.Filled.Settings, "Default Video Decoder", decoder) { dialog = "Default Video Decoder" }
                SettingRow(Icons.Filled.PlayArrow, "Default Player Engine", playback) { dialog = "Default Player Engine" }
                SettingRow(Icons.Filled.Palette, "App Theme", theme) { dialog = "App Theme" }
            }
            SectionTitle("Legal")
            SettingsGroup {
                SettingRow(Icons.Filled.Security, "Privacy Policy") { dialog = "Privacy Policy" }
                SettingRow(Icons.Filled.Settings, "Terms & Conditions") { dialog = "Terms & Conditions" }
            }
            SectionTitle("About")
            SettingsGroup { SettingRow(Icons.Filled.Web, "Visit Website") { dialog = "Website" } }
            SectionTitle("Account & Security")
            SettingsGroup {
                SettingRow(Icons.Filled.Refresh, "Restore Purchase", "Re-verify premium status manually") { dialog = "Restore Purchase" }
                SettingRow(Icons.Filled.Delete, "Log Out / Switch Account", destructive = true) { confirmLogout = true }
            }
            Text("AirGram • Storage: ${fmtSize(cacheBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        }
    }

    if (dialog.isNotEmpty()) {
        val choices = when (dialog) {
            "Default Player Engine" -> listOf("ExoPlayer", "VLC Engine")
            "Default Video Decoder" -> listOf("Auto (Recommended)", "Hardware", "Software")
            "App Theme" -> listOf("System Default", "Light", "Dark")
            else -> emptyList()
        }
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(dialog) },
            text = {
                if (choices.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { choice ->
                        val selected = when (dialog) {
                            "Default Player Engine" -> playback == choice
                            "Default Video Decoder" -> decoder == choice
                            else -> theme == choice
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                when (dialog) {
                                    "Default Player Engine" -> playback = choice
                                    "Default Video Decoder" -> decoder = choice
                                    "App Theme" -> theme = choice
                                }
                                dialog = ""
                            },
                            colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(choice, style = MaterialTheme.typography.titleMedium)
                                if (dialog == "Default Player Engine") Text(
                                    if (choice == "ExoPlayer") "Fast, hardware-accelerated. Best for most files." else "Universal playback for more codecs.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (selected) Text("✓ Selected", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else Text(when (dialog) {
                    "Clear Cache" -> "Cached thumbnails and media files are removed."
                    "Storage Usage" -> "${fmtSize(cacheBytes)} currently used by local Telegram files."
                    "Reset Database" -> "Database reset is experimental and is not enabled in this build."
                    "Export Database", "Import Database" -> "Database transfer is experimental and is not enabled in this build."
                    else -> "This option is not configured yet."
                })
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
        )
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
private fun SectionTitle(title: String) {
    Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) { content() }
}

@Composable
private fun SettingIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false) {
    BoxIcon(icon, destructive)
}

@Composable
private fun BoxIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(17.dp))
            .background(if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer) }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIcon(icon, destructive)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
