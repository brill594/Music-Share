package com.musicshare.android.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadProgressRequestBodyTest {
    @Test
    fun reportsMonotonicProgressWhileStreamingDelegateBody() {
        val payload = ByteArray(100) { index -> index.toByte() }
        val delegate = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun contentLength(): Long = payload.size.toLong()

            override fun writeTo(sink: BufferedSink) {
                sink.write(payload)
            }
        }
        val events = mutableListOf<UploadProgress>()
        val body = UploadProgressRequestBody(
            delegate = delegate,
            nowMillis = { 1_000L },
            minPercentStep = 1,
            minIntervalMillis = 0L,
            onProgress = events::add,
        )

        val sink = Buffer()
        body.writeTo(sink)

        assertArrayEquals(payload, sink.readByteArray())
        assertEquals(0, events.first().percent)
        assertEquals(100, events.last().percent)
        assertEquals(payload.size.toLong(), events.last().bytesWritten)
        assertEquals(payload.size.toLong(), events.last().contentLength)
        assertTrue(events.zipWithNext().all { (previous, next) -> previous.percent <= next.percent })
    }

    @Test
    fun marksBodyOneShotToPreventTransparentRetransmission() {
        val delegate = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun contentLength(): Long = 0L

            override fun writeTo(sink: BufferedSink) = Unit
        }

        val body = UploadProgressRequestBody(delegate = delegate, onProgress = {})

        assertTrue(body.isOneShot())
    }
}
