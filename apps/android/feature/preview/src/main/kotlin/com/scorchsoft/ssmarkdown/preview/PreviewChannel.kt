package com.scorchsoft.ssmarkdown.preview

import android.net.Uri
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import com.scorchsoft.ssmarkdown.preview.bridge.BridgeCodec
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PROTOCOL_VERSION
import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage

/**
 * The Kotlin end of the bridge: one WebMessagePort pair per page load.
 *
 * Kotlin keeps one port and hands the other to the page in a single
 * "ssmd:init" message aimed at the bundle's origin. No JavaScript interface is
 * ever added to the WebView, so page content has no handle on the app.
 * Messages sent before the page says it is ready are queued, in order.
 */
internal class PreviewChannel(
    private val onMessage: (PageMessage) -> Unit,
) {
    private var port: WebMessagePort? = null
    private var ready = false
    private val queue = ArrayDeque<String>()

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
                    val decoded = message.data?.let(BridgeCodec::decode) ?: return
                    if (decoded is PageMessage.Ready) onReady(decoded)
                    onMessage(decoded)
                }
            },
        )
        port = ours
        webView.postWebMessage(WebMessage(INIT_MESSAGE, arrayOf(theirs)), Uri.parse(PreviewWebViewClient.ORIGIN))
    }

    fun send(message: HostMessage) {
        val encoded = BridgeCodec.encode(message)
        val target = port
        if (ready && target != null) target.postMessage(WebMessage(encoded)) else queue.addLast(encoded)
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
        val target = port ?: return
        while (queue.isNotEmpty()) target.postMessage(WebMessage(queue.removeFirst()))
    }

    private companion object {
        const val INIT_MESSAGE = "ssmd:init"
    }
}
