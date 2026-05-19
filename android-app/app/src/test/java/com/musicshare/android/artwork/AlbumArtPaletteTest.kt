package com.musicshare.android.artwork

import androidx.compose.ui.graphics.toArgb
import com.musicshare.android.ui.argbLongToColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumArtPaletteTest {
    @Test
    fun derivedTokensStayOpaqueAndFollowSeedHue() {
        val tokens = deriveAlbumArtTokens(0xffff3300L, darkTheme = false)

        assertEquals(0xffL, tokens.primaryArgb ushr 24)
        assertEquals(0xffL, tokens.backgroundArgb ushr 24)
        assertTrue(red(tokens.primaryArgb) > blue(tokens.primaryArgb))
        assertTrue(red(tokens.primaryContainerArgb) > blue(tokens.primaryContainerArgb))
        assertNotEquals(tokens.primaryArgb, tokens.backgroundArgb)
    }

    @Test
    fun darkThemeUsesDarkerBackgroundThanLightTheme() {
        val light = deriveAlbumArtTokens(0xff3366ccL, darkTheme = false)
        val dark = deriveAlbumArtTokens(0xff3366ccL, darkTheme = true)

        assertTrue(luminance(dark.backgroundArgb) < luminance(light.backgroundArgb))
        assertTrue(contrastRatio(dark.backgroundArgb, dark.onBackgroundArgb) >= 4.5)
        assertTrue(contrastRatio(light.backgroundArgb, light.onBackgroundArgb) >= 4.5)
    }

    @Test
    fun composeColorConversionPreservesArgb() {
        assertEquals(0xff3366cc.toInt(), argbLongToColor(0xff3366ccL).toArgb())
    }

    @Test
    fun derivedTokensKeepAllTextPairsReadable() {
        listOf(0xffff8800L, 0xff00aaccL, 0xff669966L, 0xff777777L).forEach { seed ->
            listOf(false, true).forEach { darkTheme ->
                val tokens = deriveAlbumArtTokens(seed, darkTheme)

                assertTrue(contrastRatio(tokens.primaryArgb, tokens.onPrimaryArgb) >= 4.5)
                assertTrue(contrastRatio(tokens.primaryContainerArgb, tokens.onPrimaryContainerArgb) >= 4.5)
                assertTrue(contrastRatio(tokens.secondaryArgb, tokens.onSecondaryArgb) >= 4.5)
                assertTrue(contrastRatio(tokens.surfaceArgb, tokens.onSurfaceArgb) >= 4.5)
                assertTrue(contrastRatio(tokens.surfaceVariantArgb, tokens.onSurfaceVariantArgb) >= 4.5)
                assertTrue(contrastRatio(tokens.backgroundArgb, tokens.onBackgroundArgb) >= 4.5)
            }
        }
    }

    private fun red(argb: Long): Int = ((argb ushr 16) and 0xff).toInt()

    private fun blue(argb: Long): Int = (argb and 0xff).toInt()

    private fun contrastRatio(first: Long, second: Long): Double {
        val firstLuminance = luminance(first) + 0.05
        val secondLuminance = luminance(second) + 0.05
        return maxOf(firstLuminance, secondLuminance) / minOf(firstLuminance, secondLuminance)
    }

    private fun luminance(argb: Long): Double {
        val red = linearized(((argb ushr 16) and 0xff) / 255.0)
        val green = linearized(((argb ushr 8) and 0xff) / 255.0)
        val blue = linearized((argb and 0xff) / 255.0)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun linearized(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
}
