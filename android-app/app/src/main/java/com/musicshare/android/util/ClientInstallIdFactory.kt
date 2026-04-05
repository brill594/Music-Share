package com.musicshare.android.util

import java.security.SecureRandom
import java.util.Base64

object ClientInstallIdFactory {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
