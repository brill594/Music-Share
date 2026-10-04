package com.musicshare.android.service

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.musicshare.android.data.AppStateStore
import com.musicshare.android.data.CurrentTrackSnapshot
import com.musicshare.android.data.TranscodeConfig
import com.musicshare.android.network.MusicShareBackendRepository
import com.musicshare.android.network.PreparedUpload
import com.musicshare.android.util.DocumentUriResolver
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TrackMetadataTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val labels = CurrentTrackSnapshot(
        title = "beautiful sky",
        artist = "石川由依 (ヴァイオレット・エヴァーガーデン)",
        album = "Letters and Doll ～Looking back on the memories of Violet Evergarden～",
    )

    @Test
    fun freshUploadUsesPowerampLabelsInsteadOfSystemTags() {
        readMetadata(labels) { metadata ->
            assertEquals(labels.title, metadata.title)
            assertEquals(labels.artist, metadata.artist)
            assertEquals(labels.album, metadata.album)
        }
    }

    @Test
    fun onlyMissingPowerampFieldsFallBackToEmbeddedTags() {
        readMetadata(labels.copy(artist = " ", album = "")) { metadata ->
            assertEquals(labels.title, metadata.title)
            assertEquals("Embedded artist", metadata.artist)
            assertEquals("Embedded album", metadata.album)
        }
    }

    private fun readMetadata(track: CurrentTrackSnapshot, check: (ShareCoordinator.ExtractedMetadata) -> Unit) {
        val source = File.createTempFile("metadata-priority-", ".flac", context.cacheDir)
        try {
            instrumentation.context.assets.open("metadata-priority.flac").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val store = AppStateStore(context)
            val coordinator = ShareCoordinator(context, store, MusicShareBackendRepository(context, store), DocumentUriResolver())
            check(coordinator.readMetadata(Uri.fromFile(source), track.copy(powerampPath = source.absolutePath), 600_000))
        } finally { source.delete() }
    }

    @Test
    fun retryReusesAudioButReplacesPreviouslyCachedMojibake() {
        val cache = PreparedUploadCache(context)
        val audio = File.createTempFile("metadata-retry-", ".ogg", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val track = labels.copy(powerampPath = "/music/beautiful sky.flac", trackId = "metadata-test")
        val config = TranscodeConfig()
        try {
            val cached = cache.store(track, config, PreparedUpload(
                audioFile = audio, audioFileName = audio.name, audioMimeType = "audio/ogg",
                coverFile = null, coverMimeType = null, title = "Old title",
                artist = "ç\u009f³å·\u009dç\u0094±ä¾\u009d", album = "Letters and Doll ï½\u009e",
                durationMs = 203_933, clientCreatedAt = "2026-10-04T00:00:00Z", expireAfterSeconds = 3600,
            ))
            val restored = requireNotNull(cache.load(track, config, 3600))
            assertEquals(labels.title, restored.title)
            assertEquals(labels.artist, restored.artist)
            assertEquals(labels.album, restored.album)
            assertEquals(cached.audioFile, restored.audioFile)
            assertTrue(restored.isRetryCacheReady)
            val fallback = requireNotNull(cache.load(track.copy(title = "", artist = "", album = ""), config, 3600))
            assertEquals("Old title", fallback.title)
        } finally { cache.clear(); audio.delete() }
    }
}
