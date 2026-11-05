package com.scorchsoft.ssmarkdown.preview

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.AttributeSet
import android.view.ActionMode
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
import java.util.concurrent.Executor
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
    ) : FrameLayout(context, attrs),
        PrintTarget {
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

        /**
         * Where large page messages are framed and decoded off the main
         * thread. The host injects one thread for every preview (AppGraph's
         * `previewEncoder`: loads are rare, and one at a time keeps memory
         * flat); the default runs inline, for tests and previews. It used
         * to be a hidden process-wide executor (code review CC-D).
         */
        var backgroundExecutor: Executor = Executor(Runnable::run)

        /** Formatted copies waiting for the page's answer, by request id. Main thread only. */
        private val htmlRequests = HashMap<Int, (FormattedCopy?) -> Unit>()

        /** Selection copies waiting for the page's answer, by request id. Main thread only. */
        private val selectionRequests = HashMap<Int, (PageMessage.SelectionCopyResult?) -> Unit>()
        private var nextHtmlRequest = 1

        private val recovery = RendererRecovery()
        private val refreshTrace = RefreshTrace()
        private val channel: PreviewChannel =
            PreviewChannel(Executor { backgroundExecutor.execute(it) }, onMessage = { message ->
                when (message) {
                    is PageMessage.Rendered -> refreshTrace.onRendered(message.rev)
                    is PageMessage.TopLine -> topLine = message.line
                    is PageMessage.PrintReady -> printing.onPageReady(message.requestId)
                    is PageMessage.HtmlResult -> htmlRequests.remove(message.requestId)?.invoke(message.copy)
                    is PageMessage.SelectionCopyResult -> selectionRequests.remove(message.requestId)?.invoke(message)
                    is PageMessage.ImageQuery -> answerImages(message)
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

        /** The binding of the document the page was given last: only its pictures are served. */
        @Volatile private var pageEpoch = 0
        private val printing: PrintSession =
            PrintSession(
                sendToPage = ::send,
                openDialog = { job ->
                    webView?.let { web ->
                        PreviewPrinter.print(context, web, job, screenBackground = backgroundColour) {
                            channel.send(HostMessage.FinishPrint)
                        }
                    } != null
                },
                schedule = { delay, action ->
                    val run = Runnable(action)
                    postDelayed(run, delay)
                    ({ removeCallbacks(run) })
                },
            )

        /**
         * Sends a message to the page: [HostMessage.Load] to open a document,
         * then line [HostMessage.Edit]s, acks, scrolls, table keys and export
         * requests. Queued, in order, until the page is ready.
         */
        fun send(message: HostMessage) {
            // The page is about to hold a document (another one, or the same one afresh): anything
            // asked of the page it holds now must not be answered from the next one.
            if (message is HostMessage.Load) {
                abandonPageRequests()
                pageEpoch = message.epoch
            }
            ensureWebView()
            refreshTrace.onSent(message)
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

        /** In a visible window and laid out: the page's frames, timers and idle callbacks are running. */
        override val isOnScreen: Boolean
            get() = isAttachedToWindow && windowVisibility == VISIBLE && isShown && width > 0 && height > 0

        /**
         * Renders every block, then opens the system print dialog ("Save as
         * PDF" included), for the document the page holds now. Ends without a
         * dialog if the page is given a document first, is destroyed, or does
         * not answer within a minute (edge-cases "Export PDF from Code did
         * nothing, then printed the next file"). Call on the main thread.
         */
        override suspend fun print(jobName: String): PrintOutcome =
            suspendCancellableCoroutine { done ->
                printing.request(jobName) { outcome -> if (done.isActive) done.resume(outcome) }
                done.invokeOnCancellation { post { printing.cancel() } }
            }

        /**
         * "Copy formatted text": source lines `[fromLine, toLine)` as HTML with
         * inline light styles and its plain-text twin, together at most
         * [maxChars], or null when the page cannot give it within [timeoutMillis]
         * or is given another document first. Only the sections holding those
         * lines are rendered. Call on the main thread.
         */
        override suspend fun exportFormatted(
            fromLine: Int,
            toLine: Int,
            maxChars: Int,
        ): FormattedCopy? = exportFormatted(fromLine, toLine, maxChars, EXPORT_TIMEOUT_MILLIS)

        /** [exportFormatted] with its own [timeoutMillis]. */
        suspend fun exportFormatted(
            fromLine: Int,
            toLine: Int,
            maxChars: Int,
            timeoutMillis: Long,
        ): FormattedCopy? {
            val id = nextHtmlRequest++
            return withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { done ->
                    htmlRequests[id] = { copy -> done.resume(copy) }
                    done.invokeOnCancellation { post { htmlRequests.remove(id) } }
                    send(HostMessage.ExportHtml(id, fromLine, toLine, maxChars))
                }
            }
        }

        /**
         * The host can now see more pictures (a folder was granted, or the
         * folder changed): every placeholder for a picture beside the
         * document tries again.
         */
        fun refreshImages() = send(HostMessage.ImagesChanged)

        /**
         * Says why each picture the page could not load cannot be shown: one
         * answer per query, resolved on [backgroundExecutor] because it reads
         * storage. A query from a page that has since been given another
         * document is answered anyway; the page drops answers it no longer
         * wants.
         */
        private fun answerImages(query: PageMessage.ImageQuery) {
            val resolver = resourceResolver
            backgroundExecutor.execute {
                val statuses = LinkedHashMap<String, ImageStatus>()
                for (key in query.paths) {
                    val relative = DocPaths.relative(key)
                    statuses[key] =
                        if (relative == null || resolver == null) ImageStatus.NOT_FOUND else resolver.status(relative)
                }
                post {
                    channel.send(HostMessage.ImageStatus(query.id, statuses))
                    listener?.onImageStatuses(statuses)
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
            printing.onPageGone()
            abandonPageRequests()
            webView?.let {
                removeView(it)
                it.destroy()
            }
            webView = null
        }

        /**
         * The page is about to hold another document, or is gone: a print it
         * was preparing ends without a dialog, and formatted copies waiting on
         * it get null (the caller says so and copies the Markdown).
         */
        private fun abandonPageRequests() {
            printing.onDocumentLoaded()
            val waiting = htmlRequests.values.toList()
            htmlRequests.clear()
            waiting.forEach { it(null) }
            val selections = selectionRequests.values.toList()
            selectionRequests.clear()
            selections.forEach { it(null) }
        }

        /**
         * The WebView's text-selection menu, with "Paste as plain text" made
         * ours ([PlainPasteCallback]): after Paste, for a formatted clip.
         */
        override fun startActionModeForChild(
            originalView: View,
            callback: ActionMode.Callback,
            type: Int,
        ): ActionMode? {
            val withPaste =
                PlainPasteCallback(
                    delegate = callback,
                    resources = resources,
                    isRich = { PlainPasteCallback.clipIsRich(context) },
                    pastePlain = ::pastePlain,
                    schedule = { delay, action -> postDelayed(action, delay) },
                )
            val wrapped = CopyChoiceCallback(withPaste, resources, ::copySelection)
            return super.startActionModeForChild(originalView, wrapped, type)
        }

        /** Ctrl+Shift+V while the page has focus: "Paste as plain text", as in Code. */
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val plainPaste =
                event.keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed && event.isShiftPressed &&
                    !event.isAltPressed
            if (!plainPaste || webView?.hasFocus() != true) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) pastePlain()
            return true
        }

        /**
         * The selection menu's "Copy Markdown" / "Copy formatted": asks the
         * page for the selection's copies, hands them to the listener, and
         * finishes [mode] once answered (or after [COPY_TIMEOUT_MILLIS]).
         */
        private fun copySelection(
            formatted: Boolean,
            mode: ActionMode,
        ) {
            val id = nextHtmlRequest++
            val finish = Runnable { mode.finish() }
            selectionRequests[id] = { result ->
                removeCallbacks(finish)
                mode.finish()
                val markdown = result?.markdown
                if (markdown != null) listener?.onSelectionCopy(formatted, markdown, result.copy)
            }
            postDelayed(
                {
                    selectionRequests.remove(id)
                    finish.run()
                },
                COPY_TIMEOUT_MILLIS,
            )
            send(HostMessage.CopySelection(id, MAX_COPY_CHARS))
        }

        /** Sends the clip to the page, which inserts its words at the selection being edited. */
        fun pastePlain() {
            val (text, html) = PlainPasteCallback.readClip(context) ?: return
            if (text.isEmpty() && html.isEmpty()) return
            send(HostMessage.PastePlain(text, html))
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
                    pageEpoch = { pageEpoch },
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
            documentSource?.snapshot()?.let {
                pageEpoch = it.epoch
                channel.send(HostMessage.Load(it.rev, it.text, topLine, it.epoch))
            }
        }

        private companion object {
            /** Pictures and diagrams take the page up to 8 s (exportHtml.ts); this leaves room for the words. */
            const val EXPORT_TIMEOUT_MILLIS = 20_000L

            /** A selection copy embeds pictures within 8 s too (selectionCopy.ts). */
            const val COPY_TIMEOUT_MILLIS = 12_000L

            /** About 1 MB of UTF-16 through Binder, as the app's other copies (robustness §5). */
            const val MAX_COPY_CHARS = 400_000
        }
    }
