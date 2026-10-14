package com.airbooks.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.airbooks.app.bench.Bench
import com.airbooks.app.download.DownloadManager
import com.airbooks.app.download.DownloadScheduler
import com.airbooks.app.download.DownloadScheduler.JobState
import java.io.File
import kotlinx.coroutines.launch

/**
 * Downloads destination: active / pending / completed / failed with per-file speed, percentage,
 * bytes, size and ETA, aggregate throughput, profile control, storage usage and duplicate
 * detection, plus pause/resume/cancel/retry/boost and open/share/save/delete on finished files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val jobs by DownloadManager.ui.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val active = jobs.filter { it.state == JobState.ACTIVE }
    val pending = jobs.filter { it.state == JobState.QUEUED || it.state == JobState.PAUSED }
    val done = jobs.filter { it.state == JobState.COMPLETED }
    val failed = jobs.filter { it.state == JobState.FAILED }
    val dups = DownloadManager.duplicateNames()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Aggregate %.2f MB/s · %d active · slot count %d (%s)"
                            .format(DownloadManager.aggregateBps / 8_000_000.0, active.size,
                                DownloadManager.scheduler.concurrency, DownloadManager.scheduler.profile.label),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Storage ${fmtSize(DownloadManager.storageUsageBytes())}" +
                            if (dups.isNotEmpty()) " · possible duplicates: ${dups.size}" else "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        DownloadScheduler.Profile.values().forEach { p ->
                            FilterChip(
                                selected = DownloadManager.scheduler.profile == p,
                                onClick = { DownloadManager.setProfile(p) },
                                label = { Text(p.label, fontSize = 11.sp) },
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        listOf("single", "sweep-2", "sweep-4", "sweep-6", "contention").forEach { tag ->
                            FilterChip(
                                selected = Bench.scenario == tag,
                                onClick = { Bench.scenario = tag },
                                label = { Text(tag, fontSize = 10.sp) },
                            )
                        }
                        TextButton(onClick = {
                            val f = Bench.exportCsv()
                            if (f != null) {
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                                runCatching {
                                    ctx.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/csv"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                            "Share benchmark CSV",
                                        ),
                                    )
                                }
                            }
                        }) { Text("Export CSV", fontSize = 12.sp) }
                    }
                }
            }
            section("Active", active)
            section("Pending", pending)
            section("Completed", done)
            section("Failed", failed)
            if (jobs.isEmpty()) {
                item {
                    Text(
                        "No downloads yet. Open a chat's files and tap the download icon.",
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    jobs: List<DownloadScheduler.DownloadJob>,
) {
    if (jobs.isEmpty()) return
    item { Text(title, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall) }
    items(jobs, key = { it.fileId }) { j -> JobRow(j) }
}

@Composable
private fun JobRow(j: DownloadScheduler.DownloadJob) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(j.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = when (j.state) {
            JobState.ACTIVE, JobState.QUEUED -> buildString {
                append("${(j.fraction * 100).toInt()}% · ${fmtSize(j.downloaded)} / ${fmtSize(j.size)}")
                if (j.state == JobState.ACTIVE && j.speedBps > 0) {
                    append(" · %.2f MB/s".format(j.speedBps / 8_000_000.0))
                    val eta = j.etaMs()
                    if (eta > 0) append(" · ${eta / 1000}s left")
                }
                if (j.state == JobState.PAUSED) append(" · paused")
            }
            JobState.COMPLETED -> "${fmtSize(j.size)} · saved${if (j.boosted) " · prioritized" else ""}"
            JobState.FAILED -> "failed: ${j.error ?: "unknown"} (attempt ${j.attempts})"
            JobState.CANCELLED -> "cancelled"
            else -> ""
        }
        Text(line, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (j.state == JobState.ACTIVE || j.state == JobState.QUEUED) {
                TextButton(onClick = { DownloadManager.pause(j.fileId) }) { Text("Pause", fontSize = 12.sp) }
            }
            if (j.state == JobState.PAUSED) {
                TextButton(onClick = { DownloadManager.resume(j.fileId) }) { Text("Resume", fontSize = 12.sp) }
            }
            if (j.state == JobState.FAILED) {
                TextButton(onClick = { DownloadManager.retry(j.fileId) }) { Text("Retry", fontSize = 12.sp) }
            }
            if (j.state != JobState.COMPLETED && j.state != JobState.CANCELLED) {
                TextButton(onClick = { DownloadManager.boost(j.fileId) }) { Text("Priority", fontSize = 12.sp) }
                TextButton(onClick = { DownloadManager.cancel(j.fileId) }) { Text("Cancel", fontSize = 12.sp) }
            }
            if (j.state == JobState.COMPLETED) {
                TextButton(onClick = {
                    scope.launch {
                        val f = DownloadManager.saveToDevice(j.fileId)
                        if (f != null) openOrShare(ctx, f, j.name, share = false)
                    }
                }) { Text("Open", fontSize = 12.sp) }
                TextButton(onClick = {
                    scope.launch {
                        val f = DownloadManager.saveToDevice(j.fileId)
                        if (f != null) openOrShare(ctx, f, j.name, share = true)
                    }
                }) { Text("Share", fontSize = 12.sp) }
                TextButton(onClick = {
                    DownloadManager.deleteExport(j.fileId)
                    DownloadManager.removeFinished(j.fileId)
                }) { Text("Delete", fontSize = 12.sp) }
            }
        }
    }
}

private fun openOrShare(ctx: android.content.Context, f: File, name: String, share: Boolean) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val mime = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "bin")) ?: "*/*"
    val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
        if (share) {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = mime
        } else {
            setDataAndType(uri, mime)
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        ctx.startActivity(if (share) Intent.createChooser(intent, "Share $name") else intent)
    }
}
