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
 *
 * A request belongs to the document it was made for ([owner] when it
 * starts). It waits for a page that is on screen ([awaitPage]) before it
 * asks, and if the document changes before the answer ([cancel], or a
 * different [owner] by then) it is null: a diagram is never taken from the
 * next document and saved under this one's name (edge-cases "Export PDF from
 * Code did nothing, then printed the next file", the same flaw in exports).
 */
class DiagramExports(
    private val send: (HostMessage) -> Unit,
    private val onFinished: () -> Unit = {},
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val owner: () -> Any? = { null },
    private val awaitPage: suspend () -> Boolean = { true },
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
    ): ByteArray? {
        val document = owner()
        return serial.withLock {
            if (!awaitPage() || owner() !== document) return@withLock null
            val answer = CompletableDeferred<ByteArray?>()
            val id = nextId++
            pendingId = id
            pending = answer
            send(HostMessage.ExportImage(id, line, png, scale))
            try {
                withTimeoutOrNull(timeoutMillis) { answer.await() }?.takeIf { owner() === document }
            } finally {
                pending = null
                pendingId = 0
                onFinished()
            }
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

    /** The page is given another document or replaced: a waiting export gets null now. */
    fun cancel() {
        pendingId = 0
        pending?.complete(null)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
