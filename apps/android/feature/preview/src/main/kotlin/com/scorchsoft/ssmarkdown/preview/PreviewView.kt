package com.scorchsoft.ssmarkdown.preview

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PreviewSettings

/**
 * The Markdown preview: one WebView running the bundle in `assets/preview`,
 * driven over a WebMessagePort. Create it once and reuse it across documents
 * (a WebView costs tens of megabytes to start); call [release] when the
 * screen that owns it is gone for good.
 *
 * It also owns renderer recovery: if the renderer dies, the WebView is
 * rebuilt and reloaded at the same line from [documentSource], leaner after an
 * out-of-memory kill, and paused after two deaths in a minute.
 */
class PreviewView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs) {
        var listener: PreviewListener? = null
        var documentSource: DocumentSource? = null
        var resourceResolver: DocumentResourceResolver? = null

        private val recovery = RendererRecovery()
        private val channel: PreviewChannel =
            PreviewChannel { message ->
                when (message) {
                    is PageMessage.TopLine -> topLine = message.line
                    is PageMessage.PrintReady -> printing.onPageReady(webView)
                    else -> Unit
                }
                listener?.let(message::deliverTo)
            }
        private val assetLoader = PreviewWebViews.assetLoader(context) { resourceResolver }
        private val pinch = PinchZoom(context) { zoom -> applySettings(settings.copy(zoom = zoom)) }
        private var webView: WebView? = null
        private var theme: HostMessage.Theme? = null
        private var backgroundColour = Color.WHITE
        private var settings = PreviewSettings()
        private var topLine = 0
        private val printing: PrintSession = PrintSession(context) { channel.send(HostMessage.FinishPrint) }

        /**
         * Sends a message to the page: [HostMessage.Load] to open a document,
         * then line [HostMessage.Edit]s, acks, scrolls, table keys and export
         * requests. Queued, in order, until the page is ready.
         */
        fun send(message: HostMessage) {
            ensureWebView()
            channel.send(message)
        }

        /** Applies a theme; [backgroundColor] is painted before the page draws, so there is no flash. */
        fun setTheme(
            themeJson: String,
            dark: Boolean,
            backgroundColor: Int,
        ) {
            backgroundColour = backgroundColor
            setBackgroundColor(backgroundColor)
            webView?.setBackgroundColor(backgroundColor)
            val message = HostMessage.Theme(themeJson, dark)
            theme = message
            send(message)
        }

        fun applySettings(value: PreviewSettings) {
            settings = value.copy(lean = value.lean || recovery.lean)
            pinch.set(settings.zoom)
            send(HostMessage.Settings(settings))
        }

        /** Renders every block, then opens the system print dialog ("Save as PDF" included). */
        fun print(jobName: String) {
            printing.request(jobName)
            send(HostMessage.PreparePrint)
        }

        /** "Retry" on Preview paused. */
        fun retry() {
            recovery.reset()
            rebuild()
            listener?.onRendererStateChanged(RendererState.RUNNING)
        }

        /** Destroys the WebView. The view can be used again; it rebuilds on the next call. */
        fun release() {
            channel.close()
            webView?.let {
                removeView(it)
                it.destroy()
            }
            webView = null
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val pinching = pinch.onTouchEvent(event)
            return if (pinching) true else super.dispatchTouchEvent(event)
        }

        private fun ensureWebView() {
            if (webView != null) return
            val client =
                PreviewWebViewClient(
                    assetLoader,
                    onPageFinished = { webView?.let(channel::connect) },
                    onRendererGone = { didCrash ->
                        recover(recovery.onRendererGone(SystemClock.elapsedRealtime(), didCrash))
                    },
                )
            val view = PreviewWebViews.create(context, backgroundColour, client)
            addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            webView = view
            // Theme and settings go first so the document's first paint is already right.
            theme?.let(channel::send)
            channel.send(HostMessage.Settings(settings))
            view.loadUrl(PreviewWebViewClient.PREVIEW_URL)
        }

        private fun recover(action: RecoveryAction) {
            when (action) {
                is RecoveryAction.Restart -> {
                    settings = settings.copy(lean = action.lean)
                    rebuild()
                    listener?.onRendererStateChanged(RendererState.RESTARTED)
                }

                RecoveryAction.Pause -> {
                    release()
                    listener?.onRendererStateChanged(RendererState.PAUSED)
                }
            }
        }

        /** A fresh WebView reloaded at the last top line, from the host's current document. */
        private fun rebuild() {
            release()
            ensureWebView()
            documentSource?.snapshot()?.let { channel.send(HostMessage.Load(it.rev, it.text, topLine)) }
        }
    }
