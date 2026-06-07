package com.musicshare.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test
    fun defaultThemeKeepsContentTextWhite() {
        assertEquals(Color.White.toArgb(), musicShareTextColor.toArgb())

        listOf(false, true).forEach { darkTheme ->
            val colors = defaultMusicShareColorScheme(darkTheme)

            assertEquals(Color.White.toArgb(), colors.onPrimary.toArgb())
            assertEquals(Color.White.toArgb(), colors.onPrimaryContainer.toArgb())
            assertEquals(Color.White.toArgb(), colors.onSecondary.toArgb())
            assertEquals(Color.White.toArgb(), colors.onSurface.toArgb())
            assertEquals(Color.White.toArgb(), colors.onSurfaceVariant.toArgb())
            assertEquals(Color.White.toArgb(), colors.onBackground.toArgb())
        }
    }

    @Test
    fun defaultThemeContainersAreTranslucentEnoughForArtwork() {
        listOf(false, true).forEach { darkTheme ->
            val colors = defaultMusicShareColorScheme(darkTheme)

            assertAlphaAtMost(colors.background, 0x99)
            assertAlphaAtMost(colors.surface, 0x99)
            assertAlphaAtMost(colors.surfaceVariant, 0x66)
        }
    }

    @Test
    fun screenLayersStayMoreTransparentThanPreviousChrome() {
        assertTrue(appBackgroundAlpha < 0.80f)
        assertTrue(artworkBackgroundOverlayAlpha < 0.38f)
        assertTrue(highlightCardContainerAlpha < 0.68f)
        assertTrue(shareItemContainerAlpha < 0.32f)
        assertTrue(controlContainerAlpha < 0.32f)
    }

    private fun assertAlphaAtMost(color: Color, maxAlpha: Int) {
        val alpha = (color.toArgb() ushr 24) and 0xff
        assertTrue("expected alpha <= $maxAlpha but was $alpha", alpha <= maxAlpha)
    }
}
