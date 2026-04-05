package com.musicshare.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.musicshare.android.data.PersistedAppState
import com.musicshare.android.network.ShareItemDto
import com.musicshare.android.util.formatDisplayTime
import com.musicshare.android.util.formatDurationLabel
import com.musicshare.android.util.formatShareExpiryStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicShareScreen(
    uiState: DashboardUiState,
    snackbarHostState: SnackbarHostState,
    onPickMusicTree: () -> Unit,
    onShareNow: () -> Unit,
    onAuthenticateUser: () -> Unit,
    onAuthenticateAdmin: () -> Unit,
    onRefreshShares: () -> Unit,
    onTerminateClientShare: (String) -> Unit,
    onTerminateAdminShare: (String) -> Unit,
    onExportConfig: () -> Unit,
    onImportConfigPreserveId: () -> Unit,
    onImportConfigReplaceId: () -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onAuthModeChange: (String) -> Unit,
    onBasicPasswordChange: (String) -> Unit,
    onAdminEnabledChange: (Boolean) -> Unit,
    onAdminPasswordChange: (String) -> Unit,
    onExpireAfterSecondsChange: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onBitrateChange: (String) -> Unit,
    onSampleRateChange: (String) -> Unit,
    onChannelsChange: (Int) -> Unit,
    onMaxDurationChange: (String) -> Unit,
    onMaxOutputSizeChange: (String) -> Unit,
    onClearSession: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Music Share") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                listOf("当前歌曲", "分享管理", "设置").forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) },
                    )
                }
            }
            when (selectedTab) {
                0 -> CurrentTrackTab(
                    appState = uiState.appState,
                    onPickMusicTree = onPickMusicTree,
                    onShareNow = onShareNow,
                )
                1 -> ShareManagementTab(
                    appState = uiState.appState,
                    clientShares = uiState.clientShares,
                    adminShares = uiState.adminShares,
                    isRefreshing = uiState.isRefreshing,
                    onAuthenticateUser = onAuthenticateUser,
                    onAuthenticateAdmin = onAuthenticateAdmin,
                    onRefreshShares = onRefreshShares,
                    onTerminateClientShare = onTerminateClientShare,
                    onTerminateAdminShare = onTerminateAdminShare,
                )
                else -> SettingsTab(
                    appState = uiState.appState,
                    onBaseUrlChange = onBaseUrlChange,
                    onPortChange = onPortChange,
                    onAuthModeChange = onAuthModeChange,
                    onBasicPasswordChange = onBasicPasswordChange,
                    onAdminEnabledChange = onAdminEnabledChange,
                    onAdminPasswordChange = onAdminPasswordChange,
                    onExpireAfterSecondsChange = onExpireAfterSecondsChange,
                    onApplyPreset = onApplyPreset,
                    onBitrateChange = onBitrateChange,
                    onSampleRateChange = onSampleRateChange,
                    onChannelsChange = onChannelsChange,
                    onMaxDurationChange = onMaxDurationChange,
                    onMaxOutputSizeChange = onMaxOutputSizeChange,
                    onExportConfig = onExportConfig,
                    onImportConfigPreserveId = onImportConfigPreserveId,
                    onImportConfigReplaceId = onImportConfigReplaceId,
                    onClearSession = onClearSession,
                )
            }
        }
    }
}

