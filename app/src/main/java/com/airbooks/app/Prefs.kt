package com.airbooks.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.airbooks.app.stream.StreamingMode

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

    // ── Streaming ──
    /** User's chosen default mode (Settings). */
    var streamingMode by mutableStateOf(StreamingMode.NORMAL)
        private set
    /** When true, opening the player uses the last mode chosen in the player instead. */
    var rememberLastMode by mutableStateOf(true)
        private set
    var lastMode by mutableStateOf(StreamingMode.NORMAL)
        private set
    var wifiOnlyPrefetch by mutableStateOf(false)
        private set
    var mobileDataWarning by mutableStateOf(true)
        private set
    var suspendThumbsLowData by mutableStateOf(true)
        private set
    var maxCacheMb by mutableIntStateOf(512)
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("airbooks", Context.MODE_PRIVATE)
        prefetchMb = sp.getInt("prefetchMb", 16)
        themeMode = sp.getString("themeMode", "System Default") ?: "System Default"
        playerEngine = sp.getString("playerEngine", "ExoPlayer") ?: "ExoPlayer"
        videoDecoder = sp.getString("videoDecoder", "Auto (Recommended)") ?: "Auto (Recommended)"
        autoThumbs = sp.getBoolean("autoThumbs", true)
        playbackSpeed = sp.getFloat("playbackSpeed", 1f)
        streamingMode = modeOf(sp.getString("streamMode", null))
        rememberLastMode = sp.getBoolean("rememberLastMode", true)
        lastMode = modeOf(sp.getString("lastStreamMode", null))
        wifiOnlyPrefetch = sp.getBoolean("wifiOnlyPrefetch", false)
        mobileDataWarning = sp.getBoolean("mobileDataWarning", true)
        suspendThumbsLowData = sp.getBoolean("suspendThumbsLowData", true)
        maxCacheMb = sp.getInt("maxCacheMb", 512)
    }

    private fun modeOf(v: String?): StreamingMode =
        runCatching { StreamingMode.valueOf(v ?: "") }.getOrDefault(StreamingMode.NORMAL)

    /** The mode a new playback session should start in. */
    fun effectiveMode(): StreamingMode = if (rememberLastMode) lastMode else streamingMode

    fun setDefaultMode(m: StreamingMode) {
        streamingMode = m
        sp.edit().putString("streamMode", m.name).apply()
        if (!rememberLastMode) lastMode = m
    }

    /** Called when the user picks a mode in the player: always remembered as last-used. */
    fun setActiveMode(m: StreamingMode) {
        lastMode = m
        sp.edit().putString("lastStreamMode", m.name).apply()
    }

    fun setRememberLastMode(v: Boolean) {
        rememberLastMode = v
        sp.edit().putBoolean("rememberLastMode", v).apply()
        if (!v) lastMode = streamingMode
    }

    fun setWifiOnlyPrefetch(v: Boolean) { wifiOnlyPrefetch = v; sp.edit().putBoolean("wifiOnlyPrefetch", v).apply() }
    fun setMobileDataWarning(v: Boolean) { mobileDataWarning = v; sp.edit().putBoolean("mobileDataWarning", v).apply() }
    fun setSuspendThumbsLowData(v: Boolean) { suspendThumbsLowData = v; sp.edit().putBoolean("suspendThumbsLowData", v).apply() }
    fun setMaxCacheMb(v: Int) { maxCacheMb = v; sp.edit().putInt("maxCacheMb", v).apply() }
    fun setPrefetch(mb: Int) {
        prefetchMb = mb
        sp.edit().putInt("prefetchMb", mb).apply()
    }
    fun updateAutoThumbs(v: Boolean) {
        autoThumbs = v
        sp.edit().putBoolean("autoThumbs", v).apply()
    }
    fun updatePlaybackSpeed(value: Float) { playbackSpeed = value; sp.edit().putFloat("playbackSpeed", value).apply() }
    fun updatePlayerEngine(value: String) { playerEngine = value; sp.edit().putString("playerEngine", value).apply() }
    fun updateVideoDecoder(value: String) { videoDecoder = value; sp.edit().putString("videoDecoder", value).apply() }
    fun setTheme(mode: String) {
        themeMode = mode
        sp.edit().putString("themeMode", mode).apply()
    }
    fun getPos(key: String): Long = sp.getLong("pos_$key", 0L)
    fun setPos(key: String, ms: Long) = sp.edit().putLong("pos_$key", ms).apply()
}
