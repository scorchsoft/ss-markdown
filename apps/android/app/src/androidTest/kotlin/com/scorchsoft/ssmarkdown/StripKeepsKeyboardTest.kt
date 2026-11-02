package com.scorchsoft.ssmarkdown

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Andrew, 0.2.1 on his phone: with the keyboard up, Bold put the keyboard
 * away and brought it back, and ¶ did the same, so its menu closed before
 * a heading could be chosen (edge-cases "Strip command flashes the
 * keyboard away"). On the emulator's soft keyboard: tap B, then ¶ → Heading
 * 1, in Code and in Preview, and check the keyboard was visible in every
 * frame drawn meanwhile, and that the heading landed.
 */
@RunWith(AndroidJUnit4::class)
class StripKeepsKeyboardTest {
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun graph() = rule.activity.graph()

    private fun awaitEditor() = rule.waitUntil(TIMEOUT_MILLIS) { graph().editor.active != null }

    private fun activeText(): String {
        var text = ""
        instrumentation.runOnMainSync {
            text =
                graph()
                    .editor.active
                    ?.text
                    ?.text
                    ?.toString()
                    .orEmpty()
        }
        return text
    }

    private fun setText(text: String) {
        instrumentation.runOnMainSync {
            val doc = graph().editor.active ?: error("nothing open")
            doc.surface.apply(listOf(EditOp(0, doc.text.text.toString(), text)), TextSelection.caret(CARET))
        }
    }

    private fun selectMode(mode: ViewMode) {
        instrumentation.runOnMainSync { graph().editor.onAction(EditorAction.SelectMode(mode)) }
        rule.waitForIdle()
    }

    private fun imeVisible(): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            visible =
                ViewCompat
                    .getRootWindowInsets(
                        rule.activity.window.decorView,
                    )?.isVisible(WindowInsetsCompat.Type.ime()) ==
                true
        }
        return visible
    }

    /** Waits for the soft keyboard; an emulator image without one skips the test rather than fail it. */
    private fun awaitKeyboard() {
        val deadline = System.currentTimeMillis() + KEYBOARD_TIMEOUT_MILLIS
        while (!imeVisible() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
        assumeTrue("no soft keyboard on this device", imeVisible())
        // The strip replaces the mode bar once the app has seen the keyboard.
        rule.waitUntil(TIMEOUT_MILLIS) { runCatching { strip("Bold").assertExists() }.isSuccess }
    }

    private fun strip(label: String) = rule.onNodeWithContentDescription(label)

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

    private fun boldThenHeading1() {
        val before = activeText()
        val bold = keyboardThroughout { strip("Bold").performClick() }
        assertTrue("the keyboard went away after Bold: $bold", bold.all { it })
        rule.waitUntil(TIMEOUT_MILLIS) { activeText() != before }
        val heading =
            keyboardThroughout {
                strip("Block style").performClick()
                rule
                    .onNode(hasText("Heading 1") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                    .performClick()
            }
        assertTrue("the keyboard went away for ¶ → Heading 1: $heading", heading.all { it })
        rule.waitUntil(TIMEOUT_MILLIS) { activeText().startsWith("# ") }
        assertTrue(imeVisible())
    }

    private fun enableSoftKeyboard() {
        // The emulator has a hardware keyboard, which hides the soft one unless this is on.
        instrumentation.uiAutomation
            .executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            .close()
    }

    @Test
    fun inCodeBoldAndAHeadingKeepTheKeyboardUp() {
        enableSoftKeyboard()
        awaitEditor()
        selectMode(ViewMode.Code)
        setText("Make this a heading\n")
        rule.waitUntil(TIMEOUT_MILLIS) { codeView() != null }
        val view = codeView() ?: error("no Code view")
        instrumentation.runOnMainSync {
            view.requestFocus()
            view.context.getSystemService(InputMethodManager::class.java).showSoftInput(view, 0)
        }
        awaitKeyboard()
        boldThenHeading1()
        var focused = false
        instrumentation.runOnMainSync { focused = view.isFocused }
        assertTrue("the Code view lost focus", focused)
    }

    @Test
    fun inPreviewBoldAndAHeadingKeepTheKeyboardUp() {
        enableSoftKeyboard()
        awaitEditor()
        setText("Make this a heading\n")
        selectMode(ViewMode.Preview)
        rule.waitUntil(TIMEOUT_MILLIS) { webView() != null }
        Thread.sleep(PAGE_LOAD_MILLIS)
        tapFirstParagraph(webView() ?: error("no WebView"))
        rule.waitUntil(TIMEOUT_MILLIS) {
            graph()
                .editor.state.preview.editing
        }
        awaitKeyboard()
        boldThenHeading1()
        assertTrue(
            graph()
                .editor.state.preview.editing,
        )
    }

    /** A real tap (through the input pipeline, as a finger) on the page's first paragraph. */
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
        val origin = IntArray(2)
        var scale = 1f
        instrumentation.runOnMainSync {
            web.getLocationOnScreen(origin)
            scale = web.resources.displayMetrics.density
        }
        val x = origin[0] + (cssX * scale).toInt()
        val y = origin[1] + (cssY * scale).toInt()
        instrumentation.uiAutomation.executeShellCommand("input tap $x $y").close()
    }

    private fun codeView(): MarkdownCodeView? {
        var found: MarkdownCodeView? = null
        instrumentation.runOnMainSync { found = find(rule.activity.window.decorView, MarkdownCodeView::class.java) }
        return found
    }

    private fun webView(): WebView? {
        var found: WebView? = null
        instrumentation.runOnMainSync { found = find(rule.activity.previewView, WebView::class.java) }
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
        const val KEYBOARD_TIMEOUT_MILLIS = 10_000L
        const val PAGE_LOAD_MILLIS = 2_000L
        const val SETTLE_MILLIS = 800L
        const val POLL_MILLIS = 100L
        const val CARET = 5
    }
}
