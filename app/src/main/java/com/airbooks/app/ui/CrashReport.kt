package com.airbooks.app.ui

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbooks.app.AirBooksApp
import com.airbooks.app.BuildConfig
import java.io.File

/** The trace written by [AirBooksApp]'s uncaught-exception handler, if the last run crashed. */
fun crashReportFile(ctx: Context): File = File(ctx.cacheDir, AirBooksApp.CRASH_FILE)

/**
 * Shows the stack trace of the previous run's crash, if any, as a selectable dialog so it can be
 * screenshotted and reported. "Dismiss" deletes the report; closing it any other way keeps it for
 * the next launch.
 */
@Composable
fun CrashReportDialog() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val f = crashReportFile(ctx)
        if (f.exists() && f.length() > 0L) text = runCatching { f.readText() }.getOrNull()
    }
    val report = text ?: return
    AlertDialog(
        onDismissRequest = { text = null },
        title = { Text("AirGram crashed last run") },
        text = {
            SelectionContainer {
                Text(
                    // Distinguish a stored report written by an older build ("AirGram 1.0 crash
                    // at …") from the build currently installed, so stale traces are obvious.
                    "Installed now: AirGram ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n" +
                        report.take(6000),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { crashReportFile(ctx).delete() }
                text = null
            }) { Text("Dismiss") }
        },
        dismissButton = {
            TextButton(onClick = { text = null }) { Text("Keep") }
        },
    )
}
