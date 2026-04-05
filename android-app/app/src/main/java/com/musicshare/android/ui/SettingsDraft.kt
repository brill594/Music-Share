package com.musicshare.android.ui

import com.musicshare.android.data.PersistedAppState
import com.musicshare.android.data.TranscodeConfig

data class SettingsDraft(
    val baseUrl: String,
    val authMode: String,
    val basicAuthPassword: String,
    val adminEnabled: Boolean,
    val adminPassword: String,
    val expireAfterSeconds: String,
    val outputFormat: String,
    val audioCodec: String,
    val bitrateKbps: String,
    val sampleRateHz: String,
    val channels: Int,
    val maxDurationSeconds: String,
    val maxOutputSizeMb: String,
) {
    companion object {
        fun from(appState: PersistedAppState): SettingsDraft {
            return SettingsDraft(
                baseUrl = appState.server.baseUrl,
                authMode = appState.server.authMode,
                basicAuthPassword = appState.server.basicAuthPassword,
                adminEnabled = appState.admin.enabled,
                adminPassword = appState.admin.password,
                expireAfterSeconds = appState.shareDefaults.expireAfterSeconds.toString(),
                outputFormat = appState.transcode.outputFormat,
                audioCodec = appState.transcode.audioCodec,
                bitrateKbps = appState.transcode.bitrateKbps.toString(),
                sampleRateHz = appState.transcode.sampleRateHz.toString(),
                channels = appState.transcode.channels,
                maxDurationSeconds = appState.transcode.maxDurationSeconds.toString(),
                maxOutputSizeMb = appState.transcode.maxOutputSizeMb.toString(),
            )
        }

        fun preset(name: String): TranscodeConfig {
            return when (name) {
                "fast" -> TranscodeConfig(
                    outputFormat = "ogg",
                    audioCodec = "opus",
                    bitrateKbps = 64,
                    sampleRateHz = 24_000,
                    channels = 1,
                )
                "better" -> TranscodeConfig(
                    outputFormat = "m4a",
                    audioCodec = "aac",
                    bitrateKbps = 128,
                    sampleRateHz = 44_100,
                    channels = 2,
                )
                else -> TranscodeConfig(
                    outputFormat = "ogg",
                    audioCodec = "opus",
                    bitrateKbps = 96,
                    sampleRateHz = 44_100,
                    channels = 2,
                )
            }
        }
    }
}
