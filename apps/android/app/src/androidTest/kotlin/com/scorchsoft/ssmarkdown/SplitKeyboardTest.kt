package com.scorchsoft.ssmarkdown

import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.editor.panes.SPLIT_PANES_TAG
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.settings.SplitTyping
import com.scorchsoft.ssmarkdown.text.EditOp
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Andrew, 0.2.2 on his phone: "If I'm in split view, I go to open the
 * keyboard on one of the views. Keyboard goes to show and then disappears
 * again." (edge-cases "Split: the keyboard showed and went away"). On the
 * emulator's soft keyboard, in portrait Split: a real tap in the Code pane,
 * and one on a paragraph in the Preview pane, must leave the keyboard up in
 * every frame after it first appears, with the tapped pane still editing.
 * Both failed before the fix.
 *
 * Then Split while typing (ux-spec "Split while typing"): by default the
 * tapped pane fills the page and the other leaves the screen until the
 * keyboard closes; with "Keep both panes" both stay. Those two run in a
 * taller window (480 x 1200 dp): on the CI emulator's own small screen
 * the keyboard leaves Split under 280 dp, too short to stack, where Preview
 * folds into its strip whatever the setting (edge-cases "Tiny split-screen
 * window").
 */
@RunWith(AndroidJUnit4::class)
class SplitKeyboardTest {
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun graph() = rule.activity.graph()

    @After
    fun reset() {
        shell("wm size reset")
        instrumentation.runOnMainSync {
            rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        graph().settings.update { it.copy(splitTyping = SplitTyping.EditedPane) }
    }

    private fun shell(command: String) {
        val out = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    /**
     * A taller window (`wm size`, the emulator's own 160 dpi kept): 480 x
     * 1200 dp, so stacked Split keeps its two panes with the keyboard up.
     * The failure says what the window became.
     */
    private fun phoneSizedWindow() {
        shell("wm size ${TALL_WIDTH_PX}x$TALL_HEIGHT_PX")
        val tall =
            runCatching {
                rule.waitUntil(RESIZE_TIMEOUT_MILLIS) { windowFacts().second >= TALL_HEIGHT_PX * 9 / 10 }
            }
        assertTrue("the window did not grow: ${windowFacts()}", tall.isSuccess)
        rule.waitForIdle()
    }

    /** The decor view's width and height in px, and the density. */
    private fun windowFacts(): Triple<Int, Int, Float> {
        var facts = Triple(0, 0, 0f)
        instrumentation.runOnMainSync {
            val decor = rule.activity.window.decorView
            facts = Triple(decor.width, decor.height, decor.resources.displayMetrics.density)
        }
        return facts
    }

    /** Both panes on screen, waited for with a bounded poll; the failure says how tall Split was. */
    private fun assertBothPanesOnScreen() {
        val both = runCatching { rule.waitUntil(TIMEOUT_MILLIS) { onScreen(codeView()) && onScreen(webView()) } }
        val area = rule.onNodeWithTag(SPLIT_PANES_TAG).fetchSemanticsNode().boundsInWindow
        val heightDp = area.height / rule.activity.resources.displayMetrics.density
        assertTrue(
            "both panes should be on screen while typing: Code ${onScreen(codeView())}, " +
                "page ${onScreen(webView())}, Split $heightDp dp high",
            both.isSuccess,
        )
    }

    @Test
    fun aTapInTheCodePaneKeepsTheKeyboardUpAndCodeFillsThePage() {
        val view = tapCode()
        assertTrue("Code is on screen", onScreen(view))
        assertFalse("the page left the screen while typing in Code", onScreen(webView()))
        closeKeyboardAndSeeBothPanes()
    }

    @Test
    fun aTapInThePreviewPaneKeepsTheKeyboardUpAndThePageFillsIt() {
        val web = tapPreview()
        assertTrue("the page is on screen", onScreen(web))
        assertFalse("Code left the screen while typing in Preview", onScreen(codeView()))
        closeKeyboardAndSeeBothPanes()
    }

    @Test
    fun keepBothPanesKeepsTheKeyboardAndBothPanesForCode() {
        graph().settings.update { it.copy(splitTyping = SplitTyping.BothPanes) }
        phoneSizedWindow()
        tapCode()
        assertBothPanesOnScreen()
    }

    @Test
    fun keepBothPanesKeepsTheKeyboardAndBothPanesForPreview() {
        graph().settings.update { it.copy(splitTyping = SplitTyping.BothPanes) }
        phoneSizedWindow()
        tapPreview()
        assertBothPanesOnScreen()
    }

    /** Opens Split, taps the Code pane and checks the keyboard stayed, with the Code view focused. */
    private fun tapCode(): MarkdownCodeView {
        openSplit()
        val view = codeView() ?: error("no Code view")
        val frames = keyboardThroughout { tapAt(view, view.width / 2f, view.height / 3f) }
        assertKeyboardStayed(frames)
        var focused = false
        instrumentation.runOnMainSync { focused = view.isFocused }
        assertTrue("the Code view lost focus", focused)
        return view
    }

    /** Opens Split, taps the page's first paragraph and checks the keyboard stayed, with the page editing. */
    private fun tapPreview(): WebView {
        openSplit()
        val web = webView() ?: error("no WebView")
        val frames = keyboardThroughout { tapFirstParagraph(web) }
        assertKeyboardStayed(frames)
        assertTrue(
            "the page stopped editing",
            graph()
                .editor.state.preview.editing,
        )
        return web
    }

    /** The keyboard closes: Split comes back with both panes on screen, still in Split. */
    private fun closeKeyboardAndSeeBothPanes() {
        instrumentation.runOnMainSync {
            val window = rule.activity.window
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        rule.waitUntil(TIMEOUT_MILLIS) { !imeVisible() }
        rule.waitUntil(TIMEOUT_MILLIS) { onScreen(codeView()) && onScreen(webView()) }
        assertEquals(ViewMode.Split, graph().editor.state.mode)
    }

    /**
     * Whether some of [view] is inside Split's pane area: a pane moved out
     * of view is still attached (that is the fix), so only its place says.
     */
    private fun onScreen(view: View?): Boolean {
        if (view == null) return false
        rule.waitForIdle()
        val area = rule.onNodeWithTag(SPLIT_PANES_TAG).fetchSemanticsNode().boundsInWindow
        var top = 0
        var bottom = 0
        instrumentation.runOnMainSync {
            val at = IntArray(2)
            view.getLocationInWindow(at)
            top = at[1]
            bottom = at[1] + view.height
        }
        return minOf(bottom.toFloat(), area.bottom) - maxOf(top.toFloat(), area.top) > 1f
    }

    /** Portrait Split on a short document, with the page loaded and the keyboard down. */
    private fun openSplit() {
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
            doc.surface.apply(listOf(EditOp(0, doc.text.text.toString(), TEXT)), TextSelection.caret(0))
            graph().editor.onAction(EditorAction.SelectMode(ViewMode.Split))
        }
        rule.waitForIdle()
        rule.waitUntil(TIMEOUT_MILLIS) { codeView() != null && webView() != null }
        Thread.sleep(PAGE_LOAD_MILLIS)
        rule.waitForIdle()
        assertFalse("the keyboard was already up", imeVisible())
    }

    /**
     * The keyboard appeared (else this image has none, and the test is
     * skipped) and was up in every frame from then on, and still is.
     */
    private fun assertKeyboardStayed(frames: List<Boolean>) {
        val first = frames.indexOf(true)
        assumeTrue("no soft keyboard on this device: $frames", first >= 0 || imeVisible())
        val after = if (first >= 0) frames.drop(first) else emptyList()
        assertTrue("the keyboard went away after it showed: $frames", after.all { it })
        assertTrue("the keyboard is not up after settling: $frames", imeVisible())
    }

    private fun imeVisible(): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            visible =
                ViewCompat
                    .getRootWindowInsets(rule.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    /** Records, for every frame drawn while [block] runs and settles, whether the keyboard was up. */
    private fun keyboardThroughout(block: () -> Unit): List<Boolean> {
        val frames = mutableListOf<Boolean>()
        val decor = rule.activity.window.decorView
        val listener =
            ViewTreeObserver.OnPreDrawListener {
                frames += ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                true
            }
        instrumentation.runOnMainSync { decor.viewTreeObserver.addOnPreDrawListener(listener) }
        block()
        // Long enough for the keyboard to slide in, and to slide out again if focus went.
        Thread.sleep(SETTLE_MILLIS)
        rule.waitForIdle()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            decor.viewTreeObserver.removeOnPreDrawListener(listener)
            done.countDown()
        }
        done.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        return frames.toList()
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

    /** A real tap on the page's first paragraph. */
    private fun tapFirstParagraph(web: WebView) {
        val rect = CountDownLatch(1)
        var box = ""
        instrumentation.runOnMainSync {
            web.evaluateJavascript(
                "(() => { const r = document.querySelector('p').getBoundingClientRect(); " +
                    "return [r.left + 20, r.top + r.height / 2].join(','); })()",
            ) {
                box = it.trim('"')
                rect.countDown()
            }
        }
        assertTrue(rect.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        val (cssX, cssY) = box.split(',').map { it.toFloat() }
        val scale = web.resources.displayMetrics.density
        tapAt(web, cssX * scale, cssY * scale)
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
        const val TIMEOUT_MILLIS = 5_000L
        const val TALL_WIDTH_PX = 480
        const val TALL_HEIGHT_PX = 1200
        const val RESIZE_TIMEOUT_MILLIS = 15_000L
        const val PAGE_LOAD_MILLIS = 2_000L
        const val SETTLE_MILLIS = 2_500L
        const val TEXT = "A first paragraph to tap in.\n\nA second one below it.\n"
    }
}
