package com.musicshare.android.service

import android.content.Context
import com.musicshare.android.data.CurrentTrackSnapshot
import com.musicshare.android.data.TranscodeConfig
import com.musicshare.android.network.PreparedUpload
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PreparedUploadCache(
    private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }
    private val cacheRoot = File(context.cacheDir, cacheDirectoryName)
    private val metadataFile = File(cacheRoot, metadataFileName)
    private val stagingRoot = File(context.cacheDir, stagingDirectoryName)
    private val backupRoot = File(context.cacheDir, backupDirectoryName)

    fun load(
        track: CurrentTrackSnapshot,
        transcodeConfig: TranscodeConfig,
        expireAfterSeconds: Long,
    ): PreparedUpload? {
        recoverBackupIfNeeded()
        val metadata = readMetadata() ?: return null
        if (metadata.cacheKey != buildCacheKey(track, transcodeConfig)) {
            return null
        }
        val audioFile = File(cacheRoot, metadata.audioFileName)
        if (!audioFile.exists() || audioFile.length() <= 0L) {
            clear()
            return null
        }
        val coverFile = metadata.coverFileName
            ?.let { File(cacheRoot, it) }
            ?.takeIf { it.exists() && it.length() > 0L }
        return PreparedUpload(
            audioFile = audioFile,
            audioMimeType = metadata.audioMimeType,
            coverFile = coverFile,
            coverMimeType = metadata.coverMimeType.takeIf { coverFile != null },
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            durationMs = metadata.durationMs,
            clientCreatedAt = metadata.clientCreatedAt,
            expireAfterSeconds = expireAfterSeconds,
            isRetryCacheReady = true,
        )
    }

    fun store(
        track: CurrentTrackSnapshot,
        transcodeConfig: TranscodeConfig,
        preparedUpload: PreparedUpload,
    ): PreparedUpload {
        stagingRoot.deleteRecursively()
        stagingRoot.mkdirs()
        return runCatching {
            val cachedAudio = copyIntoCache(preparedUpload.audioFile, stagingRoot, audioBaseName)
            val cachedCover = preparedUpload.coverFile?.let { copyIntoCache(it, stagingRoot, coverBaseName) }
            val metadata = CachedPreparedUpload(
                cacheKey = buildCacheKey(track, transcodeConfig),
                audioFileName = cachedAudio.name,
                audioMimeType = preparedUpload.audioMimeType,
                coverFileName = cachedCover?.name,
                coverMimeType = preparedUpload.coverMimeType.takeIf { cachedCover != null },
                title = preparedUpload.title,
                artist = preparedUpload.artist,
                album = preparedUpload.album,
                durationMs = preparedUpload.durationMs,
                clientCreatedAt = preparedUpload.clientCreatedAt,
            )
            File(stagingRoot, metadataFileName).writeText(
                json.encodeToString(CachedPreparedUpload.serializer(), metadata),
                Charsets.UTF_8,
            )
            swapStagingIntoCache()
            val cachedUploadAudio = File(cacheRoot, cachedAudio.name)
            val cachedUploadCover = cachedCover?.let { File(cacheRoot, it.name) }
            preparedUpload.audioFile.delete()
            preparedUpload.coverFile?.delete()
            preparedUpload.copy(
                audioFile = cachedUploadAudio,
                coverFile = cachedUploadCover,
                isRetryCacheReady = true,
            )
        }.getOrElse { error ->
            stagingRoot.deleteRecursively()
            throw error
        }
    }

    fun clear() {
        cacheRoot.deleteRecursively()
        stagingRoot.deleteRecursively()
        backupRoot.deleteRecursively()
    }

    private fun readMetadata(): CachedPreparedUpload? {
        if (!metadataFile.exists()) {
            return null
        }
        return runCatching {
            json.decodeFromString<CachedPreparedUpload>(metadataFile.readText(Charsets.UTF_8))
        }.getOrElse {
            clear()
            null
        }
    }

    private fun recoverBackupIfNeeded() {
        if (!cacheRoot.exists() && backupRoot.exists()) {
            backupRoot.renameTo(cacheRoot)
        } else if (cacheRoot.exists()) {
            backupRoot.deleteRecursively()
        }
    }

    private fun swapStagingIntoCache() {
        backupRoot.deleteRecursively()
        val hadPreviousCache = cacheRoot.exists()
        if (hadPreviousCache && !cacheRoot.renameTo(backupRoot)) {
            throw IOException("无法保留上一次准备好的音频缓存。")
        }
        if (!stagingRoot.renameTo(cacheRoot)) {
            if (hadPreviousCache && backupRoot.exists() && !backupRoot.renameTo(cacheRoot)) {
                throw IOException("无法恢复上一次准备好的音频缓存。")
            }
            throw IOException("无法保存本次准备好的音频缓存。")
        }
        backupRoot.deleteRecursively()
    }

    private fun copyIntoCache(source: File, root: File, baseName: String): File {
        val extension = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        val target = File(root, "$baseName$extension")
        source.copyTo(target, overwrite = true)
        return target
    }

    private fun buildCacheKey(
        track: CurrentTrackSnapshot,
        transcodeConfig: TranscodeConfig,
    ): String {
        val raw = listOf(
            track.powerampPath,
            track.documentUri,
            track.trackId,
            track.durationMs.toString(),
            transcodeConfig.outputFormat,
            transcodeConfig.audioCodec,
            transcodeConfig.bitrateKbps.toString(),
            transcodeConfig.sampleRateHz.toString(),
            transcodeConfig.channels.toString(),
            transcodeConfig.loudnessMode,
            transcodeConfig.maxDurationSeconds.toString(),
            transcodeConfig.maxOutputSizeMb.toString(),
        ).joinToString(separator = "\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    @Serializable
    private data class CachedPreparedUpload(
        val cacheKey: String,
        val audioFileName: String,
        val audioMimeType: String,
        val coverFileName: String? = null,
        val coverMimeType: String? = null,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val clientCreatedAt: String,
    )

    private companion object {
        const val cacheDirectoryName = "prepared-upload-cache"
        const val stagingDirectoryName = "prepared-upload-cache-staging"
        const val backupDirectoryName = "prepared-upload-cache-backup"
        const val metadataFileName = "metadata.json"
        const val audioBaseName = "audio"
        const val coverBaseName = "cover"
    }
}
