package com.musicshare.android.artwork

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
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
    val primary = if (darkTheme) blend(seed, white, 0.35f) else blend(seed, black, 0.18f)
    val primaryContainer = if (darkTheme) blend(seed, black, 0.35f) else blend(seed, white, 0.70f)
    val secondary = if (darkTheme) blend(seed, white, 0.18f) else blend(seed, black, 0.06f)
    val surface = if (darkTheme) blend(seed, black, 0.78f) else blend(seed, white, 0.82f)
    val surfaceVariant = if (darkTheme) blend(seed, black, 0.66f) else blend(seed, white, 0.66f)
    val background = if (darkTheme) blend(seed, black, 0.86f) else blend(seed, white, 0.88f)
    return AlbumArtTokens(
        primaryArgb = primary,
        onPrimaryArgb = readableTextOn(primary),
        primaryContainerArgb = primaryContainer,
        onPrimaryContainerArgb = readableTextOn(primaryContainer),
        secondaryArgb = secondary,
        onSecondaryArgb = readableTextOn(secondary),
        surfaceArgb = surface,
        onSurfaceArgb = readableTextOn(surface),
        surfaceVariantArgb = surfaceVariant,
        onSurfaceVariantArgb = readableTextOn(surfaceVariant),
        backgroundArgb = background,
        onBackgroundArgb = readableTextOn(background),
    )
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

private fun readableTextOn(background: Long): Long =
    if (contrastRatio(background, black) >= contrastRatio(background, white)) black else white

private fun contrastRatio(first: Long, second: Long): Float {
    val firstLuminance = relativeLuminance(first) + 0.05f
    val secondLuminance = relativeLuminance(second) + 0.05f
    return max(firstLuminance, secondLuminance) / min(firstLuminance, secondLuminance)
}

private fun relativeLuminance(argb: Long): Float {
    val red = linearized(shifted(argb, 16) / 255f)
    val green = linearized(shifted(argb, 8) / 255f)
    val blue = linearized(shifted(argb, 0) / 255f)
    return 0.2126f * red + 0.7152f * green + 0.0722f * blue
}

private fun linearized(channel: Float): Float =
    if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

private fun Float.pow(exponent: Float): Float = Math.pow(toDouble(), exponent.toDouble()).toFloat()

private fun shifted(argb: Long, shift: Int): Int = ((argb ushr shift) and 0xffL).toInt()

private fun argb(red: Int, green: Int, blue: Int): Long =
    (0xffL shl 24) or
        (red.coerceIn(0, 255).toLong() shl 16) or
        (green.coerceIn(0, 255).toLong() shl 8) or
        blue.coerceIn(0, 255).toLong()

private const val sampleEdge = 64
private const val minOpaqueAlpha = 128
private const val white = 0xffffffffL
private const val black = 0xff000000L
