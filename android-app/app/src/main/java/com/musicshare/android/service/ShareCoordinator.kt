package com.musicshare.android.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import com.musicshare.android.artwork.AlbumArtworkRepository
import com.musicshare.android.data.AppStateStore
import com.musicshare.android.data.CurrentTrackSnapshot
import com.musicshare.android.data.TranscodeConfig
import com.musicshare.android.network.MusicShareBackendRepository
import com.musicshare.android.network.PreparedUpload
import com.musicshare.android.network.ShareItemDto
import com.musicshare.android.util.DocumentUriResolver
import com.musicshare.android.util.UserVisibleException
import com.musicshare.android.util.nowIso
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ShareCoordinator(
    private val context: Context,
    private val stateStore: AppStateStore,
    private val backendRepository: MusicShareBackendRepository,
    private val documentUriResolver: DocumentUriResolver,
    private val albumArtworkRepository: AlbumArtworkRepository,
) {
    private val shareMutex = Mutex()
    private val audioTranscoder = FfmpegAudioTranscoder(context)
    private val preparedUploadCache = PreparedUploadCache(context)

    suspend fun shareLatestTrack(): ShareItemDto = shareMutex.withLock {
        updateRuntime(
            processing = true,
            stage = "校验当前曲目",
            progressPercent = -1,
            lastError = "",
            lastShareUrl = "",
        )
        var preparedUpload: PreparedUpload? = null
        var uploadSucceeded = false
        try {
            val state = stateStore.read()
            val track = resolveTrack(state.latestTrack, state.musicTreeUri)
                ?: throw UserVisibleException("当前没有可分享的曲目。")
            preparedUpload = prepareUpload(track)
            updateRuntime(
                processing = true,
                stage = "上传到后端",
                progressPercent = 0,
            )
            val uploaded = backendRepository.upload(preparedUpload) { progress ->
                if (progress.percent >= 0) {
                    updateRuntimeBlocking(
                        processing = true,
                        stage = if (progress.percent >= 100) "等待后端生成分享链接" else "上传到后端",
                        progressPercent = progress.percent,
                    )
                }
            }
            uploadSucceeded = true
            copyToClipboard(uploaded.shareUrl)
            updateRuntime(
                processing = false,
                stage = "",
                progressPercent = -1,
                lastError = "",
                lastShareUrl = uploaded.shareUrl,
            )
            uploaded
        } catch (error: Throwable) {
            val finalMessage = buildFailureMessage(
                originalMessage = error.message ?: "分享失败。",
                hasReusablePreparedUpload = preparedUpload?.isRetryCacheReady == true && !uploadSucceeded,
            )
            updateRuntime(
                processing = false,
                stage = "",
                progressPercent = -1,
                lastError = finalMessage,
            )
            if (finalMessage != error.message) {
                throw UserVisibleException(finalMessage)
            }
            throw error
        }
    }

    suspend fun refreshTrackResolution() {
        val state = stateStore.read()
        val latest = state.latestTrack ?: return
        val resolved = resolveTrack(latest, state.musicTreeUri) ?: return
        val themed = if (albumArtworkRepository.hasUsableArtwork(resolved)) {
            resolved
        } else {
            albumArtworkRepository.extract(resolved)?.let {
                resolved.copy(
                    artUri = it.artUri,
                    artworkColorArgb = it.artworkColorArgb,
                )
            } ?: resolved.copy(artUri = "", artworkColorArgb = 0L)
        }
        stateStore.update { it.copy(latestTrack = themed) }
    }

    private suspend fun resolveTrack(
        snapshot: CurrentTrackSnapshot?,
        treeUri: String,
    ): CurrentTrackSnapshot? {
        snapshot ?: return null
        if (snapshot.documentUri.isNotBlank() && documentUriResolver.isReadable(context, snapshot.documentUri)) {
            return snapshot.copy(isResolvable = true)
        }
        val resolvedUri = documentUriResolver.resolve(treeUri, snapshot.powerampPath)?.toString().orEmpty()
        val readable = resolvedUri.isNotBlank() && documentUriResolver.isReadable(context, resolvedUri)
        return snapshot.copy(
            documentUri = resolvedUri,
            isResolvable = readable,
        ).takeIf { it.isResolvable }
    }

    private suspend fun prepareUpload(track: CurrentTrackSnapshot): PreparedUpload = withContext(Dispatchers.IO) {
        val appState = stateStore.read()
        preparedUploadCache.load(
            track = track,
            transcodeConfig = appState.transcode,
            expireAfterSeconds = appState.shareDefaults.expireAfterSeconds,
        )?.let { cached ->
            updateRuntime(
                processing = true,
                stage = "复用上次准备好的音频",
                progressPercent = 100,
            )
            return@withContext cached
        }
        updateRuntime(processing = true, stage = "读取音频元数据")
        val sourceUri = Uri.parse(track.documentUri)
        val metadata = readMetadata(
            sourceUri = sourceUri,
            fallbackTrack = track,
            maxDurationLimitMs = appState.transcode.maxDurationSeconds * 1_000L,
            maxOutputBytes = appState.transcode.maxOutputSizeMb * 1024L * 1024L,
        )
        if (metadata.durationMs > metadata.maxDurationLimitMs) {
            throw UserVisibleException("曲目时长超过当前配置上限。")
        }

        val targetSpec = FfmpegAudioTranscoder.TargetAudioSpec.from(appState.transcode)
        updateRuntime(processing = true, stage = "准备音频文件", progressPercent = -1)
        val sourceMime = metadata.audioMimeType
        val preparedAudio = if (targetSpec.matchesSourceMime(sourceMime)) {
            copySourceAudio(sourceUri, targetSpec.mimeType)
        } else {
            transcodeAudio(
                sourceUri = sourceUri,
                durationMs = metadata.durationMs,
                transcodeConfig = appState.transcode,
            )
        }
        val maxBytes = metadata.maxOutputBytes
        if (preparedAudio.file.length() > maxBytes) {
            preparedAudio.file.delete()
            throw UserVisibleException("音频文件超过当前配置的输出大小上限。")
        }

        updateRuntime(processing = true, stage = "提取封面", progressPercent = -1)
        val coverResult = extractCover(
            sourceUri = sourceUri,
            metadata = metadata,
        )

        val preparedUpload = PreparedUpload(
            audioFile = preparedAudio.file,
            audioMimeType = preparedAudio.audioMimeType,
            coverFile = coverResult?.first,
            coverMimeType = coverResult?.second,
            title = metadata.title.ifBlank { track.displayTitle() },
            artist = metadata.artist.ifBlank { track.artist },
            album = metadata.album.ifBlank { track.album },
            durationMs = metadata.durationMs,
            clientCreatedAt = track.updatedAt.ifBlank { nowIso() },
            expireAfterSeconds = appState.shareDefaults.expireAfterSeconds,
        )
        runCatching {
            preparedUploadCache.store(
                track = track,
                transcodeConfig = appState.transcode,
                preparedUpload = preparedUpload,
            )
        }.getOrElse {
            preparedUpload
        }
    }

    private suspend fun transcodeAudio(
        sourceUri: Uri,
        durationMs: Long,
        transcodeConfig: TranscodeConfig,
    ): PreparedAudioFile {
        val result = audioTranscoder.transcode(
            sourceUri = sourceUri,
            durationMs = durationMs,
            transcodeConfig = transcodeConfig,
            onProgress = { progress ->
                updateRuntimeBlocking(
                    processing = true,
                    stage = progress.stage,
                    progressPercent = progress.progressPercent,
                )
            },
        )
        return PreparedAudioFile(
            file = result.file,
            audioMimeType = result.audioMimeType,
        )
    }

    private suspend fun copySourceAudio(sourceUri: Uri, targetMimeType: String): PreparedAudioFile {
        val extension = extensionForMime(targetMimeType)
        val audioFile = File.createTempFile("music-share-${UUID.randomUUID()}", extension, context.cacheDir)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(audioFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw UserVisibleException("无法读取当前音频文件。")
        return PreparedAudioFile(
            file = audioFile,
            audioMimeType = targetMimeType,
        )
    }

    private suspend fun extractCover(
        sourceUri: Uri,
        metadata: ExtractedMetadata,
    ): Pair<File, String>? {
        metadata.coverBytes?.let { bytes ->
            val coverMime = detectImageMime(bytes)
            if (coverMime != null) {
                return writeCoverBytesToFile(bytes, coverMime)
            }
        }
        val extractedArtwork = audioTranscoder.extractEmbeddedArtwork(sourceUri) ?: return null
        return extractedArtwork.file to extractedArtwork.mimeType
    }

    private fun writeCoverBytesToFile(bytes: ByteArray, coverMime: String): Pair<File, String> {
        val coverFile = File.createTempFile(
            "music-share-cover-${UUID.randomUUID()}",
            extensionForMime(coverMime),
            context.cacheDir,
        )
        FileOutputStream(coverFile).use { output -> output.write(bytes) }
        return coverFile to coverMime
    }

    private fun readMetadata(
        sourceUri: Uri,
        fallbackTrack: CurrentTrackSnapshot,
        maxDurationLimitMs: Long,
        maxOutputBytes: Long,
    ): ExtractedMetadata {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, sourceUri)
            ExtractedMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
                    .ifBlank { fallbackTrack.title },
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                    .ifBlank { fallbackTrack.artist },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                    .ifBlank { fallbackTrack.album },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: fallbackTrack.durationMs,
                coverBytes = retriever.embeddedPicture,
                audioMimeType = context.contentResolver.getType(sourceUri)
                    ?: inferMimeFromPath(fallbackTrack.powerampPath)
                    ?: "application/octet-stream",
                maxDurationLimitMs = maxDurationLimitMs,
                maxOutputBytes = maxOutputBytes,
            )
        }.getOrElse {
            ExtractedMetadata(
                title = fallbackTrack.title,
                artist = fallbackTrack.artist,
                album = fallbackTrack.album,
                durationMs = fallbackTrack.durationMs,
                coverBytes = null,
                audioMimeType = inferMimeFromPath(fallbackTrack.powerampPath) ?: "application/octet-stream",
                maxDurationLimitMs = maxDurationLimitMs,
                maxOutputBytes = maxOutputBytes,
            )
        }.also {
            retriever.release()
        }
    }

    private suspend fun updateRuntime(
        processing: Boolean,
        stage: String,
        progressPercent: Int? = null,
        lastError: String? = null,
        lastShareUrl: String? = null,
    ) {
        stateStore.update { state ->
            state.copy(
                runtime = state.runtime.copy(
                    isProcessing = processing,
                    currentStage = stage,
                    progressPercent = progressPercent ?: state.runtime.progressPercent,
                    lastError = lastError ?: state.runtime.lastError,
                    lastShareUrl = lastShareUrl ?: state.runtime.lastShareUrl,
                    lastCompletedAt = if (!processing) nowIso() else state.runtime.lastCompletedAt,
                ),
            )
        }
    }

    private fun updateRuntimeBlocking(
        processing: Boolean,
        stage: String,
        progressPercent: Int,
    ) {
        runCatching {
            kotlinx.coroutines.runBlocking {
                updateRuntime(
                    processing = processing,
                    stage = stage,
                    progressPercent = progressPercent,
                )
            }
        }
    }

    private fun buildFailureMessage(
        originalMessage: String,
        hasReusablePreparedUpload: Boolean,
    ): String {
        if (!hasReusablePreparedUpload) {
            return originalMessage
        }
        if (originalMessage.contains("跳过转码")) {
            return originalMessage
        }
        return "$originalMessage 已保留最近一次准备好的音频，重试时会跳过转码并重新上传；如果刚才网络在完成后中断，请先刷新分享列表确认是否已生成链接。"
    }

    private suspend fun copyToClipboard(url: String) = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Music Share URL", url))
    }

    private fun inferMimeFromPath(path: String): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(path)
            ?.lowercase()
            .orEmpty()
        return when (extension) {
            "ogg", "oga", "opus" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/x-m4a"
            "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> null
        }
    }

    private fun extensionForMime(mimeType: String): String {
        return when (mimeType) {
            "audio/ogg" -> ".ogg"
            "audio/mpeg" -> ".mp3"
            "audio/mp4", "audio/x-m4a" -> ".m4a"
            "audio/aac" -> ".aac"
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".bin"
        }
    }

    private fun detectImageMime(bytes: ByteArray): String? {
        return when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
                "image/jpeg"
            bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() ->
                "image/png"
            bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                bytes.copyOfRange(8, 12).decodeToString() == "WEBP" ->
                "image/webp"
            else -> null
        }
    }

    private data class ExtractedMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val coverBytes: ByteArray?,
        val audioMimeType: String,
        val maxDurationLimitMs: Long,
        val maxOutputBytes: Long,
    )

    private data class PreparedAudioFile(
        val file: File,
        val audioMimeType: String,
    )
}
