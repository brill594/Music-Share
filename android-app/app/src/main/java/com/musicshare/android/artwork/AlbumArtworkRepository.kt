package com.musicshare.android.artwork

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.musicshare.android.data.CurrentTrackSnapshot
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AlbumArtwork(
    val artUri: String,
    val artworkColorArgb: Long,
)

class AlbumArtworkRepository(private val context: Context) {
    suspend fun extract(track: CurrentTrackSnapshot): AlbumArtwork? = withContext(Dispatchers.IO) {
        runCatching {
            if (!track.isResolvable || track.documentUri.isBlank()) return@runCatching null
            val sourceUri = Uri.parse(track.documentUri)
            val artworkBytes = readEmbeddedArtwork(sourceUri) ?: return@runCatching null
            val seed = decodeSeed(artworkBytes)
            val file = writeCurrentArtwork(track, artworkBytes) ?: return@runCatching null
            AlbumArtwork(
                artUri = Uri.fromFile(file).toString(),
                artworkColorArgb = seed,
            )
        }.getOrNull()
    }

    suspend fun hasUsableArtwork(track: CurrentTrackSnapshot): Boolean = withContext(Dispatchers.IO) {
        track.artUri.isNotBlank() && track.artworkColorArgb != 0L && canRead(track.artUri)
    }

    private fun canRead(artUri: String): Boolean = runCatching {
        context.contentResolver.openInputStream(Uri.parse(artUri))?.use { true } == true
    }.getOrDefault(false)

    private fun readEmbeddedArtwork(sourceUri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            retriever.embeddedPicture
        } catch (_: Exception) {
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

    private fun writeCurrentArtwork(track: CurrentTrackSnapshot, bytes: ByteArray): File? {
        val directory = File(context.cacheDir, artworkCacheDir).apply { mkdirs() }
        val target = File(directory, "current-${cacheKey(track)}.img")
        directory.listFiles()?.forEach { file ->
            if (file != target) file.delete()
        }
        val staging = File(directory, "${target.name}.tmp")
        FileOutputStream(staging).use { output -> output.write(bytes) }
        if (staging.renameTo(target)) {
            return target
        }
        target.delete()
        if (staging.renameTo(target)) {
            return target
        }
        staging.delete()
        return null
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
        const val artworkCacheDir = "current-album-artwork"
        const val maxSeedBitmapEdge = 128
    }
}
