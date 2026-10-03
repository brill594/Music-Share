package com.musicshare.android.artwork

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.musicshare.android.service.FfmpegAudioTranscoder
import kotlinx.coroutines.CancellationException
import com.musicshare.android.data.CurrentTrackSnapshot
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AlbumArtwork(
    val artUri: String,
    val artworkColorArgb: Long,
)

class AlbumArtworkRepository(private val context: Context) {
    private val writeMutex = Mutex()

    suspend fun extract(track: CurrentTrackSnapshot): AlbumArtwork? = withContext(Dispatchers.IO) {
        runCatching {
            if (!track.isResolvable || track.documentUri.isBlank()) return@runCatching null
            val sourceUri = Uri.parse(track.documentUri)
            val artworkBytes = readEmbeddedArtwork(sourceUri) ?: run {
                Log.d(logTag, "Trying FFmpeg artwork fallback")
                val extracted = FfmpegAudioTranscoder(context).extractEmbeddedArtwork(sourceUri)
                    ?: return@runCatching null
                try { extracted.file.readBytes() } finally { extracted.file.delete() }
            }
            val seed = decodeSeed(artworkBytes)
            if (seed == 0L) return@runCatching null
            val file = writeMutex.withLock { writeCurrentArtwork(track, artworkBytes) }
            AlbumArtwork(
                artUri = Uri.fromFile(file).toString(),
                artworkColorArgb = seed,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(logTag, "Artwork extraction failed", error)
        }.getOrNull()
    }

    suspend fun hasUsableArtwork(track: CurrentTrackSnapshot): Boolean = withContext(Dispatchers.IO) {
        track.artUri.isNotBlank() && track.artworkColorArgb != 0L && canRead(track.artUri)
    }

    private fun canRead(artUri: String): Boolean = runCatching {
        context.contentResolver.openInputStream(Uri.parse(artUri))?.use { input ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        } == true
    }.getOrDefault(false)

    private fun readEmbeddedArtwork(sourceUri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            retriever.embeddedPicture
        } catch (error: Exception) {
            Log.w(logTag, "System artwork extraction failed", error)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeSeed(bytes: ByteArray): Long {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, maxSeedBitmapEdge)
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return 0L
        return try {
            seedArgbFromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeCurrentArtwork(track: CurrentTrackSnapshot, bytes: ByteArray): File {
        val directory = File(context.cacheDir, artworkCacheDir).apply { mkdirs() }
        // A new URI also makes Compose retry after a missing/corrupt cache entry.
        val target = File.createTempFile("current-${cacheKey(track)}-", ".img", directory)
        try {
            FileOutputStream(target).use { output -> output.write(bytes) }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        // Never remove another in-flight track's freshly published artwork.
        val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1_000L
        directory.listFiles()?.filter { it != target && it.lastModified() < expiry }
            ?.forEach { it.delete() }
        return target
    }

    private fun calculateSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth > maxEdge || sampledHeight > maxEdge) {
            sampleSize *= 2
            sampledWidth = width / sampleSize
            sampledHeight = height / sampleSize
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun cacheKey(track: CurrentTrackSnapshot): String {
        val stableKey = track.trackId.ifBlank { track.powerampPath.ifBlank { track.documentUri } }
        return Integer.toUnsignedString(stableKey.hashCode(), 16)
    }

    private companion object {
        const val logTag = "MusicShareArtwork"
        const val artworkCacheDir = "current-album-artwork"
        const val maxSeedBitmapEdge = 128
    }
}
