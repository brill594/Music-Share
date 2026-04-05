package com.musicshare.android.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object TileStateBridge {
    fun requestRefresh(context: Context) {
        TileService.requestListeningState(
            context,
            ComponentName(context, ShareTileService::class.java),
        )
    }
}