@Composable
private fun CurrentTrackTab(
    appState: PersistedAppState,
    onPickMusicTree: () -> Unit,
    onShareNow: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HighlightCard(
            title = if (appState.hasMusicTreePermission()) "目录授权已保存" else "需要授权音乐目录",
            body = if (appState.hasMusicTreePermission()) {
                "当前已保存 SAF treeUri。若切歌后 documentUri 失效，可重新授权。"
            } else {
                "首次使用前，请通过系统文档选择器授权 Poweramp 所在音乐目录。"
            },
        ) {
            Button(onClick = onPickMusicTree) {
                Text(if (appState.hasMusicTreePermission()) "重新授权目录" else "授权音乐目录")
            }
        }

        val track = appState.latestTrack
        if (track == null) {
            HighlightCard(
                title = "等待 Poweramp 广播",
                body = "打开 Poweramp 并切换一次曲目后，App 会缓存当前可分享曲目。",
            )
        } else {
            HighlightCard(
                title = track.displayTitle(),
                body = buildString {
                    append(track.subtitle())
                    append("\n时长：${formatDurationLabel(track.durationMs)}")
                    append("\n播放状态：${track.playbackState.ifBlank { "unknown" }}")
                    append("\nPoweramp 路径：${track.powerampPath}")
                    append("\nDocumentUri：${track.documentUri.ifBlank { "未解析" }}")
                    append("\n更新时间：${formatDisplayTime(track.updatedAt)}")
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AssistChip(
                        onClick = {},
                        label = { Text(if (track.isResolvable) "可访问" else "待重新解析") },
                    )
                    Button(
                        onClick = onShareNow,
                        enabled = track.isResolvable,
                    ) {
                        Text("立即分享")
                    }
                }
            }
        }

        val runtime = appState.runtime
        if (
            runtime.currentStage.isNotBlank() ||
            runtime.progressPercent >= 0 ||
            runtime.lastError.isNotBlank() ||
            runtime.lastShareUrl.isNotBlank()
        ) {
            HighlightCard(
                title = if (runtime.isProcessing) "后台任务进行中" else "最近一次结果",
                body = buildString {
                    if (runtime.currentStage.isNotBlank()) append("阶段：${runtime.currentStage}\n")
                    if (runtime.progressPercent >= 0) append("进度：${runtime.progressPercent}%\n")
                    if (runtime.lastError.isNotBlank()) append("错误：${runtime.lastError}\n")
                    if (runtime.lastShareUrl.isNotBlank()) append("链接：${runtime.lastShareUrl}\n")
                    if (runtime.lastCompletedAt.isNotBlank()) append("完成：${formatDisplayTime(runtime.lastCompletedAt)}")
                }.trim(),
            )
        }
    }
}

@Composable
private fun ShareManagementTab(
    appState: PersistedAppState,
    clientShares: List<ShareItemDto>,
    adminShares: List<ShareItemDto>,
    isRefreshing: Boolean,
    onAuthenticateUser: () -> Unit,
    onAuthenticateAdmin: () -> Unit,
    onRefreshShares: () -> Unit,
    onTerminateClientShare: (String) -> Unit,
    onTerminateAdminShare: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HighlightCard(
            title = "认证状态",
            body = buildString {
                append("当前角色：${appState.session.role.ifBlank { "未认证" }}")
                append("\n认证类型：${appState.session.authType.ifBlank { "-" }}")
                append("\n过期时间：${appState.session.expiresAt.ifBlank { "-" }}")
                append("\n最后成功认证：${appState.authLog.lastAuthSucceededAt.ifBlank { "-" }}")
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAuthenticateUser) { Text("获取用户会话") }
                Button(onClick = onAuthenticateAdmin) { Text("获取管理员会话") }
                TextButton(onClick = onRefreshShares) {
                    Text(if (isRefreshing) "刷新中..." else "刷新列表")
                }
            }
        }

        ShareSection(
            title = "我的分享",
            items = clientShares,
            onTerminate = onTerminateClientShare,
        )

        if (appState.session.role == "admin") {
            ShareSection(
                title = "后端管理",
                items = adminShares,
                onTerminate = onTerminateAdminShare,
            )
        } else {
            HighlightCard(
                title = "后端管理不可见",
                body = "需要先拿到管理员短期凭证，管理员列表才会显示。",
            )
        }
    }
}

