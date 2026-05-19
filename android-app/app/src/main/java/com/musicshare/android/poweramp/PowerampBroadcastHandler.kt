package com.musicshare.android.poweramp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.musicshare.android.artwork.AlbumArtworkRepository
import com.musicshare.android.data.AppStateStore
import com.musicshare.android.data.CurrentTrackSnapshot
import com.musicshare.android.util.DocumentUriResolver
import com.musicshare.android.tile.TileStateBridge
import com.musicshare.android.util.nowIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PowerampBroadcastHandler(
    private val context: Context,
    private val stateStore: AppStateStore,
    private val documentUriResolver: DocumentUriResolver,
    private val albumArtworkRepository: AlbumArtworkRepository,
    private val appScope: CoroutineScope,
) {
    fun handle(intent: Intent, onFinished: (() -> Unit)? = null) {
        appScope.launch {
            try {
                Log.d(
                    logTag,
                    "Handling action=${intent.action} keys=${intent.extras?.keySet()?.sorted()?.joinToString().orEmpty()}",
                )
                val currentState = stateStore.read()
                val parsed = parseSnapshot(
                    intent = intent,
                    existing = currentState.latestTrack,
                    treeUri = currentState.musicTreeUri,
                )
                if (parsed != null) {
                    Log.d(
                        logTag,
                        "Resolved track title=${parsed.title} path=${parsed.powerampPath} state=${parsed.playbackState} readable=${parsed.isResolvable}",
                    )
                    stateStore.update { state ->
                        state.copy(latestTrack = parsed)
                    }
                    requestGlanceRefresh()
                    applyAlbumArtwork(parsed)
                } else {
                    Log.d(logTag, "Ignored action=${intent.action} because no usable snapshot was produced")
                }
            } finally {
                onFinished?.invoke()
            }
        }
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
        val playbackState = resolvePlaybackState(intent)
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
        val sameTrack = existing != null && (
            existing.powerampPath == powerampPath ||
                trackId.isNotBlank() && existing.trackId == trackId
            )
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
        val artworkSnapshot = attachAlbumArtwork(snapshot)
        if (
            artworkSnapshot.artUri == snapshot.artUri &&
            artworkSnapshot.artworkColorArgb == snapshot.artworkColorArgb
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

    private fun sameTrack(left: CurrentTrackSnapshot, right: CurrentTrackSnapshot): Boolean {
        if (left.trackId.isNotBlank() && right.trackId.isNotBlank()) {
            return left.trackId == right.trackId
        }
        return left.powerampPath.isNotBlank() && left.powerampPath == right.powerampPath
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
