package com.musicshare.android.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

private val displayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun nowIso(): String = Instant.now().toString()

fun formatDisplayTime(isoText: String): String {
    val instant = runCatching { Instant.parse(isoText) }.getOrNull() ?: return isoText
    return displayFormatter.format(instant)
}

fun formatDurationLabel(durationMs: Long): String {
    val duration = Duration.ofMillis(durationMs.coerceAtLeast(0))
    val minutes = duration.toMinutes()
    val seconds = duration.seconds % 60
    return "%d:%02d".format(minutes, seconds)
}

fun formatRemainingLabel(seconds: Long): String {
    if (seconds <= 0) {
        return "0 分钟"
    }
    return formatHoursMinutes(seconds)
}

fun formatShareExpiryStatus(
    status: String,
    remainingSeconds: Long?,
    expiresAt: String,
): String {
    val normalizedStatus = status.lowercase()
    val secondsToExpiry = remainingSeconds ?: secondsUntil(expiresAt)

    return when (normalizedStatus) {
        "terminated" -> "已终止"
        "expired" -> "已过期${formatElapsedSuffix(secondsToExpiry)}"
        else -> {
            val safeSeconds = secondsToExpiry ?: 0L
            if (safeSeconds <= 0) {
                "已过期"
            } else {
                "还有 ${formatHoursMinutes(safeSeconds)} 过期"
            }
        }
    }
}

private fun formatElapsedSuffix(secondsToExpiry: Long?): String {
    val elapsedSeconds = secondsToExpiry?.takeIf { it < 0 }?.absoluteValue ?: return ""
    return " ${formatHoursMinutes(elapsedSeconds)}"
}

private fun secondsUntil(isoText: String): Long? {
    val instant = runCatching { Instant.parse(isoText) }.getOrNull() ?: return null
    return Duration.between(Instant.now(), instant).seconds
}

private fun formatHoursMinutes(totalSeconds: Long): String {
    if (totalSeconds <= 0) {
        return "0 分钟"
    }
    val totalMinutes = Duration.ofSeconds(totalSeconds).toMinutes()
    if (totalMinutes <= 0) {
        return "不到 1 分钟"
    }
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        "${hours} 小时 ${minutes} 分钟"
    } else {
        "${totalMinutes} 分钟"
    }
}