@Composable
private fun SettingsTab(
    appState: PersistedAppState,
    onBaseUrlChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onAuthModeChange: (String) -> Unit,
    onBasicPasswordChange: (String) -> Unit,
    onAdminEnabledChange: (Boolean) -> Unit,
    onAdminPasswordChange: (String) -> Unit,
    onExpireAfterSecondsChange: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onBitrateChange: (String) -> Unit,
    onSampleRateChange: (String) -> Unit,
    onChannelsChange: (Int) -> Unit,
    onMaxDurationChange: (String) -> Unit,
    onMaxOutputSizeChange: (String) -> Unit,
    onExportConfig: () -> Unit,
    onImportConfigPreserveId: () -> Unit,
    onImportConfigReplaceId: () -> Unit,
    onClearSession: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HighlightCard(
            title = "后端连接",
            body = "所有配置修改后立即持久化。`base_url` 与 `port` 会组合成实际请求地址。",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = appState.server.baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("base_url") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = appState.server.port.toString(),
                    onValueChange = onPortChange,
                    label = { Text("port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "无需认证", "basic" to "密码认证").forEach { (value, label) ->
                        FilterChip(
                            selected = appState.server.authMode == value,
                            onClick = { onAuthModeChange(value) },
                            label = { Text(label) },
                        )
                    }
                }
                PasswordField(
                    label = "basic_auth_password",
                    value = appState.server.basicAuthPassword,
                    onValueChange = onBasicPasswordChange,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("保留管理员密码")
                    Switch(
                        checked = appState.admin.enabled,
                        onCheckedChange = onAdminEnabledChange,
                    )
                }
                PasswordField(
                    label = "admin_password",
                    value = appState.admin.password,
                    onValueChange = onAdminPasswordChange,
                )
                TextButton(onClick = onClearSession) {
                    Text("清除本地短期凭证")
                }
            }
        }

        HighlightCard(
            title = "分享与转码",
            body = "首版优先把上传闭环跑通。可直接切换预设，也可以手动调时长和大小限制。",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = appState.shareDefaults.expireAfterSeconds.toString(),
                    onValueChange = onExpireAfterSecondsChange,
                    label = { Text("expire_after_seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = false, onClick = { onApplyPreset("fast") }, label = { Text("Fast Share") })
                    FilterChip(selected = false, onClick = { onApplyPreset("balanced") }, label = { Text("Balanced") })
                    FilterChip(selected = false, onClick = { onApplyPreset("better") }, label = { Text("Better Quality") })
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = appState.transcode.bitrateKbps.toString(),
                    onValueChange = onBitrateChange,
                    label = { Text("bitrate_kbps") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = appState.transcode.sampleRateHz.toString(),
                    onValueChange = onSampleRateChange,
                    label = { Text("sample_rate_hz") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = appState.transcode.channels == 1,
                        onClick = { onChannelsChange(1) },
                        label = { Text("单声道") },
                    )
                    FilterChip(
                        selected = appState.transcode.channels == 2,
                        onClick = { onChannelsChange(2) },
                        label = { Text("双声道") },
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = appState.transcode.maxDurationSeconds.toString(),
                    onValueChange = onMaxDurationChange,
                    label = { Text("max_duration_seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = appState.transcode.maxOutputSizeMb.toString(),
                    onValueChange = onMaxOutputSizeChange,
                    label = { Text("max_output_size_mb") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Text(
                    text = "当前输出偏好：${appState.transcode.outputFormat}/${appState.transcode.audioCodec}，若设备端暂不支持，会在分享阶段给出明确错误。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HighlightCard(
            title = "导出与导入",
            body = "导出的 JSON 默认包含密码、短期凭证和认证日志。保存前请确认文件位置可信。",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onExportConfig) { Text("导出配置") }
                    Button(onClick = onImportConfigPreserveId) { Text("导入并保留安装 ID") }
                }
                Button(onClick = onImportConfigReplaceId) {
                    Text("导入并覆盖安装 ID")
                }
            }
        }
    }
}

@Composable
private fun ShareSection(
    title: String,
    items: List<ShareItemDto>,
    onTerminate: (String) -> Unit,
) {
    HighlightCard(
        title = title,
        body = if (items.isEmpty()) "暂无记录。" else "共 ${items.size} 条。",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { item ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        Text("${item.artist.ifBlank { "未知艺术家" }} · ${item.album.ifBlank { "未知专辑" }}")
                        Text(
                            "状态：${
                                formatShareExpiryStatus(
                                    status = item.status,
                                    remainingSeconds = item.remainingSeconds,
                                    expiresAt = item.expiresAt,
                                )
                            }",
                        )
                        Text("过期：${formatDisplayTime(item.expiresAt)}")
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = item.shareUrl,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onTerminate(item.shareCode) }) {
                                Text("结束")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "隐藏" else "显示")
            }
        },
        singleLine = true,
    )
}

@Composable
private fun HighlightCard(
    title: String,
    body: String,
    content: @Composable (() -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (body.isNotBlank()) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            content?.invoke()
        }
    }
}
