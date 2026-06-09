package com.musicshare.android.artwork

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class AlbumArtTokens(
    val primaryArgb: Long,
    val onPrimaryArgb: Long,
    val primaryContainerArgb: Long,
    val onPrimaryContainerArgb: Long,
    val secondaryArgb: Long,
    val onSecondaryArgb: Long,
    val surfaceArgb: Long,
    val onSurfaceArgb: Long,
    val surfaceVariantArgb: Long,
    val onSurfaceVariantArgb: Long,
    val backgroundArgb: Long,
    val onBackgroundArgb: Long,
)

fun deriveAlbumArtTokens(seedArgb: Long, darkTheme: Boolean): AlbumArtTokens {
    val seed = opaque(seedArgb)
    val accent = vibrantAccentArgb(seed)
    val onAccent = if (relativeLuminance(accent) > onColorLuminanceThreshold) black else white
    val primaryContainer = blend(accent, black, 0.72f)
    val surface = blend(seed, black, if (darkTheme) 0.90f else 0.86f)
    val surfaceVariant = blend(seed, black, if (darkTheme) 0.84f else 0.80f)
    val background = if (darkTheme) black else almostBlack
    return AlbumArtTokens(
        primaryArgb = accent,
        onPrimaryArgb = onAccent,
        primaryContainerArgb = primaryContainer,
        onPrimaryContainerArgb = white,
        secondaryArgb = powerampSecondary,
        onSecondaryArgb = white,
        surfaceArgb = surface,
        onSurfaceArgb = white,
        surfaceVariantArgb = surfaceVariant,
        onSurfaceVariantArgb = white,
        backgroundArgb = background,
        onBackgroundArgb = white,
    )
}

/**
 * Derives a vivid accent in the cover's own hue so the primary button, selected chips, switch track
 * and status dot pick up the song artwork. Dull covers are lifted to a readable saturation and the
 * lightness is clamped to a usable button band; a colourless (grayscale) cover keeps the brand
 * orange so the accent never collapses to an arbitrary hue.
 */
private fun vibrantAccentArgb(seedArgb: Long): Long {
    val r = shifted(seedArgb, 16) / 255f
    val g = shifted(seedArgb, 8) / 255f
    val b = shifted(seedArgb, 0) / 255f
    val maxChannel = maxOf(r, g, b)
    val minChannel = minOf(r, g, b)
    val delta = maxChannel - minChannel
    val lightness = (maxChannel + minChannel) / 2f
    val saturation = when {
        delta == 0f -> 0f
        lightness > 0.5f -> delta / (2f - maxChannel - minChannel)
        else -> delta / (maxChannel + minChannel)
    }
    if (saturation < minAccentChroma) return powerampOrange
    val accent = hslToArgb(
        hue = hueFraction(r, g, b, maxChannel, delta),
        saturation = saturation.coerceIn(minAccentSaturation, maxAccentSaturation),
        lightness = lightness.coerceIn(minAccentLightness, maxAccentLightness),
    )
    // Keep even deep-hued accents bright enough to read as a distinct button against the near-black
    // page background; lift toward white only as far as needed (stays below the white/black text
    // crossover, so the label colour never flips).
    return raiseToMinLuminance(accent)
}

private fun raiseToMinLuminance(argb: Long): Long {
    var color = argb
    var steps = 0
    while (relativeLuminance(color) < minAccentLuminance && steps < 16) {
        color = blend(color, white, 0.08f)
        steps++
    }
    return color
}

private fun hueFraction(r: Float, g: Float, b: Float, maxChannel: Float, delta: Float): Float {
    val hue = when (maxChannel) {
        r -> (g - b) / delta + if (g < b) 6f else 0f
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    }
    return hue / 6f
}

private fun hslToArgb(hue: Float, saturation: Float, lightness: Float): Long {
    val q = if (lightness < 0.5f) {
        lightness * (1f + saturation)
    } else {
        lightness + saturation - lightness * saturation
    }
    val p = 2f * lightness - q
    return argb(
        red = (hueToChannel(p, q, hue + 1f / 3f) * 255f).roundToInt(),
        green = (hueToChannel(p, q, hue) * 255f).roundToInt(),
        blue = (hueToChannel(p, q, hue - 1f / 3f) * 255f).roundToInt(),
    )
}

