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

@Serializable
data class UsageCountMetricDto(
    val used: Long = 0,
    val limit: Long = 0,
    val exceeded: Boolean = false,
)

@Serializable
data class UsageStorageMetricDto(
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("used_gb") val usedGb: Double = 0.0,
    @SerialName("limit_gb") val limitGb: Double = 0.0,
    val exceeded: Boolean = false,
)

@Serializable
data class UsageR2StorageMetricDto(
    @SerialName("used_gb_month") val usedGbMonth: Double = 0.0,
    @SerialName("limit_gb_month") val limitGbMonth: Double = 0.0,
    @SerialName("live_bytes") val liveBytes: Long = 0,
    val exceeded: Boolean = false,
)

@Serializable
data class CloudflareUsageReferenceDto(
    @SerialName("rolling_window_days") val rollingWindowDays: Int = 30,
    @SerialName("d1_rows_read_daily_limit") val d1RowsReadDailyLimit: Long = 5_000_000,
    @SerialName("d1_rows_written_daily_limit") val d1RowsWrittenDailyLimit: Long = 100_000,
    @SerialName("d1_storage_gb_limit") val d1StorageGbLimit: Double = 5.0,
    @SerialName("r2_class_a_rolling_30d_limit") val r2ClassARolling30dLimit: Long = 1_000_000,
    @SerialName("r2_class_b_rolling_30d_limit") val r2ClassBRolling30dLimit: Long = 10_000_000,
    @SerialName("r2_storage_gb_month_limit") val r2StorageGbMonthLimit: Double = 10.0,
)

@Serializable
data class AdminUsageDto(
    val enabled: Boolean = true,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("cloudflare_reference") val cloudflareReference: CloudflareUsageReferenceDto = CloudflareUsageReferenceDto(),
    @SerialName("d1_rows_read_daily") val d1RowsReadDaily: UsageCountMetricDto = UsageCountMetricDto(),
    @SerialName("d1_rows_written_daily") val d1RowsWrittenDaily: UsageCountMetricDto = UsageCountMetricDto(),
    @SerialName("d1_storage") val d1Storage: UsageStorageMetricDto = UsageStorageMetricDto(),
    @SerialName("r2_class_a_rolling_30d") val r2ClassARolling30d: UsageCountMetricDto = UsageCountMetricDto(),
    @SerialName("r2_class_b_rolling_30d") val r2ClassBRolling30d: UsageCountMetricDto = UsageCountMetricDto(),
    @SerialName("r2_storage_rolling_30d") val r2StorageRolling30d: UsageR2StorageMetricDto = UsageR2StorageMetricDto(),
)

@Serializable
data class AdminUsageUpdateRequest(
    val enabled: Boolean,
    @SerialName("d1_rows_read_daily_limit") val d1RowsReadDailyLimit: Long,
    @SerialName("d1_rows_written_daily_limit") val d1RowsWrittenDailyLimit: Long,
    @SerialName("d1_storage_gb_limit") val d1StorageGbLimit: Double,
    @SerialName("r2_class_a_rolling_30d_limit") val r2ClassARolling30dLimit: Long,
    @SerialName("r2_class_b_rolling_30d_limit") val r2ClassBRolling30dLimit: Long,
    @SerialName("r2_storage_gb_month_limit") val r2StorageGbMonthLimit: Double,
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
    val isRetryCacheReady: Boolean = false,
)
