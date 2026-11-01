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
import com.scorchsoft.ssmarkdown.preview.bridge.ThemeFonts
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

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
@Suppress("TooManyFunctions") // The preview's whole host-side API is this one view, by design.
class PreviewView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs) {
        var listener: PreviewListener? = null
        var documentSource: DocumentSource? = null

        /** Opens pictures next to the document for `![](img/x.png)`; null leaves them broken. */
        var resourceResolver: DocumentResourceResolver? = null

        /**
         * The installed-fonts directory, served at `/fonts/` so the `@font-face`
         * URLs in [HostMessage.Theme.fonts] resolve (feature/fonts' `FontUrls`
         * builds them on the same base). Null serves no fonts from storage;
         * bundled fonts come from assets either way.
         */
        var fontDirectory: File? = null

        /** Rich-text exports waiting for the page's answer, by request id. Main thread only. */
        private val htmlRequests = HashMap<Int, (String?) -> Unit>()
        private var nextHtmlRequest = 1

        private val recovery = RendererRecovery()
        private val channel: PreviewChannel =
            PreviewChannel(onMessage = { message ->
                when (message) {
                    is PageMessage.TopLine -> topLine = message.line
                    is PageMessage.PrintReady -> printing.onPageReady(webView)
                    is PageMessage.HtmlResult -> htmlRequests.remove(message.requestId)?.invoke(message.html)
                    else -> Unit
                }
                listener?.let(message::deliverTo)
            })
        private val assetLoader =
            PreviewWebViews.assetLoader(context, resolver = { resourceResolver }, fonts = { fontDirectory })
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

        /**
         * Applies a theme; [backgroundColor] is painted before the page draws,
         * so there is no flash. [fonts] are the theme's `@font-face` rules and
         * stacks as this phone has them; without them the page falls back to
         * system fonts.
         */
        fun setTheme(
            themeJson: String,
            dark: Boolean,
            backgroundColor: Int,
            fonts: ThemeFonts? = null,
        ) {
            backgroundColour = backgroundColor
            setBackgroundColor(backgroundColor)
            webView?.setBackgroundColor(backgroundColor)
            val message = HostMessage.Theme(themeJson, dark, fonts)
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

        /**
         * "Copy as rich text": the page's own rendering of source lines
         * `[fromLine, toLine)` as clean HTML (no editing attributes or chips),
         * or null when the preview cannot give it within [timeoutMillis]; the
         * caller then copies plain Markdown. Only the sections holding those
         * lines are rendered. Call on the main thread.
         */
        suspend fun exportHtml(
            fromLine: Int,
            toLine: Int,
            timeoutMillis: Long = EXPORT_TIMEOUT_MILLIS,
        ): String? {
            val id = nextHtmlRequest++
            return withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { done ->
                    htmlRequests[id] = { html -> done.resume(html) }
                    done.invokeOnCancellation { post { htmlRequests.remove(id) } }
                    send(HostMessage.ExportHtml(id, fromLine, toLine))
                }
            }
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
            // The page that would have answered is gone: waiting exports copy plain text instead.
            val waiting = htmlRequests.values.toList()
            htmlRequests.clear()
            waiting.forEach { it(null) }
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
            documentSource?.snapshot()?.let { channel.send(HostMessage.Load(it.rev, it.text, topLine, it.epoch)) }
        }

        private companion object {
            const val EXPORT_TIMEOUT_MILLIS = 5_000L
        }
    }
