package com.musicshare.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.musicshare.android.data.AppStateStore
import com.musicshare.android.network.MusicShareBackendRepository
import com.musicshare.android.poweramp.PowerampBroadcastHandler
import com.musicshare.android.poweramp.PowerampContract
import com.musicshare.android.service.ShareCoordinator
import com.musicshare.android.util.ConfigTransferManager
import com.musicshare.android.util.DocumentUriResolver
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MusicShareApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.bootstrap()
    }
}

class AppContainer(private val application: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val stateStore = AppStateStore(application)
    val documentUriResolver = DocumentUriResolver()
    val backendRepository = MusicShareBackendRepository(application, stateStore)
    val configTransferManager = ConfigTransferManager(application, stateStore)
    val shareCoordinator = ShareCoordinator(
        context = application,
        stateStore = stateStore,
        backendRepository = backendRepository,
        documentUriResolver = documentUriResolver,
    )
    val powerampBroadcastHandler = PowerampBroadcastHandler(
        context = application,
        stateStore = stateStore,
        documentUriResolver = documentUriResolver,
        appScope = appScope,
    )
    private val runtimePowerampReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(logTag, "Runtime receiver got action=${intent.action}")
                powerampBroadcastHandler.handle(intent)
            }
        }

    fun bootstrap() {
        registerPowerampReceiver()
        appScope.launch {
            stateStore.ensureClientInstallId()
        }
    }

    private fun registerPowerampReceiver() {
        val stickyIntent = ContextCompat.registerReceiver(
            application,
            runtimePowerampReceiver,
            PowerampContract.intentFilter(),
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.d(logTag, "Registered runtime Poweramp broadcast receiver")
        if (stickyIntent != null) {
            Log.d(logTag, "Consumed sticky Poweramp action=${stickyIntent.action}")
            powerampBroadcastHandler.handle(stickyIntent)
        }
    }

    private companion object {
        const val logTag = "MusicSharePoweramp"
    }
}
