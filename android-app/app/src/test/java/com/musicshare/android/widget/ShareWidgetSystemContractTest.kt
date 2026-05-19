package com.musicshare.android.widget

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareWidgetSystemContractTest {
    @Test
    fun homeScreenWidgetIsRemoved() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = File("src/main/java/com/musicshare/android/service/ShareForegroundService.kt").readText()
        val handler = File("src/main/java/com/musicshare/android/poweramp/PowerampBroadcastHandler.kt").readText()

        assertFalse(File("src/main/java/com/musicshare/android/widget/ShareWidgetProvider.kt").exists())
        assertFalse(File("src/main/res/layout/share_widget.xml").exists())
        assertFalse(File("src/main/res/xml/share_widget_info.xml").exists())
        assertFalse(File("src/main/res/drawable/bg_share_widget.xml").exists())
        assertFalse(File("src/main/res/drawable/bg_share_widget_action.xml").exists())
        assertFalse(manifest.contains("ShareWidgetProvider"))
        assertFalse(manifest.contains("APPWIDGET_UPDATE"))
        assertFalse(service.contains("ShareWidgetProvider"))
        assertFalse(handler.contains("ShareWidgetProvider"))
    }

    @Test
    fun foregroundServiceUpdatesNotificationWithCompletionResult() {
        val service = File("src/main/java/com/musicshare/android/service/ShareForegroundService.kt").readText()

        assertTrue(service.contains("showResultNotification"))
        assertTrue(service.contains("buildResultNotification"))
        assertTrue(service.contains("notification_title_ready"))
        assertTrue(service.contains("notification_title_failed"))
        assertFalse(service.contains("showShortcutNotification(this@ShareForegroundService)"))
        assertTrue(service.contains("hasActiveShareNotification"))
        assertTrue(service.contains("activeNotifications"))
        assertFalse(service.contains("refreshWidgetIfDue"))
        assertTrue(service.contains("val resultPosted = showResultNotification"))
        assertTrue(service.contains("STOP_FOREGROUND_REMOVE"))
    }

    @Test
    fun activityDoesNotOverwriteActiveOrResultNotificationWithShortcut() {
        val activity = File("src/main/java/com/musicshare/android/MainActivity.kt").readText()

        assertTrue(activity.contains("showCurrentShareNotification"))
        assertTrue(activity.contains("runtime.isProcessing"))
        assertTrue(activity.contains("ShareForegroundService.showResultNotification"))
        assertFalse(activity.contains("ShareForegroundService.showShortcutNotification(this)"))
    }

    @Test
    fun launcherIconUsesGrayPalette() {
        val background = File("src/main/res/values/ic_launcher_background.xml").readText()
        val foreground = File("src/main/res/drawable/ic_launcher_foreground.xml").readText()

        assertTrue(background.contains("#2E2E2E"))
        assertTrue(foreground.contains("#BDBDBD"))
    }

    @Test
    fun lightSystemBarsUseLightIconsForDarkAppBackground() {
        val theme = File("src/main/res/values/themes.xml").readText()

        assertFalse(theme.contains("android:windowLightStatusBar\" tools:targetApi=\"23\">true"))
        assertTrue(theme.contains("android:windowLightStatusBar\" tools:targetApi=\"23\">false"))
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
