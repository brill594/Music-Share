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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musicshare.android.R
import com.musicshare.android.data.CurrentTrackSnapshot
import com.musicshare.android.data.PersistedAppState
import com.musicshare.android.data.RuntimeStatus
import com.musicshare.android.network.AdminBackgroundDto
import com.musicshare.android.network.AdminUsageDto
import com.musicshare.android.network.ShareItemDto
import com.musicshare.android.util.formatDisplayTime
import com.musicshare.android.util.formatDurationLabel
import com.musicshare.android.util.formatShareExpiryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val shareManagementTabIndex = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicShareScreen(
    uiState: DashboardUiState,
    snackbarHostState: SnackbarHostState,
    onPickMusicTree: () -> Unit,
    onShareNow: () -> Unit,
    onEnterShareManagement: () -> Unit,
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
    var shareManagementAutoLoaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selectedTab) {
        if (selectedTab == shareManagementTabIndex && !shareManagementAutoLoaded) {
            shareManagementAutoLoaded = true
            onEnterShareManagement()
        }
    }

    AlbumArtworkBackground(artUri = uiState.appState.latestTrack?.artUri.orEmpty()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Music Share") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = musicShareTextColor,
                        navigationIconContentColor = musicShareTextColor,
                        actionIconContentColor = musicShareTextColor,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            CompositionLocalProvider(LocalContentColor provides musicShareTextColor) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = musicShareTextColor,
                    ) {
                        listOf("当前歌曲", "分享管理", "设置").forEachIndexed { index, label ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                selectedContentColor = musicShareTextColor,
                                unselectedContentColor = musicShareTextColor,
                                text = { Text(label) },
                            )
                        }
                    }
                    when (selectedTab) {
                        0 -> CurrentTrackTab(
                            appState = uiState.appState,
                            onShareNow = onShareNow,
                        )
                        shareManagementTabIndex -> ShareManagementTab(
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
                            onPickMusicTree = onPickMusicTree,
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
}

@Composable
private fun AlbumArtworkBackground(
    artUri: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val artworkBitmap by produceState<ImageBitmap?>(initialValue = null, artUri, context) {
        value = withContext(Dispatchers.IO) {
            decodeBackdropBitmap(context, artUri)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = appBackgroundAlpha)),
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
                    .background(MaterialTheme.colorScheme.background.copy(alpha = artworkBackgroundOverlayAlpha)),
            )
        }
        content()
    }
}

private fun decodeBackdropBitmap(context: Context, artUri: String): ImageBitmap? {
    val blurredByModifier = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val maxEdge = if (blurredByModifier) maxBackgroundBitmapEdge else maxFallbackBitmapEdge
    val decoded = decodeArtworkBitmap(context, artUri, maxEdge) ?: return null
    val displayBitmap = if (blurredByModifier) {
        decoded
    } else {
        boxBlur(decoded, fallbackBlurRadius).also { blurred ->
            if (blurred !== decoded) decoded.recycle()
        }
    }
    return displayBitmap.asImageBitmap()
}

