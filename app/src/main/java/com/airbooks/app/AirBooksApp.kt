package com.airbooks.app

import android.app.Application
import com.airbooks.app.tg.Tg
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AirBooksApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashReport()
        Prefs.init(this)
        Tg.init(this)
        com.airbooks.app.download.DownloadManager.init(this)
        // Bound the temporary streaming cache to the user's cap (best effort, off the main thread).
        CoroutineScope(Dispatchers.IO).launch {
            delay(10_000)
            runCatching { Tg.enforceCacheLimit(Prefs.maxCacheMb * 1024L * 1024L) }
        }
    }

    /**
     * Writes the stack trace of any uncaught exception to `cacheDir/last_crash.txt` before the
     * process dies, so the next launch can show it (see ui/CrashReport.kt). Samsung/Android only
     * reports "this app has a bug" with no detail; this makes crashes diagnosable from a
     * screenshot, without any backend or logcat.
     */
    private fun installCrashReport() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(cacheDir, CRASH_FILE).writeText(
                    "AirGram ${BuildConfig.VERSION_NAME} crash at ${Date()}\n" +
                        "thread=${thread.name}\n" +
                        "android=${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})\n" +
                        sw.toString(),
                )
            } catch (_: Throwable) {
                // Never mask the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
