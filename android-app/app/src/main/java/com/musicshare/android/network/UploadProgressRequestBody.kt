package com.musicshare.android.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

data class UploadProgress(
    val bytesWritten: Long,
    val contentLength: Long,
) {
    val percent: Int = if (contentLength > 0L) {
        ((bytesWritten.coerceAtMost(contentLength) * 100L) / contentLength).toInt().coerceIn(0, 100)
    } else {
        -1
    }
}

internal class UploadProgressRequestBody(
    private val delegate: RequestBody,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val minPercentStep: Int = 5,
    private val minIntervalMillis: Long = 1_000L,
    private val onProgress: (UploadProgress) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isDuplex(): Boolean = delegate.isDuplex()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val totalBytes = contentLength()
        var bytesWritten = 0L
        var lastReportedBytes = Long.MIN_VALUE
        var lastReportedPercent = Int.MIN_VALUE
        var lastReportedAt = Long.MIN_VALUE

        fun reportIfNeeded(force: Boolean) {
            val progress = UploadProgress(
                bytesWritten = bytesWritten,
                contentLength = totalBytes,
            )
            if (progress.bytesWritten == lastReportedBytes && progress.percent == lastReportedPercent) {
                return
            }
            val now = nowMillis()
            val shouldReport = force ||
                lastReportedPercent == Int.MIN_VALUE ||
                (progress.percent >= 0 && progress.percent >= lastReportedPercent + minPercentStep) ||
                now - lastReportedAt >= minIntervalMillis
            if (shouldReport) {
                lastReportedBytes = progress.bytesWritten
                lastReportedPercent = progress.percent
                lastReportedAt = now
                onProgress(progress)
            }
        }

        reportIfNeeded(force = true)
        val countingSink = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                reportIfNeeded(force = totalBytes > 0L && bytesWritten >= totalBytes)
            }
        }.buffer()

        delegate.writeTo(countingSink)
        countingSink.flush()
        reportIfNeeded(force = true)
    }
}
