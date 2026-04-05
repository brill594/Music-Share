package com.musicshare.android

import android.app.Application
import com.musicshare.android.data.AppStateStore
import com.musicshare.android.network.MusicShareBackendRepository
import com.musicshare.android.poweramp.PowerampBroadcastHandler
import com.musicshare.android.service.ShareCoordinator
import com.musicshare.android.util.ConfigTransferManager
import com.musicshare.android.util.DocumentUriResolver
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

    fun bootstrap() {
        appScope.launch {
            stateStore.ensureClientInstallId()
        }
    }
}
