package com.airbooks.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small settings store. Values are Compose state so the UI reacts instantly. */
object Prefs {
    private lateinit var sp: SharedPreferences

    /** How much of a video/audio file is requested ahead of the playhead (MB). */
    var prefetchMb by mutableIntStateOf(16)
        private set

    /** Auto-download small thumbnails for visible items. Tiny inline previews always show. */
    var autoThumbs by mutableStateOf(true)
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("airbooks", Context.MODE_PRIVATE)
        prefetchMb = sp.getInt("prefetchMb", 16)
        autoThumbs = sp.getBoolean("autoThumbs", true)
    }

    fun setPrefetch(mb: Int) {
        prefetchMb = mb
        sp.edit().putInt("prefetchMb", mb).apply()
    }

    fun setAutoThumbs(v: Boolean) {
        autoThumbs = v
        sp.edit().putBoolean("autoThumbs", v).apply()
    }

    fun getPos(key: String): Long = sp.getLong("pos_$key", 0L)
    fun setPos(key: String, ms: Long) = sp.edit().putLong("pos_$key", ms).apply()
}
