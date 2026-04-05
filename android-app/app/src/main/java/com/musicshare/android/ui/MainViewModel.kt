package com.musicshare.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musicshare.android.MusicShareApplication
import com.musicshare.android.data.PersistedAppState
import com.musicshare.android.data.TranscodeConfig
import com.musicshare.android.network.ShareItemDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val appState: PersistedAppState = PersistedAppState(),
    val clientShares: List<ShareItemDto> = emptyList(),
    val adminShares: List<ShareItemDto> = emptyList(),
    val isRefreshing: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MusicShareApplication).container
    private val clientShares = MutableStateFlow<List<ShareItemDto>>(emptyList())
    private val adminShares = MutableStateFlow<List<ShareItemDto>>(emptyList())
    private val isRefreshing = MutableStateFlow(false)

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiState: StateFlow<DashboardUiState> =
        combine(
            container.stateStore.state,
            clientShares,
            adminShares,
            isRefreshing,
        ) { appState, clientList, adminList, refreshing ->
            DashboardUiState(
                appState = appState,
                clientShares = clientList,
                adminShares = adminList,
                isRefreshing = refreshing,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DashboardUiState(),
        )

    init {
        viewModelScope.launch {
            container.stateStore.ensureClientInstallId()
            container.shareCoordinator.refreshTrackResolution()
        }
    }

    fun persistMusicTree(uri: Uri) {
        viewModelScope.launch {
            container.stateStore.update { state ->
                state.copy(musicTreeUri = uri.toString())
            }
            container.shareCoordinator.refreshTrackResolution()
            messages.tryEmit("已保存音乐目录授权。")
        }
    }

    fun authenticate(preferAdmin: Boolean) {
        viewModelScope.launch {
            runCatching {
                container.backendRepository.ensureSession(preferAdmin = preferAdmin)
            }.onSuccess { session ->
                messages.tryEmit("已获得 ${session.role.ifBlank { "用户" }} 会话。")
            }.onFailure { error ->
                messages.tryEmit(error.message ?: "认证失败。")
            }
        }
    }

    fun refreshShares() {
        viewModelScope.launch {
            isRefreshing.value = true
            runCatching {
                val client = container.backendRepository.listClientShares()
                val admin = runCatching { container.backendRepository.listAdminTracks() }.getOrDefault(emptyList())
                client to admin
            }.onSuccess { (client, admin) ->
                clientShares.value = client
                adminShares.value = admin
            }.onFailure { error ->
                messages.tryEmit(error.message ?: "刷新分享失败。")
            }
            isRefreshing.value = false
        }
    }

    fun terminateClientShare(shareCode: String) {
        viewModelScope.launch {
            runCatching {
                container.backendRepository.terminateClientShare(shareCode)
            }.onSuccess {
                refreshShares()
                messages.tryEmit("已提前结束分享。")
            }.onFailure { error ->
                messages.tryEmit(error.message ?: "结束分享失败。")
            }
        }
    }

    fun terminateAdminShare(shareCode: String) {
        viewModelScope.launch {
            runCatching {
                container.backendRepository.terminateAdminShare(shareCode)
            }.onSuccess {
                refreshShares()
                messages.tryEmit("已结束远端分享。")
            }.onFailure { error ->
                messages.tryEmit(error.message ?: "管理员操作失败。")
            }
        }
    }

    fun exportConfig(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            runCatching {
                container.configTransferManager.exportTo(uri)
            }.onSuccess {
                messages.tryEmit("配置已导出。")
            }.onFailure { error ->
                messages.tryEmit(error.message ?: "导出失败。")
            }
        }
    }

    fun importConfig(uri: Uri?, preserveInstallId: Boolean) {
        if (uri == null) return
        viewModelScope.launch {
            runCatching {
                container.configTransferManager.importFrom(uri, preserveInstallId)
                container.shareCoordinator.refreshTrackResolution()
            }.onSuccess {
                refreshShares()
                messages.tryEmit(if (preserveInstallId) "配置已导入，保留了当前安装 ID。" else "配置已导入。")
            }.onFailure { error ->
                messages.tryEmit(error.message ?: "导入失败。")
            }
        }
    }

    fun updateBaseUrl(value: String) = persist { it.copy(server = it.server.copy(baseUrl = value)) }

    fun updatePort(value: String) {
        value.toIntOrNull()?.let { port ->
            persist { state -> state.copy(server = state.server.copy(port = port)) }
        }
    }

    fun updateAuthMode(value: String) = persist { it.copy(server = it.server.copy(authMode = value)) }

    fun updateBasicPassword(value: String) =
        persist { it.copy(server = it.server.copy(basicAuthPassword = value)) }

    fun updateAdminEnabled(value: Boolean) = persist { it.copy(admin = it.admin.copy(enabled = value)) }

    fun updateAdminPassword(value: String) = persist { it.copy(admin = it.admin.copy(password = value)) }

    fun updateExpireAfterSeconds(value: String) {
        value.toLongOrNull()?.let { seconds ->
            persist { state ->
                state.copy(shareDefaults = state.shareDefaults.copy(expireAfterSeconds = seconds))
            }
        }
    }

    fun applyPreset(preset: String) {
        val config = when (preset) {
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
        persist { state ->
            state.copy(
                transcode = state.transcode.copy(
                    outputFormat = config.outputFormat,
                    audioCodec = config.audioCodec,
                    bitrateKbps = config.bitrateKbps,
                    sampleRateHz = config.sampleRateHz,
                    channels = config.channels,
                ),
            )
        }
    }

    fun updateBitrate(value: String) {
        value.toIntOrNull()?.let { bitrate ->
            persist { state -> state.copy(transcode = state.transcode.copy(bitrateKbps = bitrate)) }
        }
    }

    fun updateSampleRate(value: String) {
        value.toIntOrNull()?.let { sampleRate ->
            persist { state -> state.copy(transcode = state.transcode.copy(sampleRateHz = sampleRate)) }
        }
    }

    fun updateChannels(value: Int) =
        persist { state -> state.copy(transcode = state.transcode.copy(channels = value.coerceIn(1, 2))) }

    fun updateMaxDuration(value: String) {
        value.toIntOrNull()?.let { seconds ->
            persist { state -> state.copy(transcode = state.transcode.copy(maxDurationSeconds = seconds)) }
        }
    }

    fun updateMaxOutputSize(value: String) {
        value.toIntOrNull()?.let { sizeMb ->
            persist { state -> state.copy(transcode = state.transcode.copy(maxOutputSizeMb = sizeMb)) }
        }
    }

    fun clearSession() {
        viewModelScope.launch {
            container.backendRepository.clearSession()
            messages.tryEmit("已清除本地短期凭证。")
        }
    }

    private fun persist(transform: (PersistedAppState) -> PersistedAppState) {
        viewModelScope.launch {
            container.stateStore.update(transform)
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(application) as T
                }
            }
    }
}
