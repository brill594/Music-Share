package com.musicshare.android.poweramp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.musicshare.android.MusicShareApplication

class PowerampBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? MusicShareApplication ?: return
        Log.d(logTag, "Manifest receiver got action=${intent.action}")
        val pendingResult = goAsync()
        application.container.powerampBroadcastHandler.handle(intent) {
            pendingResult.finish()
        }
    }

    private companion object {
        const val logTag = "MusicSharePoweramp"
    }
}
