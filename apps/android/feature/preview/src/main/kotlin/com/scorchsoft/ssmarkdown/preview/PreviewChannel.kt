package com.scorchsoft.ssmarkdown.preview

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import com.scorchsoft.ssmarkdown.preview.bridge.BridgeCodec
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PROTOCOL_VERSION
import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage
import java.util.concurrent.Executor

/**
 * The Kotlin end of the bridge: one WebMessagePort pair per page load.
 *
 * Kotlin keeps one port and hands the other to the page in a single
 * "ssmd:init" message aimed at the bundle's origin. No JavaScript interface is
 * ever added to the WebView, so page content has no handle on the app.
 * Messages sent before the page says it is ready are queued, in order.
 */
internal class PreviewChannel(
    /** Where large messages are framed and decoded; the host's thread, injected through the view (CC-D). */
    background: Executor,
    private val onMessage: (PageMessage) -> Unit,
    private val queue: FrameQueue = FrameQueue(background, MainThread),
) {
    private var port: WebMessagePort? = null
    private var ready = false

    private val decoder =
        OrderedDecoder(
            background = background,
            main = MainThread,
            decode = BridgeCodec::decode,
            deliver = { decoded ->
                if (decoded is PageMessage.Ready) onReady(decoded)
                onMessage(decoded)
            },
        )

    init {
        queue.onReady = ::flush
    }

    /** Opens the channel to the page that just finished loading in [webView]. */
    fun connect(webView: WebView) {
        // A new page: drop the old port but keep what is queued for the new one.
        port?.close()
        port = null
        ready = false
        val (ours, theirs) = webView.createWebMessageChannel()
        ours.setWebMessageCallback(
            object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(
                    port: WebMessagePort,
                    message: WebMessage,
                ) {
                    message.data?.let(decoder::onData)
                }
            },
        )
        port = ours
        webView.postWebMessage(WebMessage(INIT_MESSAGE, arrayOf(theirs)), Uri.parse(PreviewWebViewClient.ORIGIN))
    }

    /** Sends [message] once the page is ready, after everything sent before it. */
    fun send(message: HostMessage) {
        queue.add(message)
    }

    /** Drops the port and anything queued; used when the renderer dies or the view is released. */
    fun close() {
        port?.close()
        port = null
        ready = false
        queue.clear()
    }

    private fun onReady(message: PageMessage.Ready) {
        if (message.version != PROTOCOL_VERSION) {
            onMessage(
                PageMessage.Error("Preview bundle speaks protocol ${message.version}, app speaks $PROTOCOL_VERSION"),
            )
        }
        ready = true
        flush()
    }

    private fun flush() {
        val target = port ?: return
        if (ready) queue.drainTo { target.postMessage(WebMessage(it)) }
    }

    /** Posts to the main thread, which owns the port. */
    private object MainThread : Executor {
        private val handler = Handler(Looper.getMainLooper())

        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }

    private companion object {
        const val INIT_MESSAGE = "ssmd:init"
    }
}
