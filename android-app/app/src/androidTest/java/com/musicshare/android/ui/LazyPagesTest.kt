package com.musicshare.android.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.musicshare.android.network.ShareItemDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LazyPagesTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showScreen(shares: List<ShareItemDto> = emptyList(), onTerminate: (String) -> Unit = {}) {
        compose.setContent {
            MusicShareTheme {
                MusicShareScreen(
                    uiState = DashboardUiState(clientShares = shares),
                    snackbarHostState = SnackbarHostState(),
                    onPickMusicTree = {}, onShareNow = {}, onEnterShareManagement = {},
                    onAuthenticateUser = {}, onAuthenticateAdmin = {}, onRefreshShares = {},
                    onTerminateClientShare = onTerminate, onTerminateAdminShare = {},
                    onUploadAdminBackground = {}, onExportConfig = {},
                    onImportConfigPreserveId = {}, onImportConfigReplaceId = {},
                    onSaveSettings = {}, onSaveUsageLimits = {}, onClearSession = {},
                )
            }
        }
    }

    @Test
    fun editingSurvivesSettingsGroupLeavingViewport() {
        showScreen()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("导出配置").assertDoesNotExist()
        compose.onNodeWithText("base_url").performTextReplacement("https://example.test")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("导出配置"))
        compose.onNodeWithText("导出配置").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("base_url"))
        compose.onNodeWithText("base_url").assertTextContains("https://example.test")
        compose.onNodeWithText("当前歌曲").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("base_url").assertTextContains("https://example.test")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("保存设置"))
        compose.onNodeWithText("保存设置").assertIsEnabled()
    }

    @Test
    fun largeShareListLoadsOnDemandAndKeepsActionsBoundToCorrectShare() {
        var terminated: String? = null
        val shares = (0..99).map { index ->
            ShareItemDto(
                shareCode = "share-$index", title = "曲目$index", artist = "Artist", album = "Album",
                durationMs = 1000, audioMime = "audio/mpeg", shareUrl = "https://example.test/$index",
                trackUrl = "", streamUrl = "", createdAt = "2026-10-04T00:00:00Z",
                expiresAt = "2026-10-05T00:00:00Z", status = "active",
            )
        }
        showScreen(shares) { terminated = it }
        compose.onNodeWithText("分享管理").performClick()
        compose.onNodeWithText("曲目99").assertDoesNotExist()
        compose.onNodeWithTag("share-management-list").performScrollToNode(hasText("曲目99"))
        compose.onNodeWithText("曲目99").assertIsDisplayed()
        compose.onAllNodesWithText("结束共享").onLast().performScrollTo().performClick()
        compose.runOnIdle { assertEquals("share-99", terminated) }
    }
}
