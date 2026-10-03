package com.musicshare.android.poweramp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
import com.musicshare.android.artwork.AlbumArtworkRepository
import com.musicshare.android.data.AppStateStore
import com.musicshare.android.data.CurrentTrackSnapshot
import com.musicshare.android.util.DocumentUriResolver
import com.musicshare.android.tile.TileStateBridge
import com.musicshare.android.util.nowIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PowerampBroadcastHandler(
    private val context: Context,
    private val stateStore: AppStateStore,
    private val documentUriResolver: DocumentUriResolver,
    private val albumArtworkRepository: AlbumArtworkRepository,
    private val appScope: CoroutineScope,
) {
    private val artworkMutex = Mutex()

    fun handle(intent: Intent, onFinished: (() -> Unit)? = null) {
        appScope.launch(Dispatchers.IO) {
            try {
                processIntent(intent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(logTag, "Poweramp update failed", error)
            } finally {
                onFinished?.invoke()
            }
        }
    }

    private suspend fun processIntent(intent: Intent, extractArtwork: Boolean = true) {
        var parsed: CurrentTrackSnapshot? = null
        stateStore.update { state ->
            // Parse against the state being committed, not a snapshot read before another broadcast.
            parsed = parseSnapshot(intent, state.latestTrack, state.musicTreeUri)
            parsed?.let { state.copy(latestTrack = it) } ?: state
        }
        parsed?.let {
            Log.d(logTag, "Resolved track title=${it.title} state=${it.playbackState} readable=${it.isResolvable}")
            requestGlanceRefresh()
            if (extractArtwork) applyAlbumArtwork(it)
        }
    }

    suspend fun refreshCurrentTrack() = withContext(Dispatchers.IO) {
        // Query each sticky action separately: a combined filter may return only status.
        for (action in listOf(PowerampContract.actionTrackChanged, PowerampContract.actionStatusChanged)) {
            val intent = ContextCompat.registerReceiver(
                context, null, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED,
            )
            if (intent != null) processIntent(intent, extractArtwork = false)
        }
        val expected = stateStore.read().latestTrack ?: return@withContext
        for (waitMs in listOf(0L, 500L, 1_500L)) {
            delay(waitMs)
            var current: CurrentTrackSnapshot? = null
            stateStore.update { state ->
                val latest = state.latestTrack
                if (latest == null || latest.powerampPath != expected.powerampPath) return@update state
                val uri = latest.documentUri.takeIf { it.isNotBlank() && documentUriResolver.isReadable(context, it) }
                    ?: documentUriResolver.resolve(state.musicTreeUri, latest.powerampPath)?.toString().orEmpty()
                current = latest.copy(
                    documentUri = uri,
                    isResolvable = uri.isNotBlank() && documentUriResolver.isReadable(context, uri),
                )
                state.copy(latestTrack = current)
            }
            val snapshot = current ?: return@withContext
            applyAlbumArtwork(snapshot)
            val latest = stateStore.read().latestTrack ?: return@withContext
            if (latest.powerampPath != expected.powerampPath || albumArtworkRepository.hasUsableArtwork(latest)) return@withContext
        }
        Log.d(logTag, "Foreground artwork recovery exhausted for track=${expected.trackId}")
    }

    private fun parseSnapshot(
        intent: Intent,
        existing: CurrentTrackSnapshot?,
        treeUri: String,
    ): CurrentTrackSnapshot? {
        val action = intent.action ?: return null
        if (action !in watchedActions) {
            return null
        }
        val trackBundle = intent.getBundleExtra(PowerampContract.extraTrack)
        val powerampPath = pickString(intent, trackBundle, PowerampContract.trackPath)
        val title = pickString(intent, trackBundle, PowerampContract.trackTitle)
        val artist = pickString(intent, trackBundle, PowerampContract.trackArtist)
        val album = pickString(intent, trackBundle, PowerampContract.trackAlbum)
        val durationMs = pickLong(intent, trackBundle, PowerampContract.trackDurationMs)
            ?: pickLong(intent, trackBundle, PowerampContract.trackDurationSeconds)?.times(1_000L)
            ?: existing?.durationMs
            ?: 0L
        val trackId = pickLong(intent, trackBundle, PowerampContract.trackId)
            ?.toString()
            ?: pickLong(intent, trackBundle, PowerampContract.extraId)?.toString()
            ?: existing?.trackId
            .orEmpty()
        val playbackState = resolvePlaybackState(intent).takeUnless { it == "unknown" }
            ?: existing?.playbackState.orEmpty()
        val updatedAt = pickLong(intent, trackBundle, PowerampContract.extraTimestamp)?.let {
            java.time.Instant.ofEpochMilli(it).toString()
        } ?: nowIso()

        if (powerampPath.isNullOrBlank()) {
            return existing?.copy(
                playbackState = playbackState,
                updatedAt = updatedAt,
            )
        }

        val resolvedUri = documentUriResolver.resolve(treeUri, powerampPath)?.toString().orEmpty()
        val isReadable = resolvedUri.isNotBlank() && documentUriResolver.isReadable(context, resolvedUri)
        val sameTrack = existing?.powerampPath == powerampPath
        val previousArtUri = if (sameTrack) existing?.artUri.orEmpty() else ""
        val previousArtworkColor = if (sameTrack) existing?.artworkColorArgb ?: 0L else 0L
        return CurrentTrackSnapshot(
            powerampPath = powerampPath,
            documentUri = resolvedUri,
            title = title ?: existing?.title.orEmpty(),
            artist = artist ?: existing?.artist.orEmpty(),
            album = album ?: existing?.album.orEmpty(),
            durationMs = durationMs,
            artUri = previousArtUri,
            artworkColorArgb = previousArtworkColor,
            trackId = trackId,
            playbackState = playbackState,
            updatedAt = updatedAt,
            isResolvable = isReadable,
        )
    }

    private suspend fun applyAlbumArtwork(snapshot: CurrentTrackSnapshot) {
        artworkMutex.withLock {
            // Startup recovery and duplicate broadcasts share the same artwork writer.
            val current = stateStore.read().latestTrack
            if (current == null || !sameTrack(current, snapshot)) {
                return
            }
            val artworkSnapshot = attachAlbumArtwork(current)
            if (
                artworkSnapshot.artUri == current.artUri &&
                artworkSnapshot.artworkColorArgb == current.artworkColorArgb
            ) {
                return
            }
            var applied = false
            stateStore.update { state ->
                val latest = state.latestTrack
                if (latest != null && sameTrack(latest, snapshot)) {
                    applied = true
                    state.copy(
                        latestTrack = latest.copy(
                            artUri = artworkSnapshot.artUri,
                            artworkColorArgb = artworkSnapshot.artworkColorArgb,
                        ),
                    )
                } else {
                    state
                }
            }
            if (applied) {
                requestGlanceRefresh()
            }
        }
    }

    private fun sameTrack(left: CurrentTrackSnapshot, right: CurrentTrackSnapshot): Boolean {
        return left.powerampPath.isNotBlank() && left.powerampPath == right.powerampPath &&
            left.documentUri == right.documentUri
    }

    private fun requestGlanceRefresh() {
        TileStateBridge.requestRefresh(context)
    }

    private suspend fun attachAlbumArtwork(snapshot: CurrentTrackSnapshot): CurrentTrackSnapshot {
        if (!snapshot.isResolvable) {
            return snapshot.copy(artUri = "", artworkColorArgb = 0L)
        }
        if (albumArtworkRepository.hasUsableArtwork(snapshot)) {
            return snapshot
        }
        val artwork = albumArtworkRepository.extract(snapshot)
            ?: return snapshot.copy(artUri = "", artworkColorArgb = 0L)
        return snapshot.copy(
            artUri = artwork.artUri,
            artworkColorArgb = artwork.artworkColorArgb,
        )
    }

    @Suppress("DEPRECATION")
    private fun resolvePlaybackState(intent: Intent): String {
        val state = intent.extras?.get(PowerampContract.extraState) as? Int
        val paused = intent.extras?.get(PowerampContract.extraPaused) as? Boolean
        return when {
            paused == true -> "paused"
            state == PowerampContract.statePlaying -> "playing"
            state == PowerampContract.statePaused -> "paused"
            state == PowerampContract.stateStopped -> "stopped"
            else -> "unknown"
        }
    }

    @Suppress("DEPRECATION")
    private fun pickString(intent: Intent, bundle: Bundle?, key: String): String? {
        return intent.extras?.get(key) as? String
            ?: bundle?.get(key) as? String
    }

    @Suppress("DEPRECATION")
    private fun pickLong(intent: Intent, bundle: Bundle?, key: String): Long? {
        return when (val direct = intent.extras?.get(key)) {
            is Int -> direct.toLong()
            is Long -> direct
            is String -> direct.toLongOrNull()
            else -> when (val nested = bundle?.get(key)) {
                is Int -> nested.toLong()
                is Long -> nested
                is String -> nested.toLongOrNull()
                else -> null
            }
        }
    }

    private companion object {
        const val logTag = "MusicSharePoweramp"

        val watchedActions = setOf(
            PowerampContract.actionTrackChanged,
            PowerampContract.actionTrackChangedExplicit,
            PowerampContract.actionStatusChanged,
            PowerampContract.actionStatusChangedExplicit,
        )
    }
}
