package com.musicshare.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.musicshare.android.artwork.AlbumArtTokens
import com.musicshare.android.artwork.deriveAlbumArtTokens

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A4D3B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEEDB),
    onPrimaryContainer = Color(0xFF0B261C),
    secondary = Color(0xFF52665C),
    surface = Color(0xFFF6FBF7),
    background = Color(0xFFF2F7F3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6E5C4),
    onPrimary = Color(0xFF0B261C),
    primaryContainer = Color(0xFF245540),
    onPrimaryContainer = Color(0xFFD0F5E1),
    secondary = Color(0xFFB7CCC0),
    surface = Color(0xFF101714),
    background = Color(0xFF0C120F),
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
            if (darkTheme) DarkColors else LightColors
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

private fun lightAlbumArtColorScheme(tokens: AlbumArtTokens): ColorScheme = lightColorScheme(
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
