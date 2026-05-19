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
    fun foregroundServiceShowsRuntimeProgressOnlyAfterShareStarts() {
        val service = File("src/main/java/com/musicshare/android/service/ShareForegroundService.kt").readText()

        assertTrue(service.contains("buildProgressNotification"))
        assertTrue(service.contains("notification_title_processing"))
        assertTrue(service.contains("setProgress"))
        assertTrue(service.contains("showProgressNotification"))
        assertTrue(service.contains("startForeground(notificationId, buildProgressNotification"))
        assertTrue(service.contains("showShortcutNotification(this@ShareForegroundService)"))
        assertFalse(service.contains("notifyCompletion"))
    }

    @Test
    fun widgetDoesNotShowCurrentTrackText() {
        val widgetProvider = File("src/main/java/com/musicshare/android/widget/ShareWidgetProvider.kt").readText()

        assertFalse(widgetProvider.contains("track.displayTitle()"))
        assertFalse(widgetProvider.contains("latestTrack.displayTitle"))
        assertTrue(widgetProvider.contains("widget_status_ready"))
    }

    @Test
    fun appThemeFollowsCurrentAlbumArtwork() {
        val models = File("src/main/java/com/musicshare/android/data/Models.kt").readText()
        val handler = File("src/main/java/com/musicshare/android/poweramp/PowerampBroadcastHandler.kt").readText()
        val theme = File("src/main/java/com/musicshare/android/ui/Theme.kt").readText()
        val screen = File("src/main/java/com/musicshare/android/ui/MusicShareScreen.kt").readText()
        val activity = File("src/main/java/com/musicshare/android/MainActivity.kt").readText()

        assertTrue(models.contains("artworkColorArgb"))
        assertTrue(handler.contains("albumArtworkRepository"))
        assertTrue(theme.contains("albumArtSeedArgb"))
        assertTrue(theme.contains("deriveAlbumArtTokens"))
        assertTrue(screen.contains("AlbumArtworkBackground"))
        assertTrue(screen.contains("Modifier.blur"))
        assertTrue(activity.contains("artworkColorArgb"))
    }
}
