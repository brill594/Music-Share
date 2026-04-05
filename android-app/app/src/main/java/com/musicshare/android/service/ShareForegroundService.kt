package com.musicshare.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.musicshare.android.MusicShareApplication
import com.musicshare.android.R
import com.musicshare.android.tile.TileStateBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != actionShareLatest || running) {
            return START_NOT_STICKY
        }
        running = true
        startForeground(notificationId, buildNotification(getString(R.string.notification_title_processing), "初始化中"))
        TileStateBridge.requestRefresh(this)
        val coordinator = (application as MusicShareApplication).container.shareCoordinator
        serviceScope.launch {
            try {
                val result = coordinator.shareLatestTrack()
                notifyCompletion(
                    title = getString(R.string.notification_title_ready),
                    content = result.shareUrl,
                    toast = "已复制链接",
                )
            } catch (error: Throwable) {
                notifyCompletion(
                    title = getString(R.string.notification_title_failed),
                    content = error.message ?: "分享失败",
                    toast = error.message ?: "分享失败",
                )
            } finally {
                running = false
                TileStateBridge.requestRefresh(this@ShareForegroundService)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun notifyCompletion(title: String, content: String, toast: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, buildNotification(title, content))
        withContext(Dispatchers.Main) {
            Toast.makeText(this@ShareForegroundService, toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(R.drawable.ic_music_tile)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            notificationChannelId,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val notificationChannelId = "music-share-processing"
        private const val notificationId = 2087
        private const val actionShareLatest = "com.musicshare.android.action.SHARE_LATEST"

        fun start(context: Context) {
            val intent = Intent(context, ShareForegroundService::class.java).setAction(actionShareLatest)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
