package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage

/**
 * One print request at a time: the page is asked to render every block, and
 * the system dialog opens only when it says it is ready, so the PDF never has
 * holes where virtualised sections were.
 *
 * A request belongs to the page as it was when it was made (edge-cases
 * "Export PDF from Code did nothing, then printed the next file"). It ends
 * exactly once, with a [PrintOutcome]:
 * - the page answers with this request's id: the dialog opens ([PrintOutcome.Opened]);
 * - the page is given a document, even the same one again: [PrintOutcome.DocumentChanged];
 * - the page is destroyed: [PrintOutcome.PageGone];
 * - no answer within [timeoutMillis]: [PrintOutcome.TimedOut];
 * - a newer request replaces it: [PrintOutcome.Superseded].
 *
 * A `printReady` for any other id, or after the request ended, opens nothing.
 *
 * @param sendToPage sends to the current page, in order with everything else.
 * @param openDialog opens the system dialog for the page as it is now; false if it cannot.
 * @param schedule runs an action after a delay and returns what cancels it (the view's handler).
 */
internal class PrintSession(
    private val sendToPage: (HostMessage) -> Unit,
    private val openDialog: (jobName: String) -> Boolean,
    private val schedule: (delayMillis: Long, action: () -> Unit) -> (() -> Unit),
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private class Pending(
        val id: Int,
        val jobName: String,
        val onOutcome: (PrintOutcome) -> Unit,
    ) {
        var cancelTimeout: () -> Unit = {}
    }

    private var pending: Pending? = null
    private var nextId = 1

    /** True while a request waits for the page. */
    val busy: Boolean get() = pending != null

    /** Asks the page to render everything for [jobName]; [onOutcome] hears how it ended, once. */
    fun request(
        jobName: String,
        onOutcome: (PrintOutcome) -> Unit,
    ) {
        end(PrintOutcome.Superseded, tellPage = true)
        val request = Pending(nextId++, jobName, onOutcome)
        pending = request
        request.cancelTimeout = schedule(timeoutMillis) { if (pending === request) end(PrintOutcome.TimedOut, true) }
        sendToPage(HostMessage.PreparePrint(request.id))
    }

    /** The view sent the page a document: whatever was being prepared is not what was asked for. */
    fun onDocumentLoaded() = end(PrintOutcome.DocumentChanged, tellPage = true)

    /** The page is gone (released, or its renderer died): nothing will answer. */
    fun onPageGone() = end(PrintOutcome.PageGone, tellPage = false)

    /** The caller stopped waiting. */
    fun cancel() = end(PrintOutcome.Cancelled, tellPage = true)

    /** The page rendered everything for request [requestId]. */
    fun onPageReady(requestId: Int) {
        val request = pending?.takeIf { it.id == requestId } ?: return
        pending = null
        request.cancelTimeout()
        if (openDialog(request.jobName)) {
            request.onOutcome(PrintOutcome.Opened)
        } else {
            sendToPage(HostMessage.FinishPrint)
            request.onOutcome(PrintOutcome.PageGone)
        }
    }

    private fun end(
        outcome: PrintOutcome,
        tellPage: Boolean,
    ) {
        val request = pending ?: return
        pending = null
        request.cancelTimeout()
        // The page may still be rendering everything: back to its virtualised reading state.
        if (tellPage) sendToPage(HostMessage.FinishPrint)
        request.onOutcome(outcome)
    }

    companion object {
        /**
         * Long enough for a long document full of diagrams (each has its own
         * 5 s cap), short enough that a request never lingers into another
         * document's session.
         */
        const val TIMEOUT_MILLIS: Long = 60_000L
    }
}
