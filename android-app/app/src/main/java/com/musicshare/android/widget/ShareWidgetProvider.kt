package com.musicshare.android.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.musicshare.android.MusicShareApplication
import com.musicshare.android.R
import com.musicshare.android.data.PersistedAppState
import com.musicshare.android.service.ShareForegroundService
import kotlinx.coroutines.launch

class ShareWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        updateWidgetsAsync(context, appWidgetManager, appWidgetIds) {
            pendingResult.finish()
        }
    }

    companion object {
        fun requestRefresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ShareWidgetProvider::class.java)
            updateWidgetsAsync(context, manager, manager.getAppWidgetIds(component))
        }

        private fun updateWidgetsAsync(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            onFinished: () -> Unit = {},
        ) {
            if (appWidgetIds.isEmpty()) {
                onFinished()
                return
            }
            val application = context.applicationContext as? MusicShareApplication
            if (application == null) {
                onFinished()
                return
            }
            application.container.appScope.launch {
                try {
                    val appState = application.container.stateStore.read()
                    appWidgetIds.forEach { widgetId ->
                        appWidgetManager.updateAppWidget(widgetId, buildViews(context, appState))
                    }
                } finally {
                    onFinished()
                }
            }
        }

        private fun buildViews(context: Context, appState: PersistedAppState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.share_widget)
            views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_title))
            views.setTextViewText(R.id.widgetSubtitle, buildSubtitle(context, appState))
            views.setTextViewText(
                R.id.widgetAction,
                context.getString(if (appState.runtime.isProcessing) R.string.widget_action_processing else R.string.widget_action_share),
            )
            val shareIntent = ShareForegroundService.sharePendingIntent(context)
            views.setOnClickPendingIntent(R.id.widgetRoot, shareIntent)
            views.setOnClickPendingIntent(R.id.widgetAction, shareIntent)
            return views
        }

        private fun buildSubtitle(context: Context, appState: PersistedAppState): String {
            val runtime = appState.runtime
            if (runtime.isProcessing) {
                return buildString {
                    append(runtime.currentStage.ifBlank { context.getString(R.string.widget_status_processing) })
                    if (runtime.progressPercent >= 0) {
                        append(' ')
                        append(runtime.progressPercent)
                        append('%')
                    }
                }
            }
            val track = appState.latestTrack
            return when {
                track?.isResolvable == true -> track.displayTitle()
                appState.hasMusicTreePermission() -> context.getString(R.string.widget_status_waiting_track)
                else -> context.getString(R.string.widget_status_needs_setup)
            }
        }
    }
}
