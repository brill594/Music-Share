package com.musicshare.android.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.musicshare.android.MusicShareApplication
import com.musicshare.android.service.ShareForegroundService
import kotlinx.coroutines.runBlocking

class ShareTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        ShareForegroundService.start(this)
        refreshTile()
    }


    private fun refreshTile() {
        val tile = qsTile ?: return
        val stateStore = (application as MusicShareApplication).container.stateStore
        val appState = runBlocking { stateStore.read() }
        val processing = appState.runtime.isProcessing
        when {
            processing -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "处理中"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = appState.runtime.currentStage.ifBlank { "正在准备分享" }
                }
            }
            appState.latestTrack?.isResolvable == true -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "分享音乐"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = appState.latestTrack.displayTitle()
                }
            }
            appState.hasMusicTreePermission() -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "等待曲目"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Poweramp 当前曲目不可用"
                }
            }
            else -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "需要授权"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "先在 App 里授权音乐目录"
                }
            }
        }
        tile.updateTile()
    }
}
