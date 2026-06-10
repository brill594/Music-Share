package com.musicshare.android.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumArtAccentTest {

    private fun red(argb: Long) = ((argb ushr 16) and 0xffL).toInt()
    private fun green(argb: Long) = ((argb ushr 8) and 0xffL).toInt()
    private fun blue(argb: Long) = (argb and 0xffL).toInt()

    @Test
    fun grayscaleCoverKeepsBrandOrangeWithWhiteText() {
        val tokens = deriveAlbumArtTokens(0xFF808080L, darkTheme = true)
        assertEquals(0xFFC84E00L, tokens.primaryArgb)
        assertEquals(0xFFFFFFFFL, tokens.onPrimaryArgb)
    }

    @Test
    fun blueCoverYieldsBlueAccentWithWhiteText() {
        val tokens = deriveAlbumArtTokens(0xFF1E6FE0L, darkTheme = true)
        val accent = tokens.primaryArgb
        assertTrue(
            "accent stays blue-dominant: $accent",
            blue(accent) > red(accent) && blue(accent) > green(accent),
        )
        assertEquals("dark accent -> white on-color", 0xFFFFFFFFL, tokens.onPrimaryArgb)
    }

    @Test
    fun lightYellowCoverYieldsYellowAccentWithDarkText() {
        val tokens = deriveAlbumArtTokens(0xFFEAD400L, darkTheme = true)
        val accent = tokens.primaryArgb
        assertTrue(
            "accent stays yellow-ish (red & green dominate blue): $accent",
            red(accent) > blue(accent) && green(accent) > blue(accent),
        )
        assertEquals("light accent -> black on-color", 0xFF000000L, tokens.onPrimaryArgb)
    }

    @Test
    fun dullCoverIsLiftedToAReadableAccentSaturation() {
        // A muted teal: low saturation but a real hue -> should be boosted, not orange.
        val tokens = deriveAlbumArtTokens(0xFF4A6B68L, darkTheme = true)
        assertTrue("dull but coloured cover should not fall back to orange", tokens.primaryArgb != 0xFFC84E00L)
        assertTrue(
            "teal accent keeps green/blue above red: ${tokens.primaryArgb}",
            green(tokens.primaryArgb) >= red(tokens.primaryArgb) && blue(tokens.primaryArgb) >= red(tokens.primaryArgb),
        )
    }
}
