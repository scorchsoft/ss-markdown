package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks the preview page for rendered diagrams, one at a time, and waits
 * for each answer. The page answers an export with `exportResult` and a
 * failure with a plain `error` (no request id), so requests are strictly
 * serial: whatever arrives while one is pending belongs to it. A page
 * that never answers (renderer gone, diagram too slow) times out to null
 * rather than hanging an export.
 */
class DiagramExports(
    private val send: (HostMessage) -> Unit,
    private val onFinished: () -> Unit = {},
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    /**
     * True while the page is drawing an export. Drawing scrolls the page to
     * the diagram, so its top-line reports are not the reader's and must
     * not move the anchor; [onFinished] scrolls it back afterwards.
     */
    val busy: Boolean get() = pending != null

    private val serial = Mutex()
    private var nextId = 1
    private var pendingId = 0
    private var pending: CompletableDeferred<ByteArray?>? = null

    /** The diagram whose fence starts at [line], as PNG at [scale] (1–3) or SVG; null if it could not be drawn. */
    suspend fun request(
        line: Int,
        png: Boolean,
        scale: Int = 2,
    ): ByteArray? =
        serial.withLock {
            val answer = CompletableDeferred<ByteArray?>()
            val id = nextId++
            pendingId = id
            pending = answer
            send(HostMessage.ExportImage(id, line, png, scale))
            try {
                withTimeoutOrNull(timeoutMillis) { answer.await() }
            } finally {
                pending = null
                pendingId = 0
                onFinished()
            }
        }

    /** `PreviewListener.onExportResult`. */
    fun onResult(
        requestId: Int,
        bytes: ByteArray,
    ) {
        if (requestId == pendingId) pending?.complete(bytes)
    }

    /** The page reported an error: if an export is waiting, that was its answer. */
    fun onError() {
        pending?.complete(null)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
