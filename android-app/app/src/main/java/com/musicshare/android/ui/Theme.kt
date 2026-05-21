package com.musicshare.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.musicshare.android.artwork.AlbumArtTokens
import com.musicshare.android.artwork.deriveAlbumArtTokens

private val LightColors = darkColorScheme(
    primary = Color(0xFFC84E00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A1B00),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF6E5D50),
    onSecondary = Color(0xFFFFFFFF),
    surface = Color(0xCC15100C),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xB3291A12),
    onSurfaceVariant = Color(0xFFFFFFFF),
    background = Color(0xCC000000),
    onBackground = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC84E00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A1B00),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF6E5D50),
    onSecondary = Color(0xFFFFFFFF),
    surface = Color(0xCC15100C),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xB3291A12),
    onSurfaceVariant = Color(0xFFFFFFFF),
    background = Color(0xCC000000),
    onBackground = Color(0xFFFFFFFF),
)

@Composable
fun MusicShareTheme(
    albumArtSeedArgb: Long? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = remember(darkTheme, albumArtSeedArgb) {
        val seed = albumArtSeedArgb?.takeIf { it != 0L }
        if (seed == null) {
            defaultMusicShareColorScheme(darkTheme)
        } else {
            albumArtColorScheme(seed, darkTheme)
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

private fun albumArtColorScheme(seedArgb: Long, darkTheme: Boolean): ColorScheme {
    val tokens = deriveAlbumArtTokens(seedArgb, darkTheme)
    return if (darkTheme) darkAlbumArtColorScheme(tokens) else lightAlbumArtColorScheme(tokens)
}

internal fun defaultMusicShareColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) DarkColors else LightColors

private fun lightAlbumArtColorScheme(tokens: AlbumArtTokens): ColorScheme = darkColorScheme(
    primary = tokens.primaryArgb.toColor(),
    onPrimary = tokens.onPrimaryArgb.toColor(),
    primaryContainer = tokens.primaryContainerArgb.toColor(),
    onPrimaryContainer = tokens.onPrimaryContainerArgb.toColor(),
    secondary = tokens.secondaryArgb.toColor(),
    onSecondary = tokens.onSecondaryArgb.toColor(),
    surface = tokens.surfaceArgb.toColor(),
    onSurface = tokens.onSurfaceArgb.toColor(),
    surfaceVariant = tokens.surfaceVariantArgb.toColor(),
    onSurfaceVariant = tokens.onSurfaceVariantArgb.toColor(),
    background = tokens.backgroundArgb.toColor(),
    onBackground = tokens.onBackgroundArgb.toColor(),
)

private fun darkAlbumArtColorScheme(tokens: AlbumArtTokens): ColorScheme = darkColorScheme(
    primary = tokens.primaryArgb.toColor(),
    onPrimary = tokens.onPrimaryArgb.toColor(),
    primaryContainer = tokens.primaryContainerArgb.toColor(),
    onPrimaryContainer = tokens.onPrimaryContainerArgb.toColor(),
    secondary = tokens.secondaryArgb.toColor(),
    onSecondary = tokens.onSecondaryArgb.toColor(),
    surface = tokens.surfaceArgb.toColor(),
    onSurface = tokens.onSurfaceArgb.toColor(),
    surfaceVariant = tokens.surfaceVariantArgb.toColor(),
    onSurfaceVariant = tokens.onSurfaceVariantArgb.toColor(),
    background = tokens.backgroundArgb.toColor(),
    onBackground = tokens.onBackgroundArgb.toColor(),
)

internal fun argbLongToColor(argb: Long): Color = Color(argb.toInt())

private fun Long.toColor(): Color = argbLongToColor(this)
