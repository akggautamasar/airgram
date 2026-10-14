package com.airbooks.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps long-running transfers alive screen-off / in background with a `dataSync` foreground
 * service (manifest declares foregroundServiceType="dataSync" + FOREGROUND_SERVICE_DATA_SYNC).
 * The notification shows live active-count and aggregate speed, refreshed at 1 Hz.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        scope.launch {
            while (true) {
                refresh(nm)
                delay(1_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF, n)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh(nm: NotificationManager) = runCatching { nm.notify(NOTIF, build()) }

    private fun build() = NotificationCompat.Builder(this, CHANNEL)
        .setOngoing(true)
        .setSilent(true)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("AirGram downloads")
        .setContentText(
            "${DownloadManager.scheduler.activeCount} active · " +
                "%.2f MB/s".format(DownloadManager.aggregateBps / 8_000_000.0),
        )
        .build()

    private companion object {
        const val CHANNEL = "airgram_downloads"
        const val NOTIF = 911
    }
}
