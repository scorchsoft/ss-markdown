package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.BridgeCodec
import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PERF-23's remainder: an exported PNG's base64 is decoded with the
 * message, off the main thread for a large one, so delivering it only
 * hands bytes on. Robolectric for the real `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class ExportDecodeTest {
    @Test
    fun `PERF-23 a large PNG export is decoded off the delivering thread, which only hands bytes on`() {
        val png = ByteArray(PNG_BYTES) { (it % PRIME).toByte() }
        val raw =
            """{"type":"exportResult","requestId":7,"mime":"image/png","data":"""" +
                Base64.getEncoder().encodeToString(png) + "\"}"
        val worker = Executors.newSingleThreadExecutor()
        val onMain = ArrayDeque<Runnable>()
        var decodedOn: Thread? = null
        var delivered: PageMessage? = null
        val decoder =
            OrderedDecoder(
                background = worker,
                main = Executor { synchronized(onMain) { onMain.addLast(it) } },
                decode = { text ->
                    decodedOn = Thread.currentThread()
                    BridgeCodec.decode(text)
                },
                deliver = { delivered = it },
            )

        decoder.onData(raw)
        worker.shutdown()
        worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
        synchronized(onMain) { onMain.removeFirst() }.run() // The main thread delivers.

        val export = delivered as PageMessage.ExportResult
        assertNotSame(Thread.currentThread(), decodedOn)
        assertArrayEquals("decoded before delivery", png, export.bytes)
        var handed: ByteArray? = null
        export.deliverTo(
            object : PreviewListener {
                override fun onExportResult(
                    requestId: Int,
                    mime: String,
                    bytes: ByteArray,
                ) {
                    handed = bytes
                }
            },
        )
        assertSame("delivery decodes nothing", export.bytes, handed)
    }

    @Test
    fun `an export whose base64 is broken is dropped by the decoder, never thrown at delivery`() {
        val raw = """{"type":"exportResult","requestId":1,"mime":"image/png","data":"!!not base64!!"}"""

        assertNull(BridgeCodec.decode(raw))
    }

    private companion object {
        /** About a 2x diagram's PNG: well past the inline limit, so it decodes in the background. */
        const val PNG_BYTES = 512 * 1024
        const val PRIME = 251
        const val WAIT_SECONDS = 10L
    }
}
