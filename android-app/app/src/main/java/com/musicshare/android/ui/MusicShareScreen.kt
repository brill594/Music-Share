package com.musicshare.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.musicshare.android.data.PersistedAppState
import com.musicshare.android.network.AdminBackgroundDto
import com.musicshare.android.network.AdminUsageDto
import com.musicshare.android.network.ShareItemDto
import com.musicshare.android.util.formatDisplayTime
import com.musicshare.android.util.formatDurationLabel
import com.musicshare.android.util.formatShareExpiryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
    onUploadAdminBackground: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfigPreserveId: () -> Unit,
    onImportConfigReplaceId: () -> Unit,
    onSaveSettings: (SettingsDraft) -> Unit,
    onSaveUsageLimits: (UsageLimitsDraft) -> Unit,
    onClearSession: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    AlbumArtworkBackground(artUri = uiState.appState.latestTrack?.artUri.orEmpty()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Music Share") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
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
                        adminBackground = uiState.adminBackground,
                        isRefreshing = uiState.isRefreshing,
                        onAuthenticateUser = onAuthenticateUser,
                        onAuthenticateAdmin = onAuthenticateAdmin,
                        onRefreshShares = onRefreshShares,
                        onTerminateClientShare = onTerminateClientShare,
                        onTerminateAdminShare = onTerminateAdminShare,
                        onUploadAdminBackground = onUploadAdminBackground,
                    )
                    else -> SettingsTab(
                        appState = uiState.appState,
                        adminUsage = uiState.adminUsage,
                        onExportConfig = onExportConfig,
                        onImportConfigPreserveId = onImportConfigPreserveId,
                        onImportConfigReplaceId = onImportConfigReplaceId,
                        onSaveSettings = onSaveSettings,
                        onSaveUsageLimits = onSaveUsageLimits,
                        onClearSession = onClearSession,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumArtworkBackground(
    artUri: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val artworkBitmap by produceState<ImageBitmap?>(initialValue = null, artUri, context) {
        value = withContext(Dispatchers.IO) {
            decodeArtworkBitmap(context, artUri)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        artworkBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.blur(36.dp).fillMaxSize().alpha(0.56f),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.58f)),
            )
        }
        content()
    }
}

private fun decodeArtworkBitmap(context: Context, artUri: String) = runCatching {
    if (artUri.isBlank()) return@runCatching null
    val uri = Uri.parse(artUri)
    val maxEdge = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        maxBackgroundBitmapEdge
    } else {
        maxFallbackBitmapEdge
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    val sampleSize = calculateArtworkSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val decoded = BitmapFactory.decodeStream(
            stream,
            null,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return@use null
        val displayBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            decoded
        } else {
            boxBlur(decoded, fallbackBlurRadius).also { blurred ->
                if (blurred !== decoded) decoded.recycle()
            }
        }
        displayBitmap.asImageBitmap()
    }
}.getOrNull()

private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
    if (radius <= 0 || source.width <= 1 || source.height <= 1) return source
    val width = source.width
    val height = source.height
    val sourcePixels = IntArray(width * height)
    val horizontalPixels = IntArray(sourcePixels.size)
    val outputPixels = IntArray(sourcePixels.size)
    source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
    blurHorizontal(sourcePixels, horizontalPixels, width, height, radius)
    blurVertical(horizontalPixels, outputPixels, width, height, radius)
    return Bitmap.createBitmap(outputPixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun blurHorizontal(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int) {
    val diameter = radius * 2 + 1
    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (delta in -radius..radius) {
                val pixel = source[rowOffset + (x + delta).coerceIn(0, width - 1)]
                alpha += (pixel ushr 24) and 0xff
                red += (pixel ushr 16) and 0xff
                green += (pixel ushr 8) and 0xff
                blue += pixel and 0xff
            }
            target[rowOffset + x] = packArgb(alpha / diameter, red / diameter, green / diameter, blue / diameter)
        }
    }
}

private fun blurVertical(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int) {
    val diameter = radius * 2 + 1
    for (y in 0 until height) {
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (delta in -radius..radius) {
                val pixel = source[(y + delta).coerceIn(0, height - 1) * width + x]
                alpha += (pixel ushr 24) and 0xff
                red += (pixel ushr 16) and 0xff
                green += (pixel ushr 8) and 0xff
                blue += pixel and 0xff
            }
            target[y * width + x] = packArgb(alpha / diameter, red / diameter, green / diameter, blue / diameter)
        }
    }
}

private fun packArgb(alpha: Int, red: Int, green: Int, blue: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255)

