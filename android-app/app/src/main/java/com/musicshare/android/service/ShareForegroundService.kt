package com.musicshare.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.musicshare.android.MusicShareApplication
import com.musicshare.android.MainActivity
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

    private data class CompletionNotice(
        val title: String,
        val content: String,
        val toast: String,
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        startForeground(notificationId, buildNotification(getString(R.string.notification_title_processing), "初始化中"))
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
                        updateOngoingNotification(runtime)
                        refreshWidgetIfDue(runtime)
                    }
                }
        }
        serviceScope.launch {
            var completionNotice: CompletionNotice? = null
            try {
                val result = coordinator.shareLatestTrack()
                completionNotice = CompletionNotice(
                    title = getString(R.string.notification_title_ready),
                    content = result.shareUrl,
                    toast = "已复制链接",
                )
            } catch (error: Throwable) {
                val message = error.message ?: "分享失败"
                completionNotice = CompletionNotice(
                    title = getString(R.string.notification_title_failed),
                    content = message,
                    toast = message,
                )
            } finally {
                running = false
                runtimeObserverJob?.cancel()
                runtimeObserverJob = null
                stopForeground(STOP_FOREGROUND_DETACH)
                completionNotice?.let { notice ->
                    notifyCompletion(
                        title = notice.title,
                        content = notice.content,
                        toast = notice.toast,
                    )
                }
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

    private suspend fun notifyCompletion(title: String, content: String, toast: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { notificationManager.notify(notificationId, buildNotification(title, content)) }
        withContext(Dispatchers.Main) {
            Toast.makeText(this@ShareForegroundService, toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOngoingNotification(runtime: RuntimeStatus) {
        val content = buildString {
            append(runtime.currentStage.ifBlank { "处理中" })
            if (runtime.progressPercent >= 0) {
                append(" ${runtime.progressPercent}%")
            }
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            notificationId,
            buildNotification(
                title = getString(R.string.notification_title_processing),
                content = content,
                progressPercent = runtime.progressPercent,
            ),
        )
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
    private fun buildNotification(
        title: String,
        content: String,
        progressPercent: Int = -1,
    ): Notification {
        val builder = NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(R.drawable.ic_music_tile)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(buildOpenAppPendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
        if (running) {
            if (progressPercent in 0..100) {
                builder.setProgress(100, progressPercent, false)
            } else {
                builder.setProgress(100, 0, true)
            }
        } else {
            builder.addAction(
                R.drawable.ic_music_tile,
                getString(R.string.notification_action_share_again),
                sharePendingIntent(this),
            )
        }
        return builder.build()
    }

    private fun buildOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        return PendingIntent.getActivity(
            this,
            pendingOpenAppRequestCode,
            intent,
            pendingIntentFlags(),
        )
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
        private const val pendingOpenAppRequestCode = 2088
        private const val pendingShareRequestCode = 2089
        private const val actionShareLatest = "com.musicshare.android.action.SHARE_LATEST"
        private const val widgetRefreshIntervalNanos = 1_000_000_000L

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, shareIntent(context))
        }

        fun sharePendingIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context,
            pendingShareRequestCode,
            shareIntent(context),
            pendingIntentFlags(),
        )

        private fun shareIntent(context: Context): Intent =
            Intent(context, ShareForegroundService::class.java).setAction(actionShareLatest)

        private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
