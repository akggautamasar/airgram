package com.airbooks.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small settings store. Values are Compose state so the UI reacts instantly. */
object Prefs {
    private lateinit var sp: SharedPreferences
    var prefetchMb by mutableIntStateOf(16)
        private set
    var themeMode by mutableStateOf("System Default")
        private set
    var playerEngine by mutableStateOf("ExoPlayer")
        private set
    var videoDecoder by mutableStateOf("Auto (Recommended)")
        private set
    var autoThumbs by mutableStateOf(true)
        private set
    var playbackSpeed by mutableFloatStateOf(1f)
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("airbooks", Context.MODE_PRIVATE)
        prefetchMb = sp.getInt("prefetchMb", 16)
        themeMode = sp.getString("themeMode", "System Default") ?: "System Default"
        playerEngine = sp.getString("playerEngine", "ExoPlayer") ?: "ExoPlayer"
        videoDecoder = sp.getString("videoDecoder", "Auto (Recommended)") ?: "Auto (Recommended)"
        autoThumbs = sp.getBoolean("autoThumbs", true)
        playbackSpeed = sp.getFloat("playbackSpeed", 1f)
    }
    fun setPrefetch(mb: Int) {
        prefetchMb = mb
        sp.edit().putInt("prefetchMb", mb).apply()
    }
    fun updateAutoThumbs(v: Boolean) {
        autoThumbs = v
        sp.edit().putBoolean("autoThumbs", v).apply()
    }
    fun setPlaybackSpeed(value: Float) { playbackSpeed = value; sp.edit().putFloat("playbackSpeed", value).apply() }
    fun setPlayerEngine(value: String) { playerEngine = value; sp.edit().putString("playerEngine", value).apply() }
    fun setVideoDecoder(value: String) { videoDecoder = value; sp.edit().putString("videoDecoder", value).apply() }
    fun setTheme(mode: String) {
        themeMode = mode
        sp.edit().putString("themeMode", mode).apply()
    }
    fun getPos(key: String): Long = sp.getLong("pos_$key", 0L)
    fun setPos(key: String, ms: Long) = sp.edit().putLong("pos_$key", ms).apply()
}
