package com.airbooks.app.playback

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.airbooks.app.Prefs
import com.airbooks.app.stream.PlaybackPolicy
import com.airbooks.app.stream.PolicyInput
import com.airbooks.app.stream.StartGate
import com.airbooks.app.stream.StreamingRuntime
import com.airbooks.app.tg.TdDataSourceFactory

/**
 * Hosts the single ExoPlayer so audio (and video audio) keeps playing in the background with a
 * media notification / media-session controls. The UI connects with a MediaController; it never
 * creates a second player, so there are no duplicate listeners or leaked player instances.
 *
 * Buffer durations are chosen from the streaming policy for the mode active when the service is
 * created; the data-source window, backoff and stall behaviour keep following the live mode on
 * every read (see TdDataSource), so switching modes in the player changes real network behaviour
 * immediately without restarting playback.
 *
 * A ~1 Hz ticker adds the two behaviours LoadControl cannot express live:
 *  - **Extreme Low Data start gate** — playback is held (INTERNAL pause, never a user pause)
 *    until the buffer ahead reaches the mode's start target, and released at the resume target,
 *    at end-of-stream, when the user leaves the mode, or when the user explicitly presses play
 *    (the user always wins; the gate does not re-hold until the resume target is reached).
 *  - **Pause-to-buffer** — while the USER has paused, the active [TdDataSourceFactory.current]
 *    source advances the TDLib window up to its mode-specific bounded prebuffer target.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var sourceFactory: TdDataSourceFactory? = null
    private val gatePolicy = PlaybackPolicy()

    private val handler = Handler(Looper.getMainLooper())
    private var internalHold = false
    private var applyingInternal = false // guards listener re-entrancy for our own pause/play
    private var userWantsPlay = true
    private var userOverrodeHold = false // user pressed play during a hold: gate stands down

    private val ticker = object : Runnable {
        override fun run() {
            runCatching { tick() }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val mode = Prefs.effectiveMode()
        StreamingRuntime.activeMode = mode
        // Buffer durations come from the streaming policy; if anything is ever misconfigured the
        // service must still come up with sane defaults instead of crashing on onCreate.
        val decision = runCatching { gatePolicy.decide(servicePolicyInput(mode, 0L)) }.getOrNull()
        val minBufferMs = decision?.minBufferMs?.toInt() ?: 15_000
        val maxBufferMs = decision?.maxBufferMs?.toInt() ?: 60_000
        val startMs = decision?.bufferForPlaybackMs?.toInt() ?: 2_500
        val rebufferMs = decision?.bufferForPlaybackAfterRebufferMs?.toInt() ?: 5_000
        val factory = TdDataSourceFactory()
        sourceFactory = factory
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(minBufferMs, maxBufferMs, startMs, rebufferMs)
                    // Telegram exposes no bitrate up front, so without a byte target the
                    // duration-based cap alone can reserve hundreds of MB of heap on a
                    // high-bitrate file and OOM-kill the process mid-buffer.
                    .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // Acceptance §6 measurements: startup time + cumulative buffering duration.
                val now = android.os.SystemClock.elapsedRealtime()
                when (state) {
                    Player.STATE_BUFFERING -> StreamingRuntime.metrics.markBuffering(now)
                    Player.STATE_READY -> StreamingRuntime.metrics.markReady(now)
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // Queue moved on (possibly without the player screen): drop the previous
                // item's bitrate estimate and any stand-down so policy relearns this file.
                StreamingRuntime.mediaBitrateBps = 0
                userOverrodeHold = false
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (applyingInternal) return // our own gate pause/resume: not user intent
                userWantsPlay = playWhenReady
                if (playWhenReady && internalHold) {
                    // User explicitly pressed play while the gate was holding: stand down.
                    userOverrodeHold = true
                    internalHold = false
                }
                if (!playWhenReady) {
                    internalHold = false // user paused (also ends any hold); pump takes over
                    userOverrodeHold = false
                }
            }
        })
        session = MediaSession.Builder(this, player).build()
        handler.post(ticker)
    }

    private fun tick() {
        val p = session?.player ?: return
        val mode = StreamingRuntime.activeMode
        val ahead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
        val atEnd = p.playbackState == Player.STATE_ENDED ||
            (p.duration > 0 && p.bufferedPosition >= p.duration - END_MARGIN_MS)
        val decision = gatePolicy.decide(servicePolicyInput(mode, ahead))

        // Extreme-mode start/resume gate (internal hold only — user pauses are never touched).
        if (!internalHold && !userOverrodeHold && p.playWhenReady &&
            StartGate.shouldHoldStart(mode, ahead, decision.bufferForPlaybackMs, atEnd)
        ) {
            internalHold = true
            applyingInternal = true
            p.pause()
            applyingInternal = false
        } else if (internalHold && StartGate.shouldReleaseHold(mode, ahead, decision.bufferForPlaybackAfterRebufferMs, atEnd)) {
            internalHold = false
            if (userWantsPlay) {
                applyingInternal = true
                p.play()
                applyingInternal = false
            }
        }
        if (userOverrodeHold && ahead >= decision.bufferForPlaybackAfterRebufferMs) {
            userOverrodeHold = false // buffer recovered: the gate may protect again later
        }
        StreamingRuntime.internalHold = internalHold

        // Pause-to-buffer: distinguish the intentional user pause from internal states.
        val userPaused = !p.playWhenReady && !internalHold
        StreamingRuntime.userPaused = userPaused
        sourceFactory?.current?.prebufferTick(userPaused && p.playbackState == Player.STATE_READY)
    }

    private fun servicePolicyInput(mode: com.airbooks.app.stream.StreamingMode, aheadMs: Long) = PolicyInput(
        throughputBps = StreamingRuntime.estimator.ewmaBps,
        avgLatencyMs = 0L,
        mediaBitrateBps = StreamingRuntime.mediaBitrateBps,
        bufferedMs = aheadMs,
        rebufferCount = StreamingRuntime.rebufferCount,
        positionMs = 0L,
        mode = mode,
        hasLowerQualityVariant = false,
        maxWindowBytes = Prefs.prefetchMb * 1024L * 1024L,
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player != null && !player.playWhenReady) {
            player.stop()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        session?.run {
            player.release()
            release()
        }
        session = null
        sourceFactory = null
        internalHold = false
        StreamingRuntime.playbackActive = false
        StreamingRuntime.userPaused = false
        StreamingRuntime.internalHold = false
        super.onDestroy()
    }

    private companion object {
        /** Hard heap ceiling for buffered samples, regardless of durations or (unknown) bitrate. */
        const val TARGET_BUFFER_BYTES = 48 * 1024 * 1024
        const val TICK_MS = 1_000L
        const val END_MARGIN_MS = 300L
    }
}
