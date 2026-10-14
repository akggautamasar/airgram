package com.airbooks.app

import android.app.Application
import com.airbooks.app.tg.Tg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AirBooksApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Tg.init(this)
        // Bound the temporary streaming cache to the user's cap (best effort, off the main thread).
        CoroutineScope(Dispatchers.IO).launch {
            delay(10_000)
            runCatching { Tg.enforceCacheLimit(Prefs.maxCacheMb * 1024L * 1024L) }
        }
    }
}
