package com.airbooks.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.content.pm.PackageManager
import android.os.Build
import android.content.pm.ActivityInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    var trackLabels by remember { mutableStateOf<List<TrackEntry>>(emptyList()) }
    var warnDismissed by remember { mutableStateOf(false) }
    val metered = remember { Tg.isMetered() }
    var wasReady by remember { mutableStateOf(false) }

    // ── Upgraded player controls ──
    /** Locks every gesture and hides the controls, so the screen can be wiped/held safely. */
    var locked by remember { mutableStateOf(false) }
    /** media3 AspectRatioFrameLayout resize mode — fit / crop / stretch. */
    var resizeMode by remember { mutableIntStateOf(GestureMath.RESIZE_MODES[0]) }
    /** Transient gesture read-out (brightness, volume, seek preview). */
    var feedback by remember { mutableStateOf<Pill?>(null) }
    /** Transient centred play/pause icon after a tap or double-tap (fades out, §5.E). */
    var pulseIcon by remember { mutableStateOf<ImageVector?>(null) }
    var pulseOn by remember { mutableStateOf(false) }
    var pulseSeq by remember { mutableIntStateOf(0) }
    var infoOpen by remember { mutableStateOf(false) }
    var titleOpen by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var audioMenu by remember { mutableStateOf(false) }
    var subsMenu by remember { mutableStateOf(false) }
    /** Sleep timer variant: stop when this item finishes instead of after a fixed delay. */
    var sleepEndOfItem by remember { mutableStateOf(false) }
    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }
    var subsOn by remember { mutableStateOf(true) }

    val audioMgr = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(900)
            feedback = null
        }
    }
    val pulseAlpha by animateFloatAsState(if (pulseOn) 1f else 0f, tween(320), label = "pulse")
    LaunchedEffect(pulseSeq) {
        if (pulseSeq > 0) {
            delay(420)
            pulseOn = false
        }
    }

    /**
     * Brightness is the *activity window's* value, never the persistent system setting: it applies
     * to this player only and is restored when the screen leaves. `-1` means "follow the system".
     */
    fun brightnessNow(): Float {
        val b = activity?.window?.attributes?.screenBrightness ?: -1f
        return if (b < 0f) 0.5f else b
    }

    fun setBrightness(f: Float) {
        val w = activity?.window ?: return
        val lp = w.attributes
        lp.screenBrightness = f.coerceIn(0.01f, 1f)
        w.attributes = lp
    }

    fun volumeNow(): Pair<Int, Int> =
        audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC) to
            audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun setVolume(v: Int) {
        // Media stream only: ring/alarm/notification volumes are never touched.
        runCatching { audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
    }

    /** Relative seek against the real duration; a no-op when the source reports none. */
    fun seekBy(seconds: Int) {
        val c = controller ?: return
        val target = GestureMath.clampSeek(c.currentPosition, seconds, c.duration)
        if (target != c.currentPosition) c.seekTo(target)
    }

    fun cycleSpeed() {
        val next = GestureMath.nextSpeed(Prefs.playbackSpeed)
        Prefs.updatePlaybackSpeed(next)
        controller?.setPlaybackSpeed(next)
    }

    fun setSpeed(f: Float) {
        Prefs.updatePlaybackSpeed(f)
        controller?.setPlaybackSpeed(f)
    }

    /** Real play/pause flip with the transient centred icon the gesture spec asks for. */
    fun togglePlayPause() {
        val playing = controller?.isPlaying == true
        controller?.let { if (playing) it.pause() else it.play() }
        pulseIcon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow
        pulseOn = true
        pulseSeq++
    }

    /** Executes one gesture. Pointer handling never touches playback state directly. */
    fun handleGesture(g: PlayerGesture) {
        when (g) {
            // §5.E: first interaction while the controls are hidden only reveals them; with the
            // controls up, a centre tap toggles play/pause and an edge tap hides them again.
            is PlayerGesture.Tap ->
                if (GestureMath.centerTapTogglesPlay(controlsVisible, g.xFraction)) {
                    togglePlayPause()
                } else {
                    controlsVisible = !controlsVisible
                }
            PlayerGesture.PlayPause -> togglePlayPause()
            is PlayerGesture.SeekStep -> {
                seekBy(g.seconds)
                feedback = Pill(
                    if (g.seconds > 0) Icons.Filled.Forward10 else Icons.Filled.Replay10,
                    if (g.seconds > 0) "+${g.seconds} s" else "${g.seconds} s",
                    null,
                )
            }
            is PlayerGesture.SeekPreview -> {
                val c = controller ?: return
                if (c.duration <= 0) {
                    feedback = Pill(Icons.Filled.ErrorOutline, "Not seekable", null)
                    return
                }
                val target = GestureMath.clampSeek(c.currentPosition, g.seconds, c.duration)
                feedback = Pill(
                    Icons.Filled.Schedule,
                    (if (g.seconds >= 0) "\u00bb " else "\u00ab ") + fmtDur((target / 1000).toInt()),
                    null,
                )
            }
            is PlayerGesture.SeekCommit -> {
                seekBy(g.seconds)
                feedback = null
            }
            is PlayerGesture.Brightness -> {
                setBrightness(g.value)
                feedback = Pill(
                    Icons.Filled.BrightnessHigh,
                    "Brightness ${(g.value * 100).toInt()}%",
                    g.value,
                )
            }
            is PlayerGesture.Volume -> {
                setVolume(g.value)
                feedback = Pill(
                    if (g.value == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    if (g.value == 0) "Muted" else "Volume ${g.value}/${g.max}",
                    if (g.max > 0) g.value.toFloat() / g.max else null,
                )
            }
        }
    }

    /** Applies a real track override from the current tracks snapshot. */
    fun pickTrack(t: TrackEntry) {
        val c = controller ?: return
        val group = c.currentTracks.groups.getOrNull(t.groupIndex) ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t.trackIndex))
            .build()
    }

    fun toggleSubtitleTrack(on: Boolean) {
        val c = controller ?: return
        subsOn = on
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !on)
            .build()
    }

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

            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                videoW = size.width
                videoH = size.height
            }

            // "End of item" sleep timer: stop here instead of letting the queue continue.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (sleepEndOfItem) {
                    sleepEndOfItem = false
                    sleepMenu = false
                    c?.pause()
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
            // Hand brightness back to the system: the gesture only ever touched this window.
            activity?.window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = -1f
                w.attributes = lp
            }
        }
    }

    // (Re)load the queue when the controller arrives or a different item is opened.
    LaunchedEffect(controller, item.key) {
        val c = controller ?: return@LaunchedEffect
        StreamingRuntime.playbackActive = true
        StreamingRuntime.activeMode = mode
        StreamingRuntime.activeFileId = item.fileId
        StreamingRuntime.metrics.startSession(android.os.SystemClock.elapsedRealtime())
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
                        // Video AND audio use the custom Compose controls below, whose seek bar
                        // shows the REAL TDLib loaded prefix (YouTube-style) instead of only
                        // Media3's buffer. Background playback, the media notification, headset
                        // controls and queue navigation stay on the MediaSession — unaffected.
                        useController = false
                    }
                },
                update = { pv ->
                    pv.player = controller
                    // Fit / crop / stretch, applied to the real PlayerView (no re-layout hacks).
                    pv.resizeMode = resizeMode
                },
            )
        }

        // Video gestures: a single tap reveals the controls (or, once visible, toggles play/pause
        // in the centre and hides them at the edges), double-tap seeks or plays, vertical swipes
        // on the edges drive brightness/volume and a horizontal swipe seeks. Audio keeps its
        // always-on controls and no brightness gesture (there is no picture to dim).
        if (!isAudio && !locked) {
            GestureLayer(::brightnessNow, ::volumeNow, ::handleGesture)
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

        val audioTracks = trackLabels.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        val textTracks = trackLabels.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }

        fun rotate() {
            landscape = !landscape
            activity?.requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        if (!locked && (controlsVisible || isAudio)) {
            Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        // Truncated here; tapping shows the full name.
                        Text(
                            item.name, color = Color.White, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { titleOpen = true },
                        )
                        val kbps = (StreamingRuntime.estimator.ewmaBps / 1000).toInt()
                        Text(
                            "Buffered +${aheadMs / 1000}s \u00b7 ${fmtSize(item.size)}" +
                                (if (kbps > 0) " \u00b7 ~$kbps kbps" else "") +
                                (if (mode != StreamingMode.NORMAL) " \u00b7 ${mode.label}" else ""),
                            color = Color(0xCCFFFFFF), fontSize = 11.sp,
                        )
                    }
                    if (!isAudio) {
                        IconButton(onClick = { locked = true }) {
                            Icon(Icons.Filled.Lock, "Lock controls", tint = Color.White)
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) {
                            Icon(Icons.Filled.MoreVert, "More options", tint = Color.White)
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            // Only options that mean something for this item are offered.
                            if (!isAudio) {
                                DropdownMenuItem(
                                    text = { Text("Aspect ratio: ${GestureMath.resizeLabel(resizeMode)}") },
                                    leadingIcon = { Icon(Icons.Filled.AspectRatio, null) },
                                    onClick = { resizeMode = GestureMath.nextResizeMode(resizeMode) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rotate screen") },
                                    leadingIcon = { Icon(Icons.Filled.ScreenRotation, null) },
                                    onClick = { moreMenu = false; rotate() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Picture in picture") },
                                    leadingIcon = { Icon(Icons.Filled.PictureInPictureAlt, null) },
                                    onClick = { moreMenu = false; activity?.enterPip() },
                                )
                            }
                            if (textTracks.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(if (subsOn) "Subtitles: on" else "Subtitles: off") },
                                    leadingIcon = { Icon(Icons.Filled.Subtitles, null) },
                                    onClick = { toggleSubtitleTrack(!subsOn) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (locked) "Unlock controls" else "Lock controls") },
                                leadingIcon = {
                                    Icon(if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock, null)
                                },
                                onClick = { locked = !locked; moreMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Show full name") },
                                onClick = { moreMenu = false; titleOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Playback info") },
                                leadingIcon = { Icon(Icons.Filled.Info, null) },
                                onClick = { moreMenu = false; infoOpen = true },
                            )
                        }
                    }
                }
                // One scrollable row keeps every control reachable on a narrow phone without
                // squeezing the title: the three streaming modes plus orientation, PiP, sleep,
                // speed and the real audio/subtitle track pickers.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        .horizontalScroll(rememberScrollState()),
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
                    if (!isAudio) {
                        AssistBtn(Icons.Filled.ScreenRotation, "Rotate", ::rotate)
                        AssistBtn(Icons.Filled.PictureInPictureAlt, "PiP") { activity?.enterPip() }
                        AssistBtn(Icons.Filled.AspectRatio, GestureMath.resizeLabel(resizeMode)) {
                            resizeMode = GestureMath.nextResizeMode(resizeMode)
                        }
                    }
                    Box {
                        TextButton(
                            onClick = { sleepMenu = true },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Filled.Timer, null, tint = Color.White, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(
                                when {
                                    sleepEndOfItem -> "End of item"
                                    sleepMin > 0 -> "Sleep ${sleepMin}m"
                                    else -> "Sleep"
                                },
                                color = Color.White, fontSize = 11.sp, maxLines = 1,
                            )
                        }
                        DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Off") },
                                onClick = { sleepMin = 0; sleepEndOfItem = false; sleepMenu = false },
                            )
                            GestureMath.SLEEP_MINUTES.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text((if (sleepMin == m) "\u2713 " else "") + "$m minutes") },
                                    onClick = {
                                        sleepMin = m; sleepEndOfItem = false; sleepMenu = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text((if (sleepEndOfItem) "\u2713 " else "") + "End of this item") },
                                onClick = { sleepMin = 0; sleepEndOfItem = true; sleepMenu = false },
                            )
                        }
                    }
                    Box {
                        TextButton(
                            onClick = { speedMenu = true },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Filled.Speed, null, tint = Color.White, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("${Prefs.playbackSpeed}\u00d7", color = Color.White, fontSize = 11.sp)
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            GestureMath.SPEEDS.forEach { sp0 ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (kotlin.math.abs(sp0 - Prefs.playbackSpeed) < 0.01f) "\u2713 " else "") +
                                                "${sp0}\u00d7",
                                        )
                                    },
                                    onClick = { setSpeed(sp0); speedMenu = false },
                                )
                            }
                        }
                    }
                    if (audioTracks.isNotEmpty()) {
                        Box {
                            TextButton(
                                onClick = { audioMenu = true },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Icon(Icons.Filled.Audiotrack, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    audioTracks.firstOrNull { it.selected }?.label?.take(14) ?: "Audio",
                                    color = Color.White, fontSize = 11.sp, maxLines = 1,
                                )
                            }
                            DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false }) {
                                audioTracks.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text((if (t.selected) "\u2713 " else "") + t.label) },
                                        onClick = { pickTrack(t); audioMenu = false },
                                    )
                                }
                            }
                        }
                    }
                    if (textTracks.isNotEmpty()) {
                        Box {
                            TextButton(
                                onClick = { subsMenu = true },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Icon(Icons.Filled.Subtitles, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    if (!subsOn) "Off"
                                    else textTracks.firstOrNull { it.selected }?.label?.take(12) ?: "Subtitles",
                                    color = Color.White, fontSize = 11.sp, maxLines = 1,
                                )
                            }
                            DropdownMenu(expanded = subsMenu, onDismissRequest = { subsMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text((if (!subsOn) "\u2713 " else "") + "Subtitles off") },
                                    onClick = { toggleSubtitleTrack(false); subsMenu = false },
                                )
                                textTracks.forEach { t ->
                                    DropdownMenuItem(
                                        text = {
                                            Text((if (subsOn && t.selected) "\u2713 " else "") + t.label)
                                        },
                                        onClick = {
                                            toggleSubtitleTrack(true)
                                            pickTrack(t)
                                            subsMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
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

        // Locked: no gestures, no controls, just a way back out.
        if (locked) {
            IconButton(
                onClick = { locked = false },
                modifier = Modifier.align(Alignment.CenterEnd).padding(20.dp),
            ) {
                Icon(Icons.Filled.LockOpen, "Unlock controls", tint = Color.White)
            }
        }

        // Custom bottom controls (video + audio) — YouTube-style timeline with three real tracks.
        // Audio keeps it permanently visible; video toggles it with a tap.
        if (!locked && controller != null && (isAudio || controlsVisible || uiHold || uiUserPaused)) {
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
                    // Relative seek, disabled when the source reports no real duration.
                    IconButton(onClick = { seekBy(-GestureMath.SEEK_STEP_SECONDS) }, enabled = durMs > 0) {
                        Icon(Icons.Filled.Replay10, "Back 10 seconds", tint = Color.White)
                    }
                    IconButton(onClick = { seekBy(GestureMath.SEEK_STEP_SECONDS) }, enabled = durMs > 0) {
                        Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = Color.White)
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
                        "${fmtDur((posMs / 1000).toInt())} / " +
                            (if (durMs > 0) fmtDur((durMs / 1000).toInt()) else "--:--"),
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    // Pause-to-buffer / start-gate status: honest, from real runtime counters.
                    when {
                        uiHold -> Text(
                            "Building buffer\u2026 +${aheadMs / 1000}s",
                            color = Color(0xCCFFFFFF), fontSize = 11.sp,
                        )
                        uiUserPaused && aheadBytes > 0 -> Text(
                            "Prebuffered ${fmtSize(aheadBytes)} ahead",
                            color = Color(0xCCFFFFFF), fontSize = 11.sp,
                        )
                    }
                    TextButton(
                        onClick = ::cycleSpeed,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("${Prefs.playbackSpeed}\u00d7", color = Color.White, fontSize = 12.sp) }
                    if (!isAudio) {
                        IconButton(onClick = ::rotate) {
                            Icon(Icons.Filled.ScreenRotation, "Rotate", tint = Color.White)
                        }
                        IconButton(onClick = { locked = true }) {
                            Icon(Icons.Filled.Lock, "Lock controls", tint = Color.White)
                        }
                    }
                }
                if (durMs <= 0) {
                    Text(
                        "This source reports no duration, so seeking is unavailable.",
                        color = Color(0xCCFFFFFF), fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }

        // Gesture read-out: transient icon + text + level bar, the only overlay a swipe produces.
        feedback?.let { f ->
            Surface(
                Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xCC000000),
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    f.icon?.let {
                        Icon(it, null, Modifier.size(22.dp), tint = Color.White)
                        Spacer(Modifier.size(10.dp))
                    }
                    Column {
                        Text(f.text, color = Color.White, fontSize = 16.sp)
                        f.level?.let { lv ->
                            Spacer(Modifier.size(6.dp))
                            Box(
                                Modifier
                                    .size(width = 96.dp, height = 4.dp)
                                    .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(lv.coerceIn(0f, 1f))
                                        .background(Color.White, RoundedCornerShape(2.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Centred play/pause pulse from a tap or double-tap; fades away on its own.
        val pIcon = pulseIcon
        if (pIcon != null && pulseAlpha > 0.01f) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(84.dp)
                    .alpha(pulseAlpha)
                    .background(Color.Black.copy(alpha = 0.38f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(pIcon, null, Modifier.size(44.dp), tint = Color.White)
            }
        }

        if (titleOpen) {
            AlertDialog(
                onDismissRequest = { titleOpen = false },
                confirmButton = { TextButton(onClick = { titleOpen = false }) { Text("Close") } },
                title = { Text("Full name") },
                text = { Text(item.name) },
            )
        }

        if (infoOpen) {
            val c = controller
            val metaBitrate = StreamingRuntime.mediaBitrateBps
            val measured = StreamingRuntime.estimator.ewmaBps
            val pfx = StreamingRuntime.loadedPrefix
            val pct = GestureMath.bufferedPercent(posMs, posMs + aheadMs, durMs)
            AlertDialog(
                onDismissRequest = { infoOpen = false },
                confirmButton = { TextButton(onClick = { infoOpen = false }) { Text("Close") } },
                title = { Text("Playback info") },
                text = {
                    Column {
                        InfoLine("Player", "MediaController \u2192 PlaybackService (ExoPlayer)")
                        InfoLine("Streaming mode", mode.label)
                        InfoLine(
                            "Resolution",
                            if (videoW > 0 && videoH > 0) "$videoW\u00d7$videoH" else "unknown",
                        )
                        InfoLine(
                            "Bitrate (from size/duration)",
                            if (metaBitrate > 0) "${metaBitrate / 1000} kbps" else "unknown",
                        )
                        InfoLine(
                            "Throughput (measured)",
                            if (measured > 0) "${(measured / 1000).toInt()} kbps" else "unknown",
                        )
                        InfoLine("Buffered ahead", "+${aheadMs / 1000}s")
                        InfoLine(
                            "Buffered share",
                            if (pct >= 0) "$pct% of duration" else "unknown duration",
                        )
                        InfoLine(
                            "Downloaded window",
                            if (pfx.fileId == item.fileId && pfx.fileSize > 0) {
                                "${(pfx.fraction * 100).toInt()}% of ${fmtSize(pfx.fileSize)}"
                            } else "not started for this item",
                        )
                        InfoLine("Rebuffer events", "${StreamingRuntime.rebufferCount}")
                        InfoLine("Item size", fmtSize(item.size))
                        InfoLine(
                            "Position",
                            "${fmtDur((posMs / 1000).toInt())} / " +
                                (if (durMs > 0) fmtDur((durMs / 1000).toInt()) else "unknown"),
                        )
                        if (c == null) InfoLine("Controller", "not connected")
                    }
                },
            )
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

/** Transient gesture read-out: optional icon, text, and optional 0..1 level for the bar. */
private data class Pill(val icon: ImageVector?, val text: String, val level: Float?)

/**
 * One gesture the player should execute. The layer below only decides *which* gesture happened —
 * all playback effects stay in [PlayerScreen], which keeps this testable and side-effect free.
 */
private sealed interface PlayerGesture {
    /** Single tap; x as a fraction of the player width so the screen can apply the tap policy. */
    data class Tap(val xFraction: Float) : PlayerGesture
    data object PlayPause : PlayerGesture
    data class SeekStep(val seconds: Int) : PlayerGesture
    data class SeekPreview(val seconds: Int) : PlayerGesture
    data class SeekCommit(val seconds: Int) : PlayerGesture
    data class Brightness(val value: Float) : PlayerGesture
    data class Volume(val value: Int, val max: Int) : PlayerGesture
}

/**
 * Full-screen pointer layer for video.
 *
 * Two cooperating detectors on one node: a drag state machine ([GestureAxisLock]) that locks to
 * the dominant axis once the finger passes the touch slop, and a tap/double-tap detector. The drag
 * runs first and consumes movement as soon as it locks, which cancels the tap detector — so a
 * swipe can never also register as a tap, and a tap never triggers a seek.
 */
@Composable
private fun GestureLayer(
    currentBrightness: () -> Float,
    currentVolume: () -> Pair<Int, Int>,
    onGesture: (PlayerGesture) -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                val lock = GestureAxisLock(viewConfiguration.touchSlop * 1.5f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    lock.down(down.position.x, down.position.y)
                    var startB = currentBrightness()
                    var startV = currentVolume()
                    var seek = 0
                    var active = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: break
                        val axis = lock.move(change.position.x, change.position.y)
                        if (axis != GestureAxis.NONE) {
                            if (!active) {
                                active = true
                                startB = currentBrightness()
                                startV = currentVolume()
                            }
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (axis == GestureAxis.VERTICAL) {
                                when (GestureMath.zoneOf(lock.startX, w)) {
                                    GestureZone.LEFT ->
                                        onGesture(
                                            PlayerGesture.Brightness(
                                                GestureMath.brightnessFrom(startB, lock.deltaY, h),
                                            ),
                                        )
                                    GestureZone.RIGHT -> {
                                        val (cur, max) = startV
                                        onGesture(
                                            PlayerGesture.Volume(
                                                GestureMath.volumeFrom(cur, max, lock.deltaY, h),
                                                max,
                                            ),
                                        )
                                    }
                                    // Centre vertical drags are left alone: no accidental effect.
                                    GestureZone.CENTER -> Unit
                                }
                            } else {
                                val s = GestureMath.seekFromDrag(lock.deltaX, w)
                                if (s != seek) {
                                    seek = s
                                    onGesture(PlayerGesture.SeekPreview(s))
                                }
                            }
                            change.consume()
                        }
                    }
                    if (active && lock.axis == GestureAxis.HORIZONTAL) {
                        onGesture(PlayerGesture.SeekCommit(seek))
                    }
                    lock.up()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { off -> onGesture(PlayerGesture.Tap(off.x / size.width.toFloat())) },
                    onDoubleTap = { off ->
                        val s = GestureMath.doubleTapSeconds(off.x, size.width.toFloat())
                        onGesture(
                            if (s != 0) PlayerGesture.SeekStep(s) else PlayerGesture.PlayPause,
                        )
                    },
                )
            },
    )
}

/** Compact labelled icon action used in the player's scrollable control row. */
@Composable
private fun AssistBtn(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(15.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label, Modifier.weight(1f), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        Text(value, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
