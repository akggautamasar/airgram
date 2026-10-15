package com.airbooks.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Tg
import java.io.File
import java.text.DateFormat
import java.util.Date

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

fun fmtDur(s: Int): String {
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun fmtDate(unixSeconds: Int): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unixSeconds * 1000L))

fun kindIcon(k: Kind): String = when (k) {
    Kind.VIDEO -> "🎬"
    Kind.AUDIO -> "🎵"
    Kind.PDF -> "📄"
    Kind.IMAGE -> "🖼"
    Kind.OTHER -> "📎"
    Kind.LINK -> "🔗"
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Local path of a Telegram file once downloaded (small files only: thumbnails, avatars). */
@Composable
fun rememberFilePath(fileId: Int, enabled: Boolean = true, priority: Int = 1): State<String?> =
    produceState<String?>(initialValue = if (fileId != 0) Tg.completedPath(fileId) else null, fileId, enabled) {
        if (fileId == 0 || !enabled) return@produceState
        try {
            value = Tg.awaitDownload(fileId, priority)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

/** Shows the ~300-byte inline preview instantly, then fades in the real thumbnail when it arrives. */
@Composable
fun TgImage(
    fileId: Int,
    mini: ByteArray?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scale: ContentScale = ContentScale.Crop,
) {
    val miniBmp = remember(mini) {
        mini?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val path by rememberFilePath(fileId, enabled)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (miniBmp != null) {
            Image(miniBmp, null, Modifier.fillMaxSize(), contentScale = scale)
        }
        path?.let { AsyncImage(File(it), null, Modifier.fillMaxSize(), contentScale = scale) }
    }
}

@Composable
fun Avatar(title: String, fileId: Int, size: Int = 46) {
    val path by rememberFilePath(fileId)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val p = path
        if (p != null) {
            AsyncImage(File(p), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(title.trim().take(1).uppercase(), fontSize = (size / 2.4).sp)
        }
    }
}

@Composable
fun ConnectionBanner() {
    val s by Tg.connection.collectAsState()
    if (s.isNotEmpty()) {
        Text(
            s,
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(6.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
        )
    }
}