private fun decodeArtworkBitmap(context: Context, artUri: String, maxEdge: Int): Bitmap? = runCatching {
    if (artUri.isBlank()) return@runCatching null
    val uri = Uri.parse(artUri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    val sampleSize = calculateArtworkSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(
            stream,
            null,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
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
private const val maxCoverBitmapEdge = 1080
private const val fallbackBlurRadius = 12

@Composable
private fun CurrentTrackTab(
    appState: PersistedAppState,
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
        val track = appState.latestTrack
        if (track == null) {
            HighlightCard(
                title = "等待 Poweramp 广播",
                body = "打开 Poweramp 并切换一次曲目后，App 会缓存当前可分享曲目。",
            )
        } else {
            NowPlayingSection(
                track = track,
                runtime = appState.runtime,
                onShareNow = onShareNow,
            )
        }
    }
}

@Composable
private fun NowPlayingSection(
    track: CurrentTrackSnapshot,
    runtime: RuntimeStatus,
    onShareNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MsTokens.RowSpacing)) {
        AlbumCoverImage(
            artUri = track.artUri,
            modifier = Modifier.fillMaxWidth(),
        )
        Plate {
            Text(
                track.displayTitle(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Plate {
            Text(
                "${track.artist.ifBlank { "未知艺术家" }} · ${track.album.ifBlank { "未知专辑" }}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Plate(verticalPadding = 6.dp) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "时长 ${formatDurationLabel(track.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                StatusChip(
                    label = if (track.isResolvable) "可访问" else "待重新解析",
                    good = track.isResolvable,
                    compact = true,
                )
            }
        }
        PrimaryButton(
            text = if (runtime.isProcessing) shareProgressLabel(runtime) else "立即分享",
            onClick = onShareNow,
            modifier = Modifier.fillMaxWidth(),
            enabled = track.isResolvable,
            loading = runtime.isProcessing,
        )
        if (runtime.lastShareUrl.isNotBlank()) {
            ShareLinkBox(shareUrl = runtime.lastShareUrl)
        }
        if (!runtime.isProcessing && runtime.lastError.isNotBlank()) {
            Plate(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "错误：${runtime.lastError}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MsTokens.DestructiveLabel,
                )
            }
        }
    }
}

private fun shareProgressLabel(runtime: RuntimeStatus): String = buildString {
    append(runtime.currentStage.ifBlank { "正在处理" })
    if (runtime.progressPercent >= 0) {
        append(" ${runtime.progressPercent}%")
    }
}

@Composable
private fun ShareLinkBox(shareUrl: String) {
    val clipboardManager = LocalClipboardManager.current
    Plate(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MsTokens.RowSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shareUrl,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "复制",
                onClick = { clipboardManager.setText(AnnotatedString(shareUrl)) },
                compact = true,
            )
        }
    }
}

@Composable
private fun Plate(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 10.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = highlightCardContainerAlpha),
                shape = CircleShape,
            )
            .padding(horizontal = 18.dp, vertical = verticalPadding),
    ) {
        content()
    }
}