private fun calculateArtworkSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= maxEdge && height <= maxEdge) return 1
    var sampleSize = 1
    var sampledWidth = width
    var sampledHeight = height
    while (sampledWidth > maxEdge || sampledHeight > maxEdge) {
        sampleSize *= 2
        sampledWidth = width / sampleSize
        sampledHeight = height / sampleSize
    }
    return sampleSize.coerceAtLeast(1)
}

private const val maxBackgroundBitmapEdge = 1080
private const val maxFallbackBitmapEdge = 360
private const val fallbackBlurRadius = 12

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
                "当前已保存音乐目录授权。如遇到无法读取当前歌曲，可重新授权。"
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
    adminBackground: AdminBackgroundDto?,
    isRefreshing: Boolean,
    onAuthenticateUser: () -> Unit,
    onAuthenticateAdmin: () -> Unit,
    onRefreshShares: () -> Unit,
    onTerminateClientShare: (String) -> Unit,
    onTerminateAdminShare: (String) -> Unit,
    onUploadAdminBackground: () -> Unit,
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
            title = "后端同步",
            body = "主动从后端拉取目前已有的音乐与分享信息，用于手动刷新管理列表。",
        ) {
            Button(
                onClick = onRefreshShares,
                enabled = !isRefreshing,
            ) {
                Text(if (isRefreshing) "正在从后端拉取..." else "从后端拉取已有音乐信息")
            }
        }

        HighlightCard(
            title = "认证状态",
            body = buildString {
                append("当前角色：${appState.session.role.ifBlank { "未认证" }}")
                if (appState.session.expiresAt.isNotBlank()) {
                    append("\n有效至：${formatDisplayTime(appState.session.expiresAt)}")
                }
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAuthenticateUser) { Text("获取用户会话") }
                Button(onClick = onAuthenticateAdmin) { Text("获取管理员会话") }
            }
        }

        ShareSection(
            title = "我的分享",
            items = clientShares,
            onTerminate = onTerminateClientShare,
        )

        if (appState.session.role == "admin") {
            HighlightCard(
                title = "背景图管理",
                body = buildString {
                    append(if (adminBackground?.configured == true) "当前已配置全局背景图。" else "当前未配置全局背景图。")
                    if (!adminBackground?.updatedAt.isNullOrBlank()) {
                        append("\n最近更新：${formatDisplayTime(adminBackground?.updatedAt.orEmpty())}")
                    }
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onUploadAdminBackground) {
                        Text("设置背景图")
                    }
                }
            }

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
    adminUsage: AdminUsageDto?,
    onExportConfig: () -> Unit,
    onImportConfigPreserveId: () -> Unit,
    onImportConfigReplaceId: () -> Unit,
    onSaveSettings: (SettingsDraft) -> Unit,
    onSaveUsageLimits: (UsageLimitsDraft) -> Unit,
    onClearSession: () -> Unit,
) {
    val sourceDraft = remember(
        appState.server,
        appState.admin,
        appState.shareDefaults,
        appState.transcode,
    ) {
        SettingsDraft.from(appState)
    }
    var draft by remember(sourceDraft) { mutableStateOf(sourceDraft) }
    val usageSourceDraft = remember(adminUsage) {
        adminUsage?.let { UsageLimitsDraft.from(it) }
    }
    var usageDraft by remember(usageSourceDraft) { mutableStateOf(usageSourceDraft) }
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HighlightCard(
            title = "设置草稿",
            body = if (draft == sourceDraft) "当前没有未保存修改。" else "你有未保存修改，点击保存后才会生效。",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onSaveSettings(draft) },
                    enabled = draft != sourceDraft,
                ) {
                    Text("保存设置")
                }
                TextButton(
                    onClick = { draft = sourceDraft },
                    enabled = draft != sourceDraft,
                ) {
                    Text("撤销修改")
                }
            }
        }

        HighlightCard(
            title = "后端连接",
            body = "`base_url` 直接填写完整入口地址。输入裸域名会在保存时自动补成 `https://`，非标准端口请直接写在 `base_url` 里。",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.baseUrl,
                    onValueChange = { draft = draft.copy(baseUrl = it) },
                    label = { Text("base_url") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "无需认证", "basic" to "密码认证").forEach { (value, label) ->
                        FilterChip(
                            selected = draft.authMode == value,
                            onClick = { draft = draft.copy(authMode = value) },
                            label = { Text(label) },
                        )
                    }
                }
                PasswordField(
                    label = "basic_auth_password",
                    value = draft.basicAuthPassword,
                    onValueChange = { draft = draft.copy(basicAuthPassword = it) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("保留管理员密码")
                    Switch(
                        checked = draft.adminEnabled,
                        onCheckedChange = { draft = draft.copy(adminEnabled = it) },
                    )
                }
                PasswordField(
                    label = "admin_password",
                    value = draft.adminPassword,
                    onValueChange = { draft = draft.copy(adminPassword = it) },
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
                    value = draft.expireAfterSeconds,
                    onValueChange = { draft = draft.copy(expireAfterSeconds = it) },
                    label = { Text("expire_after_seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            val preset = SettingsDraft.preset("fast")
                            draft = draft.copy(
                                outputFormat = preset.outputFormat,
                                audioCodec = preset.audioCodec,
                                bitrateKbps = preset.bitrateKbps.toString(),
                                sampleRateHz = preset.sampleRateHz.toString(),
                                channels = preset.channels,
                            )
                        },
                        label = { Text("Fast Share") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val preset = SettingsDraft.preset("balanced")
                            draft = draft.copy(
                                outputFormat = preset.outputFormat,
                                audioCodec = preset.audioCodec,
                                bitrateKbps = preset.bitrateKbps.toString(),
                                sampleRateHz = preset.sampleRateHz.toString(),
                                channels = preset.channels,
                            )
                        },
                        label = { Text("Balanced") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val preset = SettingsDraft.preset("better")
                            draft = draft.copy(
                                outputFormat = preset.outputFormat,
                                audioCodec = preset.audioCodec,
                                bitrateKbps = preset.bitrateKbps.toString(),
                                sampleRateHz = preset.sampleRateHz.toString(),
                                channels = preset.channels,
                            )
                        },
                        label = { Text("Better Quality") },
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.bitrateKbps,
                    onValueChange = { draft = draft.copy(bitrateKbps = it) },
                    label = { Text("bitrate_kbps") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.sampleRateHz,
                    onValueChange = { draft = draft.copy(sampleRateHz = it) },
                    label = { Text("sample_rate_hz") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.channels == 1,
                        onClick = { draft = draft.copy(channels = 1) },
                        label = { Text("单声道") },
                    )
                    FilterChip(
                        selected = draft.channels == 2,
                        onClick = { draft = draft.copy(channels = 2) },
                        label = { Text("双声道") },
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.maxDurationSeconds,
                    onValueChange = { draft = draft.copy(maxDurationSeconds = it) },
                    label = { Text("max_duration_seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.maxOutputSizeMb,
                    onValueChange = { draft = draft.copy(maxOutputSizeMb = it) },
                    label = { Text("max_output_size_mb") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Text(
                    text = "当前草稿输出偏好：${draft.outputFormat}/${draft.audioCodec}，保存后才会用于实际转码。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (adminUsage == null) {
            HighlightCard(
                title = "远端用量上限",
                body = "先获取管理员会话并刷新一次，才会显示 Cloudflare D1/R2 用量统计和远端上限配置。",
            )
        } else {
            HighlightCard(
                title = "当前远端用量",
                body = buildString {
                    append("用于按 Cloudflare 免费额度相关指标做保护。")
                    if (adminUsage.updatedAt != null) {
                        append("\n上限更新：${formatDisplayTime(adminUsage.updatedAt)}")
                    }
                    if (adminUsage.generatedAt.isNotBlank()) {
                        append("\n统计生成：${formatDisplayTime(adminUsage.generatedAt)}")
                    }
                    append("\n窗口：R2 采用近 ${adminUsage.cloudflareReference.rollingWindowDays} 天滚动估算。")
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    UsageMetricLine(
                        label = "D1 每日读取行数",
                        detail = "${formatCount(adminUsage.d1RowsReadDaily.used)} / ${formatCount(adminUsage.d1RowsReadDaily.limit)}",
                        exceeded = adminUsage.d1RowsReadDaily.exceeded,
                    )
                    UsageMetricLine(
                        label = "D1 每日写入行数",
                        detail = "${formatCount(adminUsage.d1RowsWrittenDaily.used)} / ${formatCount(adminUsage.d1RowsWrittenDaily.limit)}",
                        exceeded = adminUsage.d1RowsWrittenDaily.exceeded,
                    )
                    UsageMetricLine(
                        label = "D1 总存储",
                        detail = "${formatGb(adminUsage.d1Storage.usedGb)} GB / ${formatGb(adminUsage.d1Storage.limitGb)} GB",
                        exceeded = adminUsage.d1Storage.exceeded,
                    )
                    UsageMetricLine(
                        label = "R2 Class A（近 30 天）",
                        detail = "${formatCount(adminUsage.r2ClassARolling30d.used)} / ${formatCount(adminUsage.r2ClassARolling30d.limit)}",
                        exceeded = adminUsage.r2ClassARolling30d.exceeded,
                    )
                    UsageMetricLine(
                        label = "R2 Class B（近 30 天）",
                        detail = "${formatCount(adminUsage.r2ClassBRolling30d.used)} / ${formatCount(adminUsage.r2ClassBRolling30d.limit)}",
                        exceeded = adminUsage.r2ClassBRolling30d.exceeded,
                    )
                    UsageMetricLine(
                        label = "R2 存储（GB-month）",
                        detail = "${formatGb(adminUsage.r2StorageRolling30d.usedGbMonth)} / ${formatGb(adminUsage.r2StorageRolling30d.limitGbMonth)} GB-month",
                        exceeded = adminUsage.r2StorageRolling30d.exceeded,
                    )
                    Text(
                        text = "当前 R2 实时占用：${formatBytes(adminUsage.r2StorageRolling30d.liveBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HighlightCard(
                title = "远端上限配置",
                body = if (usageDraft == usageSourceDraft) {
                    "当前没有未保存修改。可直接填入 Cloudflare 免费额度，也可以按更低阈值提前阻断上传和公开读取。"
                } else {
                    "这里保存的是后端侧全局保护阈值，不是本地草稿。"
                },
            ) {
                usageDraft?.let { currentDraft ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("启用远端保护")
                            Switch(
                                checked = currentDraft.enabled,
                                onCheckedChange = { usageDraft = currentDraft.copy(enabled = it) },
                            )
                        }
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.d1RowsReadDailyLimit,
                            onValueChange = { usageDraft = currentDraft.copy(d1RowsReadDailyLimit = it) },
                            label = { Text("d1_rows_read_daily_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.d1RowsWrittenDailyLimit,
                            onValueChange = { usageDraft = currentDraft.copy(d1RowsWrittenDailyLimit = it) },
                            label = { Text("d1_rows_written_daily_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.d1StorageGbLimit,
                            onValueChange = { usageDraft = currentDraft.copy(d1StorageGbLimit = it) },
                            label = { Text("d1_storage_gb_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.r2ClassARolling30dLimit,
                            onValueChange = { usageDraft = currentDraft.copy(r2ClassARolling30dLimit = it) },
                            label = { Text("r2_class_a_rolling_30d_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.r2ClassBRolling30dLimit,
                            onValueChange = { usageDraft = currentDraft.copy(r2ClassBRolling30dLimit = it) },
                            label = { Text("r2_class_b_rolling_30d_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.r2StorageGbMonthLimit,
                            onValueChange = { usageDraft = currentDraft.copy(r2StorageGbMonthLimit = it) },
                            label = { Text("r2_storage_gb_month_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { onSaveUsageLimits(currentDraft) },
                                enabled = currentDraft != usageSourceDraft,
                            ) {
                                Text("保存远端上限")
                            }
                            TextButton(
                                onClick = {
                                    usageDraft = UsageLimitsDraft.fromReference(
                                        adminUsage.cloudflareReference,
                                        enabled = currentDraft.enabled,
                                    )
                                },
                            ) {
                                Text("填入免费额度")
                            }
                        }
                        TextButton(
                            onClick = { usageDraft = usageSourceDraft },
                            enabled = currentDraft != usageSourceDraft,
                        ) {
                            Text("撤销远端修改")
                        }
                    }
                }
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
    val clipboardManager = LocalClipboardManager.current
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1.2f),
                                onClick = { clipboardManager.setText(AnnotatedString(item.shareUrl)) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = "复制共享链接",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                            Button(
                                modifier = Modifier.weight(0.8f),
                                onClick = { onTerminate(item.shareCode) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = "结束共享",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
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
private fun UsageMetricLine(
    label: String,
    detail: String,
    exceeded: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (exceeded) "$detail · 已达上限" else detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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

private fun formatCount(value: Long): String = "%,d".format(value)

private fun formatGb(value: Double): String {
    val rounded = when {
        abs(value) >= 100 -> "%.1f".format(value)
        abs(value) >= 1 -> "%.2f".format(value)
        else -> "%.4f".format(value)
    }
    return rounded.trimEnd('0').trimEnd('.')
}

private fun formatBytes(value: Long): String {
    if (value < 1_000L) return "$value B"
    if (value < 1_000_000L) return "${formatGb(value / 1_000.0)} KB"
    if (value < 1_000_000_000L) return "${formatGb(value / 1_000_000.0)} MB"
    return "${formatGb(value / 1_000_000_000.0)} GB"
}
