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
        startForeground(notificationId, buildProgressNotification(this, RuntimeStatus(isProcessing = true, currentStage = "初始化中")))
        TileStateBridge.requestRefresh(this)
        val container = (application as MusicShareApplication).container
        val coordinator = container.shareCoordinator
        runtimeObserverJob?.cancel()
        runtimeObserverJob = serviceScope.launch {
            container.stateStore.state
                .map { it.runtime }
                .distinctUntilChanged()
                .collect { runtime ->
                    if (running && runtime.isProcessing) {
                        showProgressNotification(runtime)
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
                val resultPosted = showResultNotification(this@ShareForegroundService, container.stateStore.read().runtime)
                stopForeground(if (resultPosted) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                TileStateBridge.requestRefresh(this@ShareForegroundService)
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

    private fun showProgressNotification(runtime: RuntimeStatus) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(notificationId, buildProgressNotification(this, runtime))
        } catch (_: SecurityException) {
            // Android 13+ can deny notification posting; direct share still works from tile/notification action.
        }
    }


    companion object {
        private const val notificationChannelId = "music-share-processing"
        private const val notificationId = 2087
        private const val pendingShareRequestCode = 2089
        private const val actionShareLatest = "com.musicshare.android.action.SHARE_LATEST"

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, shareIntent(context))
        }

        fun showShortcutNotification(context: Context) {
            createNotificationChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (hasActiveShareNotification(notificationManager)) {
                return
            }
            try {
                notificationManager.notify(notificationId, buildShortcutNotification(context))
            } catch (_: SecurityException) {
                // Android 13+ can deny notification posting; direct share still works from tile/notification action.
            }
        }

        fun showResultNotification(context: Context, runtime: RuntimeStatus): Boolean {
            createNotificationChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return try {
                notificationManager.notify(notificationId, buildResultNotification(context, runtime))
                true
            } catch (_: SecurityException) {
                // Android 13+ can deny notification posting; direct share still works from tile/notification action.
                false
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

        private fun buildResultNotification(context: Context, runtime: RuntimeStatus): Notification {
            val title = when {
                runtime.lastError.isNotBlank() -> context.getString(R.string.notification_title_failed)
                runtime.lastShareUrl.isNotBlank() -> context.getString(R.string.notification_title_ready)
                else -> context.getString(R.string.notification_title_shortcut)
            }
            val content = when {
                runtime.lastError.isNotBlank() -> runtime.lastError
                runtime.lastShareUrl.isNotBlank() -> context.getString(R.string.notification_body_ready)
                else -> context.getString(R.string.notification_body_shortcut)
            }
            return NotificationCompat.Builder(context, notificationChannelId)
                .setSmallIcon(R.drawable.ic_music_tile)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
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
        }

        private fun buildProgressNotification(context: Context, runtime: RuntimeStatus): Notification {
            val content = buildProgressContent(context, runtime)
            val builder = NotificationCompat.Builder(context, notificationChannelId)
                .setSmallIcon(R.drawable.ic_music_tile)
                .setContentTitle(context.getString(R.string.notification_title_processing))
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
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
            if (runtime.progressPercent in 0..100) {
                builder.setProgress(100, runtime.progressPercent, false)
            } else {
                builder.setProgress(100, 0, true)
            }
            return builder.build()
        }

        private fun buildProgressContent(context: Context, runtime: RuntimeStatus): String = buildString {
            append(runtime.currentStage.ifBlank { context.getString(R.string.notification_body_processing) })
            if (runtime.progressPercent >= 0) {
                append(' ')
                append(runtime.progressPercent)
                append('%')
            }
        }

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

        private fun hasActiveShareNotification(notificationManager: NotificationManager): Boolean =
            notificationManager.activeNotifications.any { notification -> notification.id == notificationId }

        private fun shareIntent(context: Context): Intent =
            Intent(context, ShareForegroundService::class.java).setAction(actionShareLatest)

        private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
