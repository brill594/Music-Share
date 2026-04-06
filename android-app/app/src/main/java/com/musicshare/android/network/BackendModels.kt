package com.musicshare.android.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val password: String,
)

@Serializable
data class LoginResponse(
    val role: String,
    @SerialName("auth_type") val authType: String,
    @SerialName("session_key") val sessionKey: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class ShareItemDto(
    @SerialName("uuid") val uuid: String? = null,
    @SerialName("share_code") val shareCode: String,
    val title: String,
    val artist: String,
    val album: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("audio_mime") val audioMime: String,
    @SerialName("share_url") val shareUrl: String,
    @SerialName("track_url") val trackUrl: String,
    @SerialName("stream_url") val streamUrl: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("background_url") val backgroundUrl: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    val status: String,
    @SerialName("client_created_at") val clientCreatedAt: String? = null,
    @SerialName("terminated_at") val terminatedAt: String? = null,
    @SerialName("remaining_seconds") val remainingSeconds: Long? = null,
    @SerialName("client_install_id") val clientInstallId: String? = null,
)

@Serializable
data class ShareListResponse(
    val items: List<ShareItemDto> = emptyList(),
)

@Serializable
data class AdminBackgroundDto(
    val configured: Boolean = false,
    @SerialName("background_url") val backgroundUrl: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

data class PreparedUpload(
    val audioFile: java.io.File,
    val audioMimeType: String,
    val coverFile: java.io.File?,
    val coverMimeType: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val clientCreatedAt: String,
    val expireAfterSeconds: Long,
    val isFromPreparedCache: Boolean = false,
)
