package com.musicshare.android.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

fun normalizeBaseUrl(raw: String, defaultScheme: String = "https"): String {
    val trimmed = raw.trim().removeSuffix("/")
    if (trimmed.isBlank()) {
        return ""
    }
    val withScheme = if (schemePattern.containsMatchIn(trimmed)) {
        trimmed
    } else {
        "$defaultScheme://$trimmed"
    }
    val parsed = withScheme.toHttpUrlOrNull()
        ?: throw UserVisibleException("base_url 格式无效。")
    return parsed.toString().removeSuffix("/")
}
