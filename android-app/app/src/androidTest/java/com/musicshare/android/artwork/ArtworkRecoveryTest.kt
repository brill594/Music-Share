package com.musicshare.android.artwork

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import androidx.test.platform.app.InstrumentationRegistry
import com.musicshare.android.data.AppStateStore
import com.musicshare.android.data.CurrentTrackSnapshot
import com.musicshare.android.data.PersistedAppState
import com.musicshare.android.poweramp.PowerampBroadcastHandler
import com.musicshare.android.service.FfmpegAudioTranscoder
import com.musicshare.android.util.DocumentUriResolver
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArtworkRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun source(): File = File.createTempFile("artwork-fixture-", ".wav", context.cacheDir).also { file ->
        instrumentation.context.assets.open("embedded-cover.wav").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    @Test
    fun audioOnlyFfmpegCopiesEmbeddedJpegWithoutPngEncoder() = runBlocking<Unit> {
        val source = source()
        try {
            val art = FfmpegAudioTranscoder(context).extractEmbeddedArtwork(Uri.fromFile(source), source)
            assertNotNull("Audio-only FFmpeg must support the WAV fallback without PNG encoding", art)
            try {
                assertEquals("image/jpeg", art!!.mimeType)
                assertTrue(art.file.length() > 0)
            } finally { art?.file?.delete() }
        } finally { source.delete() }
    }

    @Test
    fun concurrentExtractionsDoNotDeleteEachOthersPublishedArtwork() = runBlocking<Unit> {
        val source = source()
        val repository = AlbumArtworkRepository(context)
        val track = CurrentTrackSnapshot(documentUri = Uri.fromFile(source).toString(), isResolvable = true)
        try {
            val results = (1..3).map { async { repository.extract(track) } }.awaitAll()
            assertTrue(results.all { it != null })
            assertEquals(3, results.map { it!!.artUri }.distinct().size)
            results.forEach { artwork ->
                assertTrue(repository.hasUsableArtwork(track.copy(
                    artUri = artwork!!.artUri, artworkColorArgb = artwork.artworkColorArgb,
                )))
                File(Uri.parse(artwork.artUri).path!!).delete()
            }
        } finally { source.delete() }
    }

    @Test
    fun foregroundRecoveryRepairsMissingCacheWithoutAnotherTrackBroadcast() = runBlocking<Unit> {
        val source = source()
        val stateStore = AppStateStore(context)
        val previous = stateStore.read()
        val repository = AlbumArtworkRepository(context)
        // No sticky broadcasts: recovery must work from persisted track state alone.
        val quietContext = object : ContextWrapper(context) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, permission: String?, scheduler: Handler?, flags: Int): Intent? = null
        }
        val handler = PowerampBroadcastHandler(quietContext, stateStore, DocumentUriResolver(), repository, this)
        try {
            stateStore.overwrite(PersistedAppState(latestTrack = CurrentTrackSnapshot(
                powerampPath = source.absolutePath,
                documentUri = Uri.fromFile(source).toString(),
                artUri = Uri.fromFile(File(context.cacheDir, "missing-cover.img")).toString(),
                artworkColorArgb = 0xff123456L,
                isResolvable = true,
            )))
            handler.refreshCurrentTrack()
            val recovered = stateStore.read().latestTrack!!
            assertTrue("Opening the app must repair a missing cache without switching songs", repository.hasUsableArtwork(recovered))
            assertFalse(recovered.artUri.endsWith("missing-cover.img"))
            File(Uri.parse(recovered.artUri).path!!).delete()
        } finally {
            stateStore.overwrite(previous)
            source.delete()
        }
    }
}
