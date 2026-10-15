package com.airbooks.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.content.pm.ActivityInfo
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.airbooks.app.Prefs
import com.airbooks.app.home.Recent
import com.airbooks.app.playback.PlaybackService
import com.airbooks.app.stream.StreamingMode
import com.airbooks.app.stream.StreamingRuntime
import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Tg
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay

private fun Media.toMediaItem(chatTitle: String): MediaItem {
    val isAudio = kind == Kind.AUDIO
    val title = if (isAudio && trackTitle.isNotBlank()) trackTitle else name
    val artist = when {
        isAudio && performer.isNotBlank() -> performer
        chatTitle.isNotBlank() -> chatTitle
        else -> "AirGram"
    }
    val meta = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setDisplayTitle(name)
        .apply {
            if (isAudio) setArtworkData(mini, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        .build()
    return MediaItem.Builder()
        .setUri("tg://file/$fileId")
        .setMediaId(key)
        .setMediaMetadata(meta)
        .build()
}

/**
 * Streaming player. Connects a MediaController to [PlaybackService] (which owns the only
 * ExoPlayer instance), so audio keeps playing in the background with a media notification,
 * while video uses the same player through a PlayerView.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(item: Media, queue: List<Media>, chatTitle: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val isAudio = item.kind == Kind.AUDIO
    val items = remember(queue, item.key) {
        val q = queue.filter { it.kind == Kind.VIDEO || it.kind == Kind.AUDIO }
        if (q.any { it.key == item.key }) q else listOf(item)
    }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var controllerFuture by remember { mutableStateOf<ListenableFuture<MediaController>?>(null) }
    var mode by remember {
        mutableStateOf(if (StreamingRuntime.playbackActive) StreamingRuntime.activeMode else Prefs.effectiveMode())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var aheadMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var landscape by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf(false) }
    var trackLabels by remember { mutableStateOf<List<TrackEntry>>(emptyList()) }
    var warnDismissed by remember { mutableStateOf(false) }
    val metered = remember { Tg.isMetered() }
    var wasReady by remember { mutableStateOf(false) }

    fun savePosition(c: MediaController?) {
        val p = c ?: return
        val d = p.duration
        val pos = p.currentPosition
        val key = p.currentMediaItem?.mediaId ?: item.key
        if (d > 0 && pos > 5_000 && pos < d - 10_000) Prefs.setPos(key, pos) else if (d > 0) Prefs.setPos(key, 0)
    }

    // Background audio needs a visible media notification; ask once on Android 13+.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(isAudio) {
        if (isAudio && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Connect to the playback service (async; UI renders as soon as the controller arrives).
    // SessionToken throws IllegalArgumentException if the manifest ever stops declaring the
    // media3 session-service action; surface that as an in-app error instead of a hard crash.
    LaunchedEffect(Unit) {
        val future = runCatching {
            val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
            MediaController.Builder(ctx, token).buildAsync()
        }.getOrElse { e ->
            StreamingRuntime.lastError = "SessionToken: ${e::class.java.simpleName}: ${e.message}"
            error = e.message ?: "Cannot connect to the playback service"
            return@LaunchedEffect
        }
        controllerFuture = future
        future.addListener({ runCatching { controller = future.get() } }, MoreExecutors.directExecutor())
    }

    DisposableEffect(controller) {
        val c = controller
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = e.cause?.message ?: e.message ?: "Playback error"
                StreamingRuntime.lastError = error
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                    StreamingRuntime.playbackActive = false
                }
                if (state == Player.STATE_BUFFERING && wasReady) {
                    StreamingRuntime.rebufferCount++
                }
                if (state == Player.STATE_READY) wasReady = true
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                trackLabels = tracks.groups.flatMapIndexed { gi, g ->
                    (0 until g.length).map { ti ->
                        val f = g.getTrackFormat(ti)
                        val label = f.label ?: f.language ?: when (g.type) {
                            androidx.media3.common.C.TRACK_TYPE_VIDEO ->
                                "${f.width}x${f.height}${if (f.averageBitrate > 0) " · ${f.averageBitrate / 1000} kbps" else ""}"
                            androidx.media3.common.C.TRACK_TYPE_AUDIO ->
                                "Audio ${f.channelCount}ch${if (f.averageBitrate > 0) " · ${f.averageBitrate / 1000} kbps" else ""}"
                            else -> f.sampleMimeType ?: "Subtitle"
                        }
                        TrackEntry(gi, ti, g.type, label, g.isTrackSelected(ti))
                    }
                }
            }
        }
        c?.addListener(listener)
        val window = activity?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (!isAudio) {
            ctrl?.hide(WindowInsetsCompat.Type.systemBars())
            ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            savePosition(c)
            c?.removeListener(listener)
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // (Re)load the queue when the controller arrives or a different item is opened.
    LaunchedEffect(controller, item.key) {
        val c = controller ?: return@LaunchedEffect
        StreamingRuntime.playbackActive = true
        StreamingRuntime.activeMode = mode
        StreamingRuntime.activeFileId = item.fileId
        // Average bitrate from real message metadata (bytes/second); the 250 ms poll refines
        // it from the extracted duration once the player reports one.
        StreamingRuntime.mediaBitrateBps =
            if (item.duration > 0 && item.size > 0) item.size * 8 / item.duration.toLong() else 0
        val alreadyLoaded = c.mediaItemCount == items.size &&
            (0 until c.mediaItemCount).all { c.getMediaItemAt(it).mediaId == items[it].key }
        if (!alreadyLoaded) {
            val start = items.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
            c.setMediaItems(items.map { it.toMediaItem(chatTitle) }, start, Prefs.getPos(item.key))
            c.prepare()
            c.setPlaybackSpeed(Prefs.playbackSpeed)
            c.playWhenReady = true
        } else if (c.currentMediaItem?.mediaId != item.key) {
            val idx = items.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
            c.seekTo(idx, Prefs.getPos(item.key))
            c.playWhenReady = true
        }
    }

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        var tick = 0
        while (true) {
            aheadMs = (c.bufferedPosition - c.currentPosition).coerceAtLeast(0)
            // Average media bitrate from real size/duration: feeds the policy's time-based
            // band edges and pause-prebuffer targets (Telegram reports no bitrate itself).
            if (c.duration > 0 && item.size > 0) {
                StreamingRuntime.mediaBitrateBps = item.size * 8000 / c.duration
            }
            savePosition(c)
            // Feed the Continue rail every 5 s without hammering SharedPreferences.
            if (++tick % 5 == 0 && c.currentPosition > 0) {
                Prefs.updateRecentMedia(
                    Recent.encode(
                        Recent.note(
                            Recent.decode(Prefs.recentMedia),
                            Recent.Entry(item.chatId, item.msgId, item.fileId, item.kind, item.name, item.size, c.currentPosition, System.currentTimeMillis()),
                        ),
                    ),
                )
            }
            delay(1000)
        }
    }

    // Sleep timer: pause playback after the chosen delay (audio bedside use).
    var sleepMin by remember { mutableIntStateOf(0) }
    LaunchedEffect(sleepMin) {
        if (sleepMin > 0) {
            delay(sleepMin * 60_000L)
            controller?.pause()
        }
    }

    // ── Video controls state (250 ms poll: seek bar must track the loaded prefix smoothly,
    //    including while paused, when the TDLib window keeps growing up to its bounded target) ──
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var loadedFrac by remember { mutableFloatStateOf(0f) }
    var uiUserPaused by remember { mutableStateOf(false) }
    var uiHold by remember { mutableStateOf(false) }
    var aheadBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller, item.fileId) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            posMs = c.currentPosition
            durMs = c.duration.coerceAtLeast(0L)
            playing = c.isPlaying
            // Loaded track = max(Media3 buffered time, real TDLib contiguous prefix mapped to
            // time linearly via file size). Never fabricates ranges: both inputs are real.
            val timeFrac = if (durMs > 0) (c.bufferedPosition.toFloat() / durMs).coerceIn(0f, 1f) else 0f
            val pfx = StreamingRuntime.loadedPrefix
            val byteFrac = if (pfx.fileId == item.fileId && pfx.fileSize > 0) pfx.fraction else 0f
            loadedFrac = maxOf(timeFrac, byteFrac)
            uiUserPaused = StreamingRuntime.userPaused
            uiHold = StreamingRuntime.internalHold
            aheadBytes = StreamingRuntime.lastPrebufferAheadBytes
            delay(250)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Video stops with the screen; audio keeps playing in the background service.
            if (!isAudio) {
                controller?.pause()
                controller?.stop()
                StreamingRuntime.playbackActive = false
            }
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controller = null
        }
    }

    fun applyMode(m: StreamingMode) {
        mode = m
        Prefs.setActiveMode(m)
        StreamingRuntime.activeMode = m
        // TdDataSource reads the live mode on every window request, so read-ahead, backoff and
        // stall behaviour change immediately; no playback restart is needed.
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (controller != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PlayerView(c).apply {
                        keepScreenOn = true
                        // Audio keeps the built-in controller (prev/next + notification parity).
                        // Video uses the custom Compose controls below, whose seek bar shows the
                        // REAL TDLib loaded prefix (YouTube-style) instead of only Media3's buffer.
                        useController = isAudio
                        if (isAudio) {
                            controllerShowTimeoutMs = 0
                            controllerHideOnTouch = false
                        }
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == View.VISIBLE }
                        )
                    }
                },
                update = { it.player = controller },
            )
        }

        // Video: tap anywhere toggles the custom controls; 250 ms state poll drives the seek bar.
        if (!isAudio) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                },
            )
        }

        if (isAudio) {
            // Artwork + metadata (no fake video track is created for audio files).
            Column(
                Modifier.align(Alignment.Center).padding(bottom = 110.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // In Low Data / Extreme Low Data the artwork network fetch is suspended
                // (the inline mini-thumbnail still shows); notification art uses in-memory
                // bytes only, so no speculative requests happen in data-saving modes.
                TgImage(
                    item.thumbId, item.mini,
                    Modifier.size(180.dp).clip(RoundedCornerShape(20.dp)),
                    enabled = Prefs.autoThumbs && !(Prefs.suspendThumbsLowData && mode != StreamingMode.NORMAL),
                )
                Text(
                    if (item.trackTitle.isNotBlank()) item.trackTitle else item.name,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 16.dp, start = 32.dp, end = 32.dp),
                )
                val sub = listOf(item.performer, chatTitle).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (controlsVisible || isAudio) {
            Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val kbps = (StreamingRuntime.estimator.ewmaBps / 1000).toInt()
                        Text(
                            "Buffered +${aheadMs / 1000}s · ${fmtSize(item.size)}" +
                                (if (kbps > 0) " · ~$kbps kbps" else "") +
                                (if (mode != StreamingMode.NORMAL) " · ${mode.label}" else ""),
                            color = Color(0xCCFFFFFF), fontSize = 11.sp,
                        )
                    }
                    if (!isAudio) {
                        TextButton(onClick = {
                            landscape = !landscape
                            activity?.requestedOrientation = if (landscape) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }) { Text("Rotate", color = Color.White) }
                        TextButton(onClick = { activity?.enterPip() }) { Text("PiP", color = Color.White) }
                    }
                    Box {
                        TextButton(onClick = { sleepMenu = true }) { Text(if (sleepMin > 0) "Sleep ${sleepMin}m" else "Sleep", color = Color.White) }
                        DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                            listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 60 to "1 hour").forEach { (m, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { sleepMin = m; sleepMenu = false })
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { speedMenu = true }) { Text("${Prefs.playbackSpeed}×", color = Color.White) }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                                DropdownMenuItem(
                                    text = { Text("${s}×") },
                                    onClick = {
                                        Prefs.updatePlaybackSpeed(s)
                                        controller?.setPlaybackSpeed(s)
                                        speedMenu = false
                                    },
                                )
                            }
                        }
                    }
                    if (trackLabels.isNotEmpty()) {
                        Box {
                            TextButton(onClick = { trackMenu = true }) { Text("Tracks", color = Color.White) }
                            DropdownMenu(expanded = trackMenu, onDismissRequest = { trackMenu = false }) {
                                trackLabels.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text((if (t.selected) "✓ " else "") + t.label) },
                                        onClick = {
                                            val c = controller
                                            if (c != null) {
                                                val group = c.currentTracks.groups.getOrNull(t.groupIndex)
                                                if (group != null) {
                                                    c.trackSelectionParameters = c.trackSelectionParameters
                                                        .buildUpon()
                                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t.trackIndex))
                                                        .build()
                                                }
                                            }
                                            trackMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 8.dp).horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = mode == StreamingMode.NORMAL,
                        onClick = { applyMode(StreamingMode.NORMAL) },
                        label = { Text("Normal Streaming", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = mode == StreamingMode.LOW_DATA,
                        onClick = { applyMode(StreamingMode.LOW_DATA) },
                        label = { Text("Low Data Streaming", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = mode == StreamingMode.EXTREME_LOW_DATA,
                        onClick = { applyMode(StreamingMode.EXTREME_LOW_DATA) },
                        label = { Text(StreamingMode.EXTREME_LOW_DATA.label, fontSize = 11.sp) },
                    )
                }
                if (metered && Prefs.mobileDataWarning && !warnDismissed && mode == StreamingMode.NORMAL) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp)
                            .background(Color(0xCC665500), RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "On mobile data. Low Data Streaming uses less data.",
                            color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { applyMode(StreamingMode.LOW_DATA) }) { Text("Switch", color = Color.White) }
                        TextButton(onClick = { warnDismissed = true }) { Text("Dismiss", color = Color.White) }
                    }
                }
            }
        }

        // Video: custom bottom controls — YouTube-style timeline with three real tracks.
        if (!isAudio && controller != null && (controlsVisible || uiHold || uiUserPaused)) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                ThreeTrackSeekBar(
                    playedFrac = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f,
                    loadedFrac = loadedFrac,
                    onSeek = { f -> if (durMs > 0) controller?.seekTo((f * durMs).toLong()) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val c = controller ?: return@IconButton
                        if (c.isPlaying) c.pause() else c.play()
                    }) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "Pause" else "Play",
                            tint = Color.White,
                        )
                    }
                    if (items.size > 1) {
                        IconButton(onClick = { controller?.seekToPrevious() }) {
                            Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White)
                        }
                        IconButton(onClick = { controller?.seekToNext() }) {
                            Icon(Icons.Filled.SkipNext, "Next", tint = Color.White)
                        }
                    }
                    Text(
                        "${fmtDur((posMs / 1000).toInt())} / ${fmtDur((durMs / 1000).toInt())}",
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    // Pause-to-buffer / start-gate status: honest, from real runtime counters.
                    when {
                        uiHold -> Text(
                            "Building buffer… +${aheadMs / 1000}s",
                            color = Color(0xCCFFFFFF), fontSize = 11.sp,
                        )
                        uiUserPaused && aheadBytes > 0 -> Text(
                            "Prebuffered ${fmtSize(aheadBytes)} ahead",
                            color = Color(0xCCFFFFFF), fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        if (buffering && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (controller == null && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, color = Color.White)
                Button(
                    onClick = {
                        error = null
                        val c = controller
                        if (c != null) {
                            val pos = c.currentPosition
                            c.seekTo(pos) // resume from the last known position
                            c.prepare()
                            c.playWhenReady = true
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Retry") }
            }
        }
    }
}

private data class TrackEntry(
    val groupIndex: Int,
    val trackIndex: Int,
    val type: Int,
    val label: String,
    val selected: Boolean,
)

/**
 * YouTube-style timeline with three honest tracks:
 *  - **played** (theme primary) — media already played (real player position);
 *  - **loaded** (translucent white) — media genuinely available ahead: max(Media3
 *    `bufferedPosition`, TDLib's real contiguous downloaded prefix mapped linearly to time);
 *  - **unloaded** (faint white) — bytes not yet downloaded.
 * Grows while paused, because the pause-prebuffer pump keeps advancing the TDLib window.
 * Tap or drag to seek (drag scrubs locally, seeks on release). Sits on the always-black
 * player background, so the fixed whites are legible in both light and dark themes.
 */
@Composable
private fun ThreeTrackSeekBar(
    playedFrac: Float,
    loadedFrac: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragF by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { off -> onSeek((off.x / size.width).coerceIn(0f, 1f)) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { off -> dragF = (off.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragF?.let(onSeek); dragF = null },
                    onDragCancel = { dragF = null },
                ) { change, _ ->
                    change.consume()
                    dragF = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val pf = (dragF ?: playedFrac).coerceIn(0f, 1f)
        val lf = loadedFrac.coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.18f)), // unloaded track
        ) {
            Box(Modifier.fillMaxWidth(lf).height(4.dp).background(Color.White.copy(alpha = 0.45f))) // loaded
            Box(Modifier.fillMaxWidth(pf).height(4.dp).background(MaterialTheme.colorScheme.primary)) // played
        }
        val knobStart = (maxWidth * pf) - 6.dp
        Box(
            Modifier.padding(start = if (knobStart > 0.dp) knobStart else 0.dp)
                .size(12.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Picture-in-picture for video (Android 8+); no-op elsewhere. Playback keeps running. */
private fun android.app.Activity.enterPip() {
    if (Build.VERSION.SDK_INT >= 26) {
        runCatching {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build(),
            )
        }
    }
}
