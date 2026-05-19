package com.musicshare.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.musicshare.android.MusicShareApplication
import com.musicshare.android.R
import com.musicshare.android.data.RuntimeStatus
import com.musicshare.android.tile.TileStateBridge
import com.musicshare.android.widget.ShareWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    private var runtimeObserverJob: Job? = null
    private var lastWidgetRefreshNanos = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != actionShareLatest) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running) {
            return START_NOT_STICKY
        }
        running = true
        startForeground(notificationId, buildShortcutNotification(this))
        TileStateBridge.requestRefresh(this)
        ShareWidgetProvider.requestRefresh(this)
        val container = (application as MusicShareApplication).container
        val coordinator = container.shareCoordinator
        runtimeObserverJob?.cancel()
        runtimeObserverJob = serviceScope.launch {
            container.stateStore.state
                .map { it.runtime }
                .distinctUntilChanged()
                .collect { runtime ->
                    if (running) {
                        refreshWidgetIfDue(runtime)
                    }
                }
        }
        serviceScope.launch {
            try {
                coordinator.shareLatestTrack()
                showToast("已复制链接")
            } catch (error: Throwable) {
                showToast(error.message ?: "分享失败")
            } finally {
                running = false
                runtimeObserverJob?.cancel()
                runtimeObserverJob = null
                stopForeground(STOP_FOREGROUND_DETACH)
                showShortcutNotification(this@ShareForegroundService)
                TileStateBridge.requestRefresh(this@ShareForegroundService)
                ShareWidgetProvider.requestRefresh(this@ShareForegroundService)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val wasRunning = running
        runtimeObserverJob?.cancel()
        serviceScope.cancel()
        if (wasRunning) {
            val appContainer = (application as MusicShareApplication).container
            appContainer.appScope.launch {
                appContainer.stateStore.update { state ->
                    state.copy(
                        runtime = state.runtime.copy(
                            isProcessing = false,
                            currentStage = "",
                            progressPercent = -1,
                            lastError = "分享已中断。",
                        ),
                    )
                }
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@ShareForegroundService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshWidgetIfDue(runtime: RuntimeStatus) {
        val now = System.nanoTime()
        val shouldRefresh = runtime.progressPercent <= 0 ||
            runtime.progressPercent >= 100 ||
            now - lastWidgetRefreshNanos >= widgetRefreshIntervalNanos
        if (shouldRefresh) {
            lastWidgetRefreshNanos = now
            ShareWidgetProvider.requestRefresh(this)
        }
    }

    companion object {
        private const val notificationChannelId = "music-share-processing"
        private const val notificationId = 2087
        private const val pendingShareRequestCode = 2089
        private const val actionShareLatest = "com.musicshare.android.action.SHARE_LATEST"
        private const val widgetRefreshIntervalNanos = 1_000_000_000L

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, shareIntent(context))
        }

        fun showShortcutNotification(context: Context) {
            createNotificationChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.notify(notificationId, buildShortcutNotification(context))
            } catch (_: SecurityException) {
                // Android 13+ can deny notification posting; direct share still works from widget/tile.
            }
        }

        fun sharePendingIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context,
            pendingShareRequestCode,
            shareIntent(context),
            pendingIntentFlags(),
        )

        private fun buildShortcutNotification(context: Context): Notification =
            NotificationCompat.Builder(context, notificationChannelId)
                .setSmallIcon(R.drawable.ic_music_tile)
                .setContentTitle(context.getString(R.string.notification_title_shortcut))
                .setContentText(context.getString(R.string.notification_body_shortcut))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.notification_body_shortcut)),
                )
                .setContentIntent(sharePendingIntent(context))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(
                    R.drawable.ic_music_tile,
                    context.getString(R.string.notification_action_share_again),
                    sharePendingIntent(context),
                )
                .build()

        private fun createNotificationChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                notificationChannelId,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
            manager.createNotificationChannel(channel)
        }

        private fun shareIntent(context: Context): Intent =
            Intent(context, ShareForegroundService::class.java).setAction(actionShareLatest)

        private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
