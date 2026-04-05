package com.musicshare.android.service

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.musicshare.android.data.TranscodeConfig
import com.musicshare.android.util.UserVisibleException
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class FfmpegAudioTranscoder(
    private val context: Context,
) {
    suspend fun transcode(
        sourceUri: Uri,
        durationMs: Long,
        transcodeConfig: TranscodeConfig,
        onProgress: (TranscodeProgress) -> Unit = {},
    ): TranscodeResult = withContext(Dispatchers.IO) {
        val targetSpec = TargetAudioSpec.from(transcodeConfig)
        val stagedInputFile = File.createTempFile(
            "music-share-input-${UUID.randomUUID()}",
            ".source",
            context.cacheDir,
        )
        val outputFile = File.createTempFile(
            "music-share-${UUID.randomUUID()}",
            targetSpec.extension,
            context.cacheDir,
        )
        copySourceToLocalFile(sourceUri, stagedInputFile)
        val arguments = buildArguments(
            inputPath = stagedInputFile.absolutePath,
            outputPath = outputFile.absolutePath,
            targetSpec = targetSpec,
            transcodeConfig = transcodeConfig,
        )
        Log.i(
            logTag,
            "Starting transcode sourceUri=$sourceUri stagedInput=${stagedInputFile.absolutePath} " +
                "stagedBytes=${stagedInputFile.length()} output=${outputFile.absolutePath} args=$arguments",
        )

        var sessionId = -1L
        var lastLogLine = ""
        var lastProgressPercent = -1
        var lastProgressAt = 0L

        onProgress(TranscodeProgress(0, "FFmpeg 转换为 ${targetSpec.label}"))

        try {
            val session = suspendCancellableCoroutine<FFmpegSession> { continuation ->
                val runningSession = FFmpegKit.executeWithArgumentsAsync(
                    arguments.toTypedArray(),
                    { completedSession ->
                        if (continuation.isActive) {
                            continuation.resume(completedSession)
                        }
                    },
                    { log ->
                        val line = log.message?.trim().orEmpty()
                        if (line.isNotBlank()) {
                            lastLogLine = line
                            Log.d(logTag, "ffmpeg: $line")
                        }
                    },
                    { statistics ->
                        val progressPercent = statistics.toProgressPercent(durationMs)
                        if (progressPercent >= 0) {
                            val now = SystemClock.elapsedRealtime()
                            val shouldReport = progressPercent == 100 ||
                                progressPercent >= lastProgressPercent + 2 ||
                                now - lastProgressAt >= 700
                            if (shouldReport) {
                                lastProgressPercent = progressPercent
                                lastProgressAt = now
                                onProgress(
                                    TranscodeProgress(
                                        progressPercent = progressPercent,
                                        stage = "FFmpeg 转换为 ${targetSpec.label}",
                                    ),
                                )
                            }
                        }
                    },
                )
                sessionId = runningSession.sessionId
                continuation.invokeOnCancellation {
                    if (sessionId >= 0) {
                        FFmpegKit.cancel(sessionId)
                    }
                    outputFile.delete()
                    stagedInputFile.delete()
                }
            }

            if (!ReturnCode.isSuccess(session.returnCode)) {
                outputFile.delete()
                Log.e(
                    logTag,
                    "Transcode failed rc=${session.returnCode} failStack=${session.failStackTrace} lastLog=$lastLogLine",
                )
                val failReason = buildString {
                    append("FFmpeg 转换失败")
                    val returnCodeText = session.returnCode?.value?.toString().orEmpty()
                    if (returnCodeText.isNotBlank()) {
                        append(" (rc=$returnCodeText)")
                    }
                    if (lastLogLine.isNotBlank()) {
                        append("：$lastLogLine")
                    } else if (!session.failStackTrace.isNullOrBlank()) {
                        append("：${session.failStackTrace}")
                    }
                }
                throw UserVisibleException(failReason)
            }
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                outputFile.delete()
                Log.e(logTag, "Transcode produced empty output file.")
                throw UserVisibleException("FFmpeg 未生成有效音频文件。")
            }

            Log.i(logTag, "Transcode completed outputBytes=${outputFile.length()} mime=${targetSpec.mimeType}")
            onProgress(TranscodeProgress(100, "FFmpeg 转换完成"))
            TranscodeResult(
                file = outputFile,
                audioMimeType = targetSpec.mimeType,
            )
        } finally {
            stagedInputFile.delete()
        }
    }

    private fun copySourceToLocalFile(sourceUri: Uri, destination: File) {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        } ?: throw UserVisibleException("无法读取源音频文件。")
        Log.i(logTag, "Copied source to local file path=${destination.absolutePath} bytes=${destination.length()}")
    }

    private fun buildArguments(
        inputPath: String,
        outputPath: String,
        targetSpec: TargetAudioSpec,
        transcodeConfig: TranscodeConfig,
    ): List<String> {
        val arguments = mutableListOf(
            "-y",
            "-hide_banner",
            "-i",
            inputPath,
            "-vn",
            "-map_metadata",
            "0",
            "-c:a",
            targetSpec.ffmpegCodec,
            "-b:a",
            "${transcodeConfig.bitrateKbps}k",
            "-ar",
            targetSpec.resolveSampleRate(transcodeConfig.sampleRateHz).toString(),
            "-ac",
            transcodeConfig.channels.toString(),
        )
        if (transcodeConfig.loudnessMode == "normalize") {
            arguments += listOf("-af", "loudnorm")
        }
        arguments += outputPath
        return arguments
    }

    data class TranscodeProgress(
        val progressPercent: Int,
        val stage: String,
    )

    data class TranscodeResult(
        val file: File,
        val audioMimeType: String,
    )

    data class TargetAudioSpec(
        val outputFormat: String,
        val mimeType: String,
        val extension: String,
        val ffmpegCodec: String,
        val label: String,
    ) {
        fun matchesSourceMime(sourceMime: String): Boolean {
            return when (outputFormat) {
                "ogg" -> sourceMime == "audio/ogg"
                "m4a" -> sourceMime == "audio/mp4" || sourceMime == "audio/x-m4a"
                "mp3" -> sourceMime == "audio/mpeg"
                else -> false
            }
        }

        fun resolveSampleRate(requested: Int): Int {
            return when (ffmpegCodec) {
                "libopus" -> supportedOpusSampleRates
                    .minByOrNull { kotlin.math.abs(it - requested) }
                    ?: 48_000
                else -> requested
            }
        }

        companion object {
            private val supportedOpusSampleRates = listOf(48_000, 24_000, 16_000, 12_000, 8_000)

            fun from(config: TranscodeConfig): TargetAudioSpec {
                return when (config.outputFormat) {
                    "ogg" -> when (config.audioCodec) {
                        "opus" -> TargetAudioSpec(
                            outputFormat = "ogg",
                            mimeType = "audio/ogg",
                            extension = ".ogg",
                            ffmpegCodec = "libopus",
                            label = "OGG / Opus",
                        )
                        "vorbis" -> TargetAudioSpec(
                            outputFormat = "ogg",
                            mimeType = "audio/ogg",
                            extension = ".ogg",
                            ffmpegCodec = "libvorbis",
                            label = "OGG / Vorbis",
                        )
                        else -> throw UserVisibleException("当前转码配置不支持 OGG + ${config.audioCodec}。")
                    }
                    "m4a" -> {
                        if (config.audioCodec != "aac") {
                            throw UserVisibleException("当前转码配置不支持 M4A + ${config.audioCodec}。")
                        }
                        TargetAudioSpec(
                            outputFormat = "m4a",
                            mimeType = "audio/mp4",
                            extension = ".m4a",
                            ffmpegCodec = "aac",
                            label = "M4A / AAC",
                        )
                    }
                    "mp3" -> {
                        if (config.audioCodec != "mp3") {
                            throw UserVisibleException("当前转码配置不支持 MP3 + ${config.audioCodec}。")
                        }
                        TargetAudioSpec(
                            outputFormat = "mp3",
                            mimeType = "audio/mpeg",
                            extension = ".mp3",
                            ffmpegCodec = "libmp3lame",
                            label = "MP3",
                        )
                    }
                    else -> throw UserVisibleException("不支持的输出格式：${config.outputFormat}")
                }
            }
        }
    }

    private companion object {
        const val logTag = "MusicShareFFmpeg"
    }
}

private fun Statistics.toProgressPercent(durationMs: Long): Int {
    if (durationMs <= 0L) {
        return -1
    }
    val currentTimeMs = time
    if (currentTimeMs < 0L) {
        return -1
    }
    return ((currentTimeMs.toDouble() / durationMs.toDouble()) * 100.0)
        .roundToInt()
        .coerceIn(0, 100)
}
