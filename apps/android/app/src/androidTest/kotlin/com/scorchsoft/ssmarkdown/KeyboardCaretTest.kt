package com.scorchsoft.ssmarkdown

import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Andrew: "In Preview, when I tap a line near the bottom of the screen, the
 * keyboard opens over it and I have to scroll to see it. Code view is fine."
 * (docs/edge-cases.md, "Preview: the keyboard opened over the tapped line";
 * ux-spec "Keyboard"). A real tap near the bottom of a long document, on the
 * CI emulator's soft keyboard: once the keyboard is up, the caret must be
 * above it and above the formatting strip, in Preview (read from the page)
 * and in Code (the reference, which already did this). Every wait is on a
 * condition, bounded; the failure says where the caret and keyboard were.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardCaretTest {
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun graph() = rule.activity.graph()

    @After
    fun reset() {
        instrumentation.runOnMainSync {
            rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @Test
    fun previewKeepsTheTappedLineAboveTheKeyboard() {
        open(ViewMode.Preview)
        rule.waitUntil(TIMEOUT_MILLIS) { webView() != null }
        val web = webView() ?: error("no WebView")
        rule.waitUntil(TIMEOUT_MILLIS) { js(web, "document.querySelectorAll('section.sec.mounted p').length") != "0" }
        // The lowest paragraph line fully on screen, in the page's CSS px.
        val point =
            js(
                web,
                "(() => { let best = null; for (const p of document.querySelectorAll('p')) {" +
                    " for (const r of p.getClientRects()) { if (r.bottom <= window.innerHeight - 4 && r.top > 0 &&" +
                    " (!best || r.top > best[1])) best = [r.left + 20, r.top + r.height / 2]; } }" +
                    " return best ? best.join(',') : ''; })()",
            )
        assertTrue("no paragraph on screen", point.isNotEmpty())
        val (cssX, cssY) = point.split(',').map { it.toFloat() }
        val scale = web.resources.displayMetrics.density
        tapAt(web, cssX * scale, cssY * scale)
        awaitKeyboard()
        rule.waitUntil(TIMEOUT_MILLIS) {
            graph()
                .editor.state.preview.editing
        }
        // The caret's line, from the page: its bottom in CSS px and the page's own height.
        var facts = ""
        val above =
            runCatching {
                rule.waitUntil(TIMEOUT_MILLIS) {
                    val caret = js(web, CARET_JS)
                    val imeTop = imeTopInWindow()
                    val webTop = locationInWindow(web)
                    val webBottom = webTop + web.height
                    facts = "caret $caret (CSS px), page top $webTop, page bottom $webBottom, keyboard top $imeTop (px)"
                    if (caret.isEmpty() || webBottom > imeTop) return@waitUntil false
                    val (bottom, viewport) = caret.split(',').map { it.toFloat() }
                    bottom <= viewport && webTop + bottom * scale <= imeTop
                }
            }
        assertTrue("the caret is under the keyboard or the strip: $facts", above.isSuccess)
    }

    @Test
    fun codeKeepsTheTappedLineAboveTheKeyboard() {
        open(ViewMode.Code)
        rule.waitUntil(TIMEOUT_MILLIS) { codeView() != null }
        val view = codeView() ?: error("no Code view")
        var rowHeight = 0
        var height = 0
        instrumentation.runOnMainSync {
            rowHeight = view.rowHeight
            height = view.height
        }
        tapAt(view, view.width / 2f, height - rowHeight * 1.5f)
        awaitKeyboard()
        var facts = ""
        val above =
            runCatching {
                rule.waitUntil(TIMEOUT_MILLIS) {
                    var caretBottom = 0f
                    var viewHeight = 0
                    instrumentation.runOnMainSync {
                        caretBottom = view.yForOffset(view.session.caret) + view.rowHeight
                        viewHeight = view.height
                    }
                    val top = locationInWindow(view)
                    val imeTop = imeTopInWindow()
                    facts = "caret bottom $caretBottom of $viewHeight, view top $top, keyboard top $imeTop (px)"
                    top + viewHeight <= imeTop && caretBottom <= viewHeight && top + caretBottom <= imeTop
                }
            }
        assertTrue("the Code caret is under the keyboard or the strip: $facts", above.isSuccess)
    }

    /** Portrait, a long document, [mode], keyboard down. */
    private fun open(mode: ViewMode) {
        // The emulator has a hardware keyboard, which hides the soft one unless this is on.
        instrumentation.uiAutomation
            .executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            .close()
        instrumentation.runOnMainSync {
            rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        rule.waitUntil(TIMEOUT_MILLIS) { graph().editor.active != null }
        instrumentation.runOnMainSync {
            val doc = graph().editor.active ?: error("nothing open")
            doc.surface.apply(listOf(EditOp(0, doc.text.text.toString(), LONG)), TextSelection.caret(0))
            graph().editor.onAction(EditorAction.SelectMode(mode))
        }
        rule.waitForIdle()
    }

    /** The keyboard came up (else this image has none, and the test is skipped). */
    private fun awaitKeyboard() {
        val up = runCatching { rule.waitUntil(TIMEOUT_MILLIS) { imeTopInWindow() < decorHeight() } }
        assumeTrue("no soft keyboard on this device", up.isSuccess)
    }

    private fun decorHeight(): Int {
        var h = 0
        instrumentation.runOnMainSync { h = rule.activity.window.decorView.height }
        return h
    }

    /** The keyboard's top edge in window px: the window's height when it is down. */
    private fun imeTopInWindow(): Int {
        var top = 0
        instrumentation.runOnMainSync {
            val decor = rule.activity.window.decorView
            val ime =
                ViewCompat
                    .getRootWindowInsets(decor)
                    ?.getInsets(WindowInsetsCompat.Type.ime())
                    ?.bottom ?: 0
            top = decor.height - ime
        }
        return top
    }

    private fun locationInWindow(view: View): Int {
        val at = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationInWindow(at) }
        return at[1]
    }

    /** Runs [script] in the page and returns its result as text, without JSON quotes. */
    private fun js(
        web: WebView,
        script: String,
    ): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            web.evaluateJavascript(script) {
                result = it.trim('"')
                done.countDown()
            }
        }
        assertTrue("the page did not answer", done.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        return result
    }

    /** A real tap (through the input pipeline, as a finger) at ([x], [y]) inside [view]. */
    private fun tapAt(
        view: View,
        x: Float,
        y: Float,
    ) {
        val origin = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(origin) }
        instrumentation.uiAutomation
            .executeShellCommand("input tap ${origin[0] + x.toInt()} ${origin[1] + y.toInt()}")
            .close()
    }

    private fun codeView(): MarkdownCodeView? {
        var found: MarkdownCodeView? = null
        instrumentation.runOnMainSync { found = find(rule.activity.window.decorView, MarkdownCodeView::class.java) }
        return found
    }

    private fun webView(): WebView? {
        var found: WebView? = null
        instrumentation.runOnMainSync {
            val preview = rule.activity.previewView
            if (preview.isAttachedToWindow) found = find(preview, WebView::class.java)
        }
        return found
    }

    private fun <T : View> find(
        view: View,
        type: Class<T>,
    ): T? =
        when {
            type.isInstance(view) -> type.cast(view)
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it), type) }
            else -> null
        }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L

        /** The caret's line bottom and the page's height (CSS px), if the caret is in the block being edited. */
        const val CARET_JS =
            "(() => { const s = window.getSelection(); const n = s && s.focusNode;" +
                " const el = n && (n.nodeType === 1 ? n : n.parentElement);" +
                " if (!el || !el.closest('.editing')) return '';" +
                " const r = document.createRange(); r.setStart(n, s.focusOffset); r.collapse(true);" +
                " const box = r.getClientRects()[0] || el.getBoundingClientRect();" +
                " return [box.bottom, window.innerHeight].join(','); })()"

        val LONG =
            (1..60).joinToString("\n\n") {
                "Paragraph $it is long enough to wrap onto a second line on a phone, here."
            }
    }
}
