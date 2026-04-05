package com.musicshare.android.data

import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable

object AppDefaults {
    const val schemaVersion = 1
    const val defaultPort = 2087
    const val defaultExpireAfterSeconds = 86_400
    const val defaultBitrateKbps = 96
    const val defaultSampleRateHz = 44_100
    const val defaultChannels = 2
    const val defaultMaxDurationSeconds = 900
    const val defaultMaxOutputSizeMb = 16

    val supportedAuthModes = setOf("none", "basic")
    val supportedOutputFormats = setOf("ogg", "m4a", "mp3")
    val supportedAudioCodecs = setOf("opus", "vorbis", "aac", "mp3")
    val supportedLoudnessModes = setOf("off", "normalize")
    val supportedRoles = setOf("", "user", "admin")
}

@Serializable
data class ShareDefaults(
    val expireAfterSeconds: Long = AppDefaults.defaultExpireAfterSeconds.toLong(),
)

@Serializable
data class ServerConfig(
    val baseUrl: String = "",
    val port: Int = AppDefaults.defaultPort,
    val authMode: String = "none",
    val basicAuthPassword: String = "",
)

@Serializable
data class AdminConfig(
    val enabled: Boolean = false,
    val password: String = "",
)

@Serializable
data class SessionSnapshot(
    val authType: String = "",
    val accessKey: String = "",
    val expiresAt: String = "",
    val role: String = "",
) {
    fun isValid(now: Instant = Instant.now()): Boolean {
        val parsed = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return false
        return accessKey.isNotBlank() && parsed.isAfter(now.plusSeconds(120))
    }
}

@Serializable
data class AuthLog(
    val lastAuthRequestedAt: String = "",
    val lastAuthSucceededAt: String = "",
    val lastRole: String = "user",
    val lastAuthType: String = "",
    val lastExpiresAt: String = "",
)

@Serializable
data class TranscodeConfig(
    val outputFormat: String = "ogg",
    val audioCodec: String = "opus",
    val bitrateKbps: Int = AppDefaults.defaultBitrateKbps,
    val sampleRateHz: Int = AppDefaults.defaultSampleRateHz,
    val channels: Int = AppDefaults.defaultChannels,
    val loudnessMode: String = "off",
    val maxDurationSeconds: Int = AppDefaults.defaultMaxDurationSeconds,
    val maxOutputSizeMb: Int = AppDefaults.defaultMaxOutputSizeMb,
)

@Serializable
data class UiConfig(
    val showShareStatus: Boolean = true,
)

@Serializable
data class RuntimeStatus(
    val isProcessing: Boolean = false,
    val currentStage: String = "",
    val lastError: String = "",
    val lastShareUrl: String = "",
    val lastCompletedAt: String = "",
)

@Serializable
data class CurrentTrackSnapshot(
    val powerampPath: String = "",
    val documentUri: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val artUri: String = "",
    val trackId: String = "",
    val playbackState: String = "",
    val updatedAt: String = "",
    val isResolvable: Boolean = false,
) {
    fun displayTitle(): String = title.ifBlank { "未命名曲目" }

    fun subtitle(): String {
        val left = artist.ifBlank { "未知艺术家" }
        val right = album.ifBlank { "未知专辑" }
        return "$left · $right"
    }
}

@Serializable
data class PersistedAppState(
    val schemaVersion: Int = AppDefaults.schemaVersion,
    val clientInstallId: String = "",
    val musicTreeUri: String = "",
    val shareDefaults: ShareDefaults = ShareDefaults(),
    val server: ServerConfig = ServerConfig(),
    val admin: AdminConfig = AdminConfig(),
    val session: SessionSnapshot = SessionSnapshot(),
    val authLog: AuthLog = AuthLog(),
    val transcode: TranscodeConfig = TranscodeConfig(),
    val ui: UiConfig = UiConfig(),
    val latestTrack: CurrentTrackSnapshot? = null,
    val runtime: RuntimeStatus = RuntimeStatus(),
) {
    fun hasMusicTreePermission(): Boolean = musicTreeUri.isNotBlank()

    fun normalized(): PersistedAppState {
        val safePort = server.port.coerceIn(1, 65_535)
        val authMode = server.authMode.lowercase(Locale.US).takeIf {
            it in AppDefaults.supportedAuthModes
        } ?: "none"
        val outputFormat = transcode.outputFormat.lowercase(Locale.US).takeIf {
            it in AppDefaults.supportedOutputFormats
        } ?: "ogg"
        val audioCodec = transcode.audioCodec.lowercase(Locale.US).takeIf {
            it in AppDefaults.supportedAudioCodecs
        } ?: when (outputFormat) {
            "m4a" -> "aac"
            "mp3" -> "mp3"
            else -> "opus"
        }
        val loudnessMode = transcode.loudnessMode.lowercase(Locale.US).takeIf {
            it in AppDefaults.supportedLoudnessModes
        } ?: "off"
        val sessionRole = session.role.lowercase(Locale.US).takeIf {
            it in AppDefaults.supportedRoles
        } ?: ""
        val authLogRole = authLog.lastRole.lowercase(Locale.US).takeIf {
            it in AppDefaults.supportedRoles
        } ?: "user"
        return copy(
            schemaVersion = AppDefaults.schemaVersion,
            server = server.copy(
                port = safePort,
                authMode = authMode,
            ),
            session = session.copy(role = sessionRole),
            authLog = authLog.copy(lastRole = authLogRole),
            transcode = transcode.copy(
                outputFormat = outputFormat,
                audioCodec = audioCodec,
                bitrateKbps = transcode.bitrateKbps.coerceIn(32, 320),
                sampleRateHz = transcode.sampleRateHz.coerceIn(8_000, 48_000),
                channels = transcode.channels.coerceIn(1, 2),
                loudnessMode = loudnessMode,
                maxDurationSeconds = transcode.maxDurationSeconds.coerceIn(30, 7_200),
                maxOutputSizeMb = transcode.maxOutputSizeMb.coerceIn(2, 64),
            ),
            shareDefaults = shareDefaults.copy(
                expireAfterSeconds = shareDefaults.expireAfterSeconds.coerceIn(60, 2_592_000),
            ),
        )
    }
}

@Serializable
data class ExportedConfig(
    val schemaVersion: Int = AppDefaults.schemaVersion,
    val exportedAt: String = "",
    val clientInstallId: String = "",
    val shareDefaults: ShareDefaults = ShareDefaults(),
    val server: ServerConfig = ServerConfig(),
    val admin: AdminConfig = AdminConfig(),
    val session: SessionSnapshot = SessionSnapshot(),
    val authLog: AuthLog = AuthLog(),
    val transcode: TranscodeConfig = TranscodeConfig(),
    val ui: UiConfig = UiConfig(),
)
