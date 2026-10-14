package com.airbooks.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Tg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

fun openExternal(ctx: Context, path: String, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(path))
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

/** Downloads (with progress) then renders page-by-page with Android's built-in PdfRenderer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(item: Media, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    var path by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.fileId) { path = Tg.awaitDownload(item.fileId, 32) { progress = it } }

    val renderer by produceState<PdfRenderer?>(null, path) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) {
                try {
                    PdfRenderer(ParcelFileDescriptor.open(File(p), ParcelFileDescriptor.MODE_READ_ONLY))
                } catch (e: Exception) {
                    error = e.message ?: "Could not open this PDF"
                    null
                }
            }
        }
    }
    DisposableEffect(renderer) {
        val r = renderer
        onDispose { try { r?.close() } catch (_: Exception) {} }
    }

    val widthPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp.roundToPx() * 1.4f).toInt() }
    val lock = remember { Mutex() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            val r = renderer
            when {
                r != null -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((0 until r.pageCount).toList()) { i -> PdfPage(r, i, widthPx, lock) }
                }
                error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    path?.let { p ->
                        Button(onClick = { openExternal(ctx, p, "application/pdf") }, modifier = Modifier.padding(top = 12.dp)) {
                            Text("Open with another app")
                        }
                    }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text("Downloading ${fmtSize(item.size)}…")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.padding(top = 12.dp).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PdfPage(r: PdfRenderer, index: Int, widthPx: Int, lock: Mutex) {
    val bmp by produceState<Bitmap?>(null, index) {
        value = withContext(Dispatchers.Default) {
            lock.withLock {
                try {
                    val page = r.openPage(index)
                    try {
                        val h = (widthPx.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(android.graphics.Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    } finally {
                        page.close()
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color(0xFF222222)))
    }
}

/** Any other file type: download with progress, then hand it to whatever app can open it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherScreen(item: Media, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    var path by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(item.fileId, started) {
        if (started) path = Tg.awaitDownload(item.fileId, 32) { progress = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            Text("${fmtSize(item.size)} · ${item.mime.ifBlank { "unknown type" }}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "This format can't be streamed in-app, so it is downloaded first (unlike videos/audio, which stream). The file stays in AirGram's cache, not your Downloads folder.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            when {
                path != null -> Button(onClick = { openExternal(ctx, path!!, item.mime) }) { Text("Open with…") }
                started -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else -> Button(onClick = { started = true }) { Text("Download (${fmtSize(item.size)})") }
            }
        }
    }
}
