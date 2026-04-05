package com.musicshare.android.util

import android.content.Context
import android.net.Uri
import com.musicshare.android.data.AppDefaults
import com.musicshare.android.data.AppStateStore
import com.musicshare.android.data.ExportedConfig
import com.musicshare.android.data.PersistedAppState
import com.musicshare.android.data.SessionSnapshot
import java.util.Locale
import kotlinx.serialization.json.Json

class ConfigTransferManager(
    private val context: Context,
    private val stateStore: AppStateStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    suspend fun exportTo(uri: Uri) {
        val current = stateStore.read()
        val payload = ExportedConfig(
            schemaVersion = AppDefaults.schemaVersion,
            exportedAt = nowIso(),
            clientInstallId = current.clientInstallId,
            shareDefaults = current.shareDefaults,
            server = current.server,
            admin = current.admin,
            session = current.session,
            authLog = current.authLog,
            transcode = current.transcode,
            ui = current.ui,
        )
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(json.encodeToString(ExportedConfig.serializer(), payload))
        } ?: throw UserVisibleException("无法写入配置文件。")
    }

    suspend fun importFrom(uri: Uri, preserveInstallId: Boolean) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: throw UserVisibleException("无法读取配置文件。")

        val imported = runCatching { json.decodeFromString<ExportedConfig>(text) }.getOrNull()
            ?: throw UserVisibleException("配置文件格式无效。")
        if (imported.schemaVersion != AppDefaults.schemaVersion) {
            throw UserVisibleException("暂不支持该 schema_version。")
        }

        val current = stateStore.read()
        val next = PersistedAppState(
            schemaVersion = AppDefaults.schemaVersion,
            clientInstallId = if (preserveInstallId) current.clientInstallId else validateInstallId(imported.clientInstallId),
            musicTreeUri = current.musicTreeUri,
            shareDefaults = imported.shareDefaults,
            server = imported.server.copy(
                baseUrl = normalizeBaseUrl(imported.server.baseUrl),
                authMode = imported.server.authMode.lowercase(Locale.US).takeIf {
                    it in AppDefaults.supportedAuthModes
                } ?: "none",
            ),
            admin = imported.admin,
            session = validateSession(imported.session),
            authLog = imported.authLog,
            transcode = imported.transcode,
            ui = imported.ui,
            latestTrack = current.latestTrack,
            runtime = current.runtime.copy(lastError = ""),
        ).normalized()
        stateStore.overwrite(next)
    }

    private fun validateInstallId(value: String): String {
        return value.takeIf { it.matches(Regex("^[A-Za-z0-9._-]{8,128}$")) }
            ?: throw UserVisibleException("client_install_id 非法。")
    }

    private fun validateSession(value: SessionSnapshot): SessionSnapshot {
        if (value.accessKey.isBlank() || value.expiresAt.isBlank()) {
            return SessionSnapshot()
        }
        return runCatching { java.time.Instant.parse(value.expiresAt) }
            .map { value }
            .getOrElse { SessionSnapshot() }
    }
}