private fun hueToChannel(p: Float, q: Float, t: Float): Float {
    val normalized = when {
        t < 0f -> t + 1f
        t > 1f -> t - 1f
        else -> t
    }
    return when {
        normalized < 1f / 6f -> p + (q - p) * 6f * normalized
        normalized < 1f / 2f -> q
        normalized < 2f / 3f -> p + (q - p) * (2f / 3f - normalized) * 6f
        else -> p
    }
}

private fun relativeLuminance(argb: Long): Float {
    fun channel(value: Int): Float {
        val c = value / 255f
        return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    return 0.2126f * channel(shifted(argb, 16)) +
        0.7152f * channel(shifted(argb, 8)) +
        0.0722f * channel(shifted(argb, 0))
}

fun seedArgbFromBitmap(bitmap: Bitmap): Long {
    if (bitmap.width <= 0 || bitmap.height <= 0) return 0L
    val stepX = max(1, bitmap.width / sampleEdge)
    val stepY = max(1, bitmap.height / sampleEdge)
    var redTotal = 0L
    var greenTotal = 0L
    var blueTotal = 0L
    var weightTotal = 0L
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel ushr 24) and 0xff
            if (alpha >= minOpaqueAlpha) {
                val red = (pixel ushr 16) and 0xff
                val green = (pixel ushr 8) and 0xff
                val blue = pixel and 0xff
                val saturationWeight = 32 + (max(red, max(green, blue)) - min(red, min(green, blue)))
                val weight = alpha.toLong() * saturationWeight.toLong()
                redTotal += red.toLong() * weight
                greenTotal += green.toLong() * weight
                blueTotal += blue.toLong() * weight
                weightTotal += weight
            }
            x += stepX
        }
        y += stepY
    }
    if (weightTotal == 0L) return 0L
    return argb(
        red = (redTotal / weightTotal).toInt(),
        green = (greenTotal / weightTotal).toInt(),
        blue = (blueTotal / weightTotal).toInt(),
    )
}

private fun opaque(argb: Long): Long = argb(shifted(argb, 16), shifted(argb, 8), shifted(argb, 0))

private fun blend(from: Long, to: Long, toAmount: Float): Long {
    val clamped = toAmount.coerceIn(0f, 1f)
    val fromAmount = 1f - clamped
    return argb(
        red = (shifted(from, 16) * fromAmount + shifted(to, 16) * clamped).roundToInt(),
        green = (shifted(from, 8) * fromAmount + shifted(to, 8) * clamped).roundToInt(),
        blue = (shifted(from, 0) * fromAmount + shifted(to, 0) * clamped).roundToInt(),
    )
}
private fun shifted(argb: Long, shift: Int): Int = ((argb ushr shift) and 0xffL).toInt()

private fun argb(red: Int, green: Int, blue: Int): Long =
    (0xffL shl 24) or
        (red.coerceIn(0, 255).toLong() shl 16) or
        (green.coerceIn(0, 255).toLong() shl 8) or
        blue.coerceIn(0, 255).toLong()

private const val sampleEdge = 64
private const val minOpaqueAlpha = 128
private const val powerampOrange = 0xffc84e00L
private const val powerampSecondary = 0xff6e5d50L
private const val almostBlack = 0xff010101L
private const val white = 0xffffffffL
private const val black = 0xff000000L

// Adaptive-accent tuning: below this chroma the cover is treated as colourless and keeps the brand
// orange; otherwise the accent is pinned into a vivid, button-legible saturation/lightness band, and
// the on-accent text flips between white and black at the WCAG white/black contrast crossover.
private const val minAccentChroma = 0.12f
private const val minAccentSaturation = 0.50f
private const val maxAccentSaturation = 0.90f
private const val minAccentLightness = 0.40f
private const val maxAccentLightness = 0.58f
private const val minAccentLuminance = 0.14f
private const val onColorLuminanceThreshold = 0.179f
