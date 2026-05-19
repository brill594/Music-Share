package com.musicshare.android
import android.Manifest

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicshare.android.service.ShareForegroundService
import com.musicshare.android.ui.MainViewModel
import com.musicshare.android.ui.MusicShareScreen
import com.musicshare.android.ui.MusicShareTheme
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel> { MainViewModel.factory(application) }
    private var preserveInstallIdOnImport: Boolean = true

    private val openTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            viewModel.persistMusicTree(uri)
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        viewModel.exportConfig(uri)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importConfig(uri, preserveInstallIdOnImport)
    }

    private val backgroundImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.uploadAdminBackground(uri)
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            ShareForegroundService.showShortcutNotification(this)
        }
        ShareForegroundService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModel.factory(application))
            val uiState = vm.uiState.collectAsStateWithLifecycle().value
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(vm) {
                vm.messages.collect { message ->
                    snackbarHostState.showSnackbar(message)
                }
            }

            MusicShareTheme(
                albumArtSeedArgb = uiState.appState.latestTrack?.artworkColorArgb?.takeIf { it != 0L },
            ) {
                MusicShareScreen(
                    uiState = uiState,
                    snackbarHostState = snackbarHostState,
                    onPickMusicTree = { openTreeLauncher.launch(null) },
                    onShareNow = { startShareWithNotificationPrompt() },
                    onAuthenticateUser = { vm.authenticate(preferAdmin = false) },
                    onAuthenticateAdmin = { vm.authenticate(preferAdmin = true) },
                    onRefreshShares = { vm.refreshShares() },
                    onTerminateClientShare = vm::terminateClientShare,
                    onTerminateAdminShare = vm::terminateAdminShare,
                    onUploadAdminBackground = {
                        backgroundImageLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                    },
                    onExportConfig = { exportLauncher.launch("music-share-config.json") },
                    onImportConfigPreserveId = {
                        preserveInstallIdOnImport = true
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                    onImportConfigReplaceId = {
                        preserveInstallIdOnImport = false
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                    onSaveSettings = vm::saveSettings,
                    onSaveUsageLimits = vm::saveAdminUsageLimits,
                    onClearSession = vm::clearSession,
                )
            }
        }
        if (canPostNotifications()) {
            ShareForegroundService.showShortcutNotification(this)
        }
    }

    private fun startShareWithNotificationPrompt() {
        if (!canPostNotifications()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ShareForegroundService.showShortcutNotification(this)
            ShareForegroundService.start(this)
        }
    }
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