@Composable
private fun AlbumCoverImage(
    artUri: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coverBitmap by produceState<ImageBitmap?>(initialValue = null, artUri, context) {
        value = withContext(Dispatchers.IO) {
            decodeArtworkBitmap(context, artUri, maxCoverBitmapEdge)?.asImageBitmap()
        }
    }
    val cover = coverBitmap
    if (cover != null) {
        Image(
            bitmap = cover,
            contentDescription = "歌曲封面",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(MsTokens.RadiusCard)),
        )
    } else {
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .background(
                    color = musicShareTextColor.copy(alpha = MsTokens.SecondaryContainerAlpha),
                    shape = RoundedCornerShape(MsTokens.RadiusCard),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_music_tile),
                contentDescription = null,
                tint = musicShareTextColor.copy(alpha = MsTokens.StatusDotPendingAlpha),
                modifier = Modifier.size(96.dp),
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
            PrimaryButton(
                text = if (isRefreshing) "正在从后端拉取..." else "从后端拉取已有音乐信息",
                onClick = onRefreshShares,
                modifier = Modifier.fillMaxWidth(),
                loading = isRefreshing,
            )
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
            ActionRow {
                PrimaryButton(
                    text = "获取用户会话",
                    onClick = onAuthenticateUser,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "获取管理员会话",
                    onClick = onAuthenticateAdmin,
                    modifier = Modifier.weight(1f),
                )
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
                PrimaryButton(
                    text = "设置背景图",
                    onClick = onUploadAdminBackground,
                    modifier = Modifier.fillMaxWidth(),
                )
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
    onPickMusicTree: () -> Unit,
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
            title = if (appState.hasMusicTreePermission()) "音乐目录授权已保存" else "授权音乐目录",
            body = if (appState.hasMusicTreePermission()) {
                "当前已保存 Poweramp 音乐目录授权。如遇到无法读取当前歌曲，可重新授权。"
            } else {
                "首次使用前，请通过系统文档选择器授权 Poweramp 所在音乐目录。"
            },
        ) {
            PrimaryButton(
                text = if (appState.hasMusicTreePermission()) "重新授权目录" else "授权音乐目录",
                onClick = onPickMusicTree,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HighlightCard(
            title = "设置草稿",
            body = if (draft == sourceDraft) "当前没有未保存修改。" else "你有未保存修改，点击保存后才会生效。",
        ) {
            ActionRow {
                PrimaryButton(
                    text = "保存设置",
                    onClick = { onSaveSettings(draft) },
                    modifier = Modifier.weight(1f),
                    enabled = draft != sourceDraft,
                )
                TextActionButton(
                    text = "撤销修改",
                    onClick = { draft = sourceDraft },
                    enabled = draft != sourceDraft,
                )
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
                    colors = whiteOutlinedTextFieldColors(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MsTokens.ChipSpacing)) {
                    listOf("none" to "无需认证", "basic" to "密码认证").forEach { (value, label) ->
                        MsFilterChip(
                            label = label,
                            selected = draft.authMode == value,
                            onClick = { draft = draft.copy(authMode = value) },
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
                    MsSwitch(
                        checked = draft.adminEnabled,
                        onCheckedChange = { draft = draft.copy(adminEnabled = it) },
                    )
                }
                PasswordField(
                    label = "admin_password",
                    value = draft.adminPassword,
                    onValueChange = { draft = draft.copy(adminPassword = it) },
                )
                DestructiveButton(
                    text = "清除本地短期凭证",
                    onClick = onClearSession,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        HighlightCard(
            title = "分享与转码",
            body = "开启原格式优先时，后端支持的音频会直接上传原文件，无需转码；其余情况按下方参数转码。",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("原格式优先")
                    MsSwitch(
                        checked = draft.passthroughPreferred,
                        onCheckedChange = { draft = draft.copy(passthroughPreferred = it) },
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.expireAfterSeconds,
                    onValueChange = { draft = draft.copy(expireAfterSeconds = it) },
                    label = { Text("expire_after_seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = whiteOutlinedTextFieldColors(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MsTokens.ChipSpacing)) {
                    listOf(
                        "fast" to "Fast Share",
                        "balanced" to "Balanced",
                        "better" to "Better Quality",
                    ).forEach { (presetKey, presetLabel) ->
                        MsFilterChip(
                            label = presetLabel,
                            selected = false,
                            onClick = {
                                val preset = SettingsDraft.preset(presetKey)
                                draft = draft.copy(
                                    outputFormat = preset.outputFormat,
                                    audioCodec = preset.audioCodec,
                                    bitrateKbps = preset.bitrateKbps.toString(),
                                    sampleRateHz = preset.sampleRateHz.toString(),
                                    channels = preset.channels,
                                )
                            },
                        )
                    }
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.bitrateKbps,
                    onValueChange = { draft = draft.copy(bitrateKbps = it) },
                    label = { Text("bitrate_kbps") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = whiteOutlinedTextFieldColors(),
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.sampleRateHz,
                    onValueChange = { draft = draft.copy(sampleRateHz = it) },
                    label = { Text("sample_rate_hz") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = whiteOutlinedTextFieldColors(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MsTokens.ChipSpacing)) {
                    MsFilterChip(
                        label = "单声道",
                        selected = draft.channels == 1,
                        onClick = { draft = draft.copy(channels = 1) },
                    )
                    MsFilterChip(
                        label = "双声道",
                        selected = draft.channels == 2,
                        onClick = { draft = draft.copy(channels = 2) },
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.maxDurationSeconds,
                    onValueChange = { draft = draft.copy(maxDurationSeconds = it) },
                    label = { Text("max_duration_seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = whiteOutlinedTextFieldColors(),
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
                            MsSwitch(
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
                            colors = whiteOutlinedTextFieldColors(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.d1RowsWrittenDailyLimit,
                            onValueChange = { usageDraft = currentDraft.copy(d1RowsWrittenDailyLimit = it) },
                            label = { Text("d1_rows_written_daily_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = whiteOutlinedTextFieldColors(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.d1StorageGbLimit,
                            onValueChange = { usageDraft = currentDraft.copy(d1StorageGbLimit = it) },
                            label = { Text("d1_storage_gb_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = whiteOutlinedTextFieldColors(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.r2ClassARolling30dLimit,
                            onValueChange = { usageDraft = currentDraft.copy(r2ClassARolling30dLimit = it) },
                            label = { Text("r2_class_a_rolling_30d_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = whiteOutlinedTextFieldColors(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.r2ClassBRolling30dLimit,
                            onValueChange = { usageDraft = currentDraft.copy(r2ClassBRolling30dLimit = it) },
                            label = { Text("r2_class_b_rolling_30d_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = whiteOutlinedTextFieldColors(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentDraft.r2StorageGbMonthLimit,
                            onValueChange = { usageDraft = currentDraft.copy(r2StorageGbMonthLimit = it) },
                            label = { Text("r2_storage_gb_month_limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = whiteOutlinedTextFieldColors(),
                            singleLine = true,
                        )
                        ActionRow {
                            PrimaryButton(
                                text = "保存远端上限",
                                onClick = { onSaveUsageLimits(currentDraft) },
                                modifier = Modifier.weight(1f),
                                enabled = currentDraft != usageSourceDraft,
                            )
                            SecondaryButton(
                                text = "填入免费额度",
                                onClick = {
                                    usageDraft = UsageLimitsDraft.fromReference(
                                        adminUsage.cloudflareReference,
                                        enabled = currentDraft.enabled,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        TextActionButton(
                            text = "撤销远端修改",
                            onClick = { usageDraft = usageSourceDraft },
                            enabled = currentDraft != usageSourceDraft,
                        )
                    }
                }
            }
        }

        HighlightCard(
            title = "导出与导入",
            body = "导出的 JSON 默认包含密码、短期凭证和认证日志。保存前请确认文件位置可信。",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionRow {
                    SecondaryButton(
                        text = "导出配置",
                        onClick = onExportConfig,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        text = "导入并保留安装 ID",
                        onClick = onImportConfigPreserveId,
                        modifier = Modifier.weight(1f),
                    )
                }
                DestructiveButton(
                    text = "导入并覆盖安装 ID",
                    onClick = onImportConfigReplaceId,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                    shape = RoundedCornerShape(MsTokens.RadiusCardInner),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = shareItemContainerAlpha),
                        contentColor = musicShareTextColor,
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
                        HorizontalDivider(color = musicShareTextColor.copy(alpha = controlContainerAlpha))
                        ActionRow {
                            SecondaryButton(
                                text = "复制共享链接",
                                onClick = { clipboardManager.setText(AnnotatedString(item.shareUrl)) },
                                modifier = Modifier.weight(1f),
                                compact = true,
                                maxLines = 2,
                            )
                            DestructiveButton(
                                text = "结束共享",
                                onClick = { onTerminate(item.shareCode) },
                                modifier = Modifier.weight(1f),
                                compact = true,
                                maxLines = 2,
                            )
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
            TextActionButton(
                text = if (visible) "隐藏" else "显示",
                onClick = { visible = !visible },
                compact = true,
            )
        },
        colors = whiteOutlinedTextFieldColors(),
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
            color = musicShareTextColor,
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
        shape = RoundedCornerShape(MsTokens.RadiusCard),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = highlightCardContainerAlpha),
            contentColor = musicShareTextColor,
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

@Composable
private fun whiteOutlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = musicShareTextColor,
    unfocusedTextColor = musicShareTextColor,
    disabledTextColor = musicShareTextColor.copy(alpha = disabledTextAlpha),
    errorTextColor = musicShareTextColor,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
    cursorColor = musicShareTextColor,
    errorCursorColor = musicShareTextColor,
    focusedBorderColor = musicShareTextColor.copy(alpha = focusedControlAlpha),
    unfocusedBorderColor = musicShareTextColor.copy(alpha = unfocusedControlAlpha),
    disabledBorderColor = musicShareTextColor.copy(alpha = disabledTextAlpha),
    errorBorderColor = musicShareTextColor.copy(alpha = focusedControlAlpha),
    focusedLabelColor = musicShareTextColor,
    unfocusedLabelColor = musicShareTextColor,
    disabledLabelColor = musicShareTextColor.copy(alpha = disabledTextAlpha),
    errorLabelColor = musicShareTextColor,
    focusedTrailingIconColor = musicShareTextColor,
    unfocusedTrailingIconColor = musicShareTextColor,
    disabledTrailingIconColor = musicShareTextColor.copy(alpha = disabledTextAlpha),
    errorTrailingIconColor = musicShareTextColor,
)

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
