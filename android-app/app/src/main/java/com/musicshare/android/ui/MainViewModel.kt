package com.musicshare.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musicshare.android.MusicShareApplication
import com.musicshare.android.network.AdminBackgroundDto
import com.musicshare.android.network.AdminUsageDto
import com.musicshare.android.network.AdminUsageUpdateRequest
import com.musicshare.android.data.PersistedAppState
import com.musicshare.android.network.ShareItemDto
import com.musicshare.android.util.normalizeBaseUrl
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
    val adminBackground: AdminBackgroundDto? = null,
    val adminUsage: AdminUsageDto? = null,
    val isRefreshing: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MusicShareApplication).container
    private val clientShares = MutableStateFlow<List<ShareItemDto>>(emptyList())
    private val adminShares = MutableStateFlow<List<ShareItemDto>>(emptyList())
    private val adminBackground = MutableStateFlow<AdminBackgroundDto?>(null)
    private val adminUsage = MutableStateFlow<AdminUsageDto?>(null)
    private val isRefreshing = MutableStateFlow(false)
    private val adminMetaState =
        combine(
            adminBackground,
            adminUsage,
            isRefreshing,
        ) { background, usage, refreshing ->
            Triple(background, usage, refreshing)
        }

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiState: StateFlow<DashboardUiState> =
        combine(
            container.stateStore.state,
            clientShares,
            adminShares,
            adminMetaState,
        ) { appState, clientList, adminList, adminMeta ->
            val (background, usage, refreshing) = adminMeta
            DashboardUiState(
                appState = appState,
                clientShares = clientList,
                adminShares = adminList,
                adminBackground = background,
                adminUsage = usage,
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
                if (preferAdmin && session.role == "admin") {
                    refreshShares()
                }
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
                val background = runCatching { container.backendRepository.getAdminBackground() }.getOrNull()
                val usage = runCatching { container.backendRepository.getAdminUsage() }.getOrNull()
                Quadruple(client, admin, background, usage)
            }.onSuccess { (client, admin, background, usage) ->
                clientShares.value = client
                adminShares.value = admin
                adminBackground.value = background
                adminUsage.value = usage
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

    fun uploadAdminBackground(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            runCatching {
                container.backendRepository.uploadAdminBackground(uri)
            }.onSuccess {
                refreshShares()
                messages.tryEmit("背景图已上传。")
            }.onFailure { error ->
                messages.tryEmit(error.message ?: "背景图上传失败。")
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

    fun saveSettings(draft: SettingsDraft) {
        viewModelScope.launch {
            val normalizedBaseUrl = runCatching { normalizeBaseUrl(draft.baseUrl) }.getOrElse { error ->
                messages.tryEmit(error.message ?: "base_url 格式无效。")
                return@launch
            }
            val expireAfterSeconds = draft.expireAfterSeconds.toLongOrNull()
            if (expireAfterSeconds == null || expireAfterSeconds <= 0L) {
                messages.tryEmit("expire_after_seconds 必须是正整数。")
                return@launch
            }
            val bitrateKbps = draft.bitrateKbps.toIntOrNull()
            if (bitrateKbps == null || bitrateKbps <= 0) {
                messages.tryEmit("bitrate_kbps 必须是正整数。")
                return@launch
            }
            val sampleRateHz = draft.sampleRateHz.toIntOrNull()
            if (sampleRateHz == null || sampleRateHz <= 0) {
                messages.tryEmit("sample_rate_hz 必须是正整数。")
                return@launch
            }
            val maxDurationSeconds = draft.maxDurationSeconds.toIntOrNull()
            if (maxDurationSeconds == null || maxDurationSeconds <= 0) {
                messages.tryEmit("max_duration_seconds 必须是正整数。")
                return@launch
            }
            val maxOutputSizeMb = draft.maxOutputSizeMb.toIntOrNull()
            if (maxOutputSizeMb == null || maxOutputSizeMb <= 0) {
                messages.tryEmit("max_output_size_mb 必须是正整数。")
                return@launch
            }

            container.stateStore.update { state ->
                state.copy(
                    server = state.server.copy(
                        baseUrl = normalizedBaseUrl,
                        authMode = draft.authMode,
                        basicAuthPassword = draft.basicAuthPassword,
                    ),
                    admin = state.admin.copy(
                        enabled = draft.adminEnabled,
                        password = draft.adminPassword,
                    ),
                    shareDefaults = state.shareDefaults.copy(
                        expireAfterSeconds = expireAfterSeconds,
                    ),
                    transcode = state.transcode.copy(
                        outputFormat = draft.outputFormat,
                        audioCodec = draft.audioCodec,
                        bitrateKbps = bitrateKbps,
                        sampleRateHz = sampleRateHz,
                        channels = draft.channels,
                        maxDurationSeconds = maxDurationSeconds,
                        maxOutputSizeMb = maxOutputSizeMb,
                    ),
                )
            }
            messages.tryEmit("设置已保存。")
        }
    }

    fun saveAdminUsageLimits(draft: UsageLimitsDraft) {
        viewModelScope.launch {
            val d1RowsReadDailyLimit = draft.d1RowsReadDailyLimit.toLongOrNull()
            if (d1RowsReadDailyLimit == null || d1RowsReadDailyLimit <= 0L) {
                messages.tryEmit("D1 每日读取上限必须是正整数。")
                return@launch
            }
            val d1RowsWrittenDailyLimit = draft.d1RowsWrittenDailyLimit.toLongOrNull()
            if (d1RowsWrittenDailyLimit == null || d1RowsWrittenDailyLimit <= 0L) {
                messages.tryEmit("D1 每日写入上限必须是正整数。")
                return@launch
            }
            val d1StorageGbLimit = draft.d1StorageGbLimit.toDoubleOrNull()
            if (d1StorageGbLimit == null || d1StorageGbLimit <= 0.0) {
                messages.tryEmit("D1 存储上限必须是正数。")
                return@launch
            }
            val r2ClassARolling30dLimit = draft.r2ClassARolling30dLimit.toLongOrNull()
            if (r2ClassARolling30dLimit == null || r2ClassARolling30dLimit <= 0L) {
                messages.tryEmit("R2 Class A 近 30 天上限必须是正整数。")
                return@launch
            }
            val r2ClassBRolling30dLimit = draft.r2ClassBRolling30dLimit.toLongOrNull()
            if (r2ClassBRolling30dLimit == null || r2ClassBRolling30dLimit <= 0L) {
                messages.tryEmit("R2 Class B 近 30 天上限必须是正整数。")
                return@launch
            }
            val r2StorageGbMonthLimit = draft.r2StorageGbMonthLimit.toDoubleOrNull()
            if (r2StorageGbMonthLimit == null || r2StorageGbMonthLimit <= 0.0) {
                messages.tryEmit("R2 存储 GB-month 上限必须是正数。")
                return@launch
            }

            runCatching {
                container.backendRepository.updateAdminUsage(
                    AdminUsageUpdateRequest(
                        enabled = draft.enabled,
                        d1RowsReadDailyLimit = d1RowsReadDailyLimit,
                        d1RowsWrittenDailyLimit = d1RowsWrittenDailyLimit,
                        d1StorageGbLimit = d1StorageGbLimit,
                        r2ClassARolling30dLimit = r2ClassARolling30dLimit,
                        r2ClassBRolling30dLimit = r2ClassBRolling30dLimit,
                        r2StorageGbMonthLimit = r2StorageGbMonthLimit,
                    ),
                )
            }.onSuccess { usage ->
                adminUsage.value = usage
                messages.tryEmit("远端用量上限已保存。")
            }.onFailure { error ->
                messages.tryEmit(error.message ?: "保存远端用量上限失败。")
            }
        }
    }

    fun clearSession() {
        viewModelScope.launch {
            container.backendRepository.clearSession()
            messages.tryEmit("已清除本地短期凭证。")
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

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
