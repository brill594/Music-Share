package com.musicshare.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
fun MusicShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
