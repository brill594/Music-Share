package com.musicshare.android.widget

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareWidgetSystemContractTest {
    @Test
    fun widgetUsesPowerampLikeTranslucentGrayPalette() {
        val widgetBackground = File("src/main/res/drawable/bg_share_widget.xml").readText()
        val actionBackground = File("src/main/res/drawable/bg_share_widget_action.xml").readText()
        val layout = File("src/main/res/layout/share_widget.xml").readText()

        assertTrue(widgetBackground.contains("#A9323232"))
        assertTrue(actionBackground.contains("#CC4A4A4A"))
        assertTrue(layout.contains("android:textColor=\"#FFFFFFFF\""))
        assertTrue(layout.contains("android:shadowColor=\"#99000000\""))
    }

    @Test
    fun widgetAndTileShareWithoutOpeningTheApp() {
        val widgetProvider = File("src/main/java/com/musicshare/android/widget/ShareWidgetProvider.kt").readText()
        val tileService = File("src/main/java/com/musicshare/android/tile/ShareTileService.kt").readText()

        assertTrue(widgetProvider.contains("ShareForegroundService.sharePendingIntent"))
        assertTrue(tileService.contains("ShareForegroundService.start(this)"))
        assertFalse(widgetProvider.contains("MainActivity.shareWithPrompt"))
        assertFalse(tileService.contains("startActivityAndCollapse"))
    }

    @Test
    fun foregroundServiceNotificationIsPersistentShareShortcutNotUploadProgress() {
        val service = File("src/main/java/com/musicshare/android/service/ShareForegroundService.kt").readText()

        assertTrue(service.contains("notification_title_shortcut"))
        assertFalse(service.contains("notification_title_processing"))
        assertFalse(service.contains("setProgress"))
        assertFalse(service.contains("notifyCompletion"))
    }
}
