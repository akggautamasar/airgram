package com.airbooks.app.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.airbooks.app.Prefs
import com.airbooks.app.stream.PlaybackPolicy
import com.airbooks.app.stream.PolicyInput
import com.airbooks.app.stream.StreamingRuntime
import com.airbooks.app.tg.TdDataSourceFactory

/**
 * Hosts the single ExoPlayer so audio (and video audio) keeps playing in the background with a
 * media notification / media-session controls. The UI connects with a MediaController; it never
 * creates a second player, so there are no duplicate listeners or leaked player instances.
 *
 * Buffer durations are chosen from the streaming policy for the mode active when the service is
 * created; the data-source window, backoff and stall behaviour keep following the live mode on
 * every read (see TdDataSource), so switching Normal/Low Data in the player changes real network
 * behaviour immediately without restarting playback.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val mode = Prefs.effectiveMode()
        StreamingRuntime.activeMode = mode
        // Buffer durations come from the streaming policy; if anything is ever misconfigured the
        // service must still come up with sane defaults instead of crashing on onCreate.
        val decision = runCatching {
            PlaybackPolicy().decide(
                PolicyInput(
                    throughputBps = StreamingRuntime.estimator.ewmaBps,
                    avgLatencyMs = 0L,
                    mediaBitrateBps = 0,
                    bufferedMs = 0L,
                    rebufferCount = 0,
                    positionMs = 0L,
                    mode = mode,
                    hasLowerQualityVariant = false,
                    maxWindowBytes = Prefs.prefetchMb * 1024L * 1024L,
                )
            )
        }.getOrNull()
        val minBufferMs = decision?.minBufferMs?.toInt() ?: 15_000
        val maxBufferMs = decision?.maxBufferMs?.toInt() ?: 60_000
        val startMs = decision?.bufferForPlaybackMs?.toInt() ?: 2_500
        val rebufferMs = decision?.bufferForPlaybackAfterRebufferMs?.toInt() ?: 5_000
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(TdDataSourceFactory()))
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
        session = MediaSession.Builder(this, player).build()
    }

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
        session?.run {
            player.release()
            release()
        }
        session = null
        StreamingRuntime.playbackActive = false
        super.onDestroy()
    }

    private companion object {
        /** Hard heap ceiling for buffered samples, regardless of durations or (unknown) bitrate. */
        const val TARGET_BUFFER_BYTES = 48 * 1024 * 1024
    }
}
