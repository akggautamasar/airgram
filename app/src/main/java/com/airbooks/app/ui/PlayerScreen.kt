package com.airbooks.app.ui

import android.content.pm.ActivityInfo
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.airbooks.app.Prefs
import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.TdDataSource
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(item: Media, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val isAudio = item.kind == Kind.AUDIO

    val player = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { TdDataSource() }))
            // Slow-network friendly: keep up to 90 s buffered, start playing after 2.5 s,
            // and after a stall wait for 5 s before resuming so it doesn't stutter.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(20_000, 90_000, 2_500, 5_000)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(if (isAudio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var aheadMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var landscape by remember { mutableStateOf(false) }

    fun savePosition() {
        val p = player.currentPosition
        val d = player.duration
        if (d > 0 && p > 5_000 && p < d - 10_000) Prefs.setPos(item.key, p) else Prefs.setPos(item.key, 0)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = e.cause?.message ?: e.message ?: "Playback error"
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)

        val window = activity?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (!isAudio) {
            ctrl?.hide(WindowInsetsCompat.Type.systemBars())
            ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            savePosition()
            player.removeListener(listener)
            player.release()
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(item.key) {
        val mi = MediaItem.Builder().setUri("tg://file/${item.fileId}").build()
        player.setMediaItem(mi, Prefs.getPos(item.key))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(player) {
        var n = 0
        while (true) {
            aheadMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
            if (++n % 5 == 0) savePosition()
            delay(1000)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    keepScreenOn = true
                    if (isAudio) {
                        controllerShowTimeoutMs = 0
                        controllerHideOnTouch = false
                    }
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == View.VISIBLE }
                    )
                }
            },
        )

        if (controlsVisible || isAudio) {
            Row(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Column {
                    Text(item.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 56.dp))
                    Text(
                        "Buffered +${aheadMs / 1000}s · ${fmtSize(item.size)} · prefetch ${Prefs.prefetchMb} MB",
                        color = Color(0xCCFFFFFF), fontSize = 11.sp,
                    )
                }
            }
            if (!isAudio) {
                Button(
                    onClick = {
                        landscape = !landscape
                        activity?.requestedOrientation = if (landscape)
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                ) { Text("⟳") }
            }
        }

        if (buffering && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, color = Color.White)
                Button(onClick = { error = null; player.prepare() }, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Retry")
                }
            }
        }
    }
}
