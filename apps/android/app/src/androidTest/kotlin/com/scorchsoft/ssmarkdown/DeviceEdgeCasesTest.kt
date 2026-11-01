package com.scorchsoft.ssmarkdown

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edge cases from docs/edge-cases.md that only a real Android can prove,
 * run on CI's API 34 emulator: the WebView renderer dying, the platform
 * asking for memory back, and a hardware keyboard.
 */
@RunWith(AndroidJUnit4::class)
class DeviceEdgeCasesTest {
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun awaitEditor() =
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.activity
                .graph()
                .editor.active != null
        }

    private fun typeIntoActive(text: String) {
        instrumentation.runOnMainSync {
            val doc =
                rule.activity
                    .graph()
                    .editor.active ?: error("nothing open")
            val end = doc.text.text.length
            doc.surface.apply(listOf(EditOp(end, "", text)), TextSelection.caret(end + text.length))
        }
    }

    private fun activeText(): String {
        var text = ""
        instrumentation.runOnMainSync {
            text =
                rule.activity
                    .graph()
                    .editor.active
                    ?.text
                    ?.text
                    ?.toString()
                    .orEmpty()
        }
        return text
    }

    private fun selectMode(mode: ViewMode) {
        instrumentation.runOnMainSync {
            rule.activity
                .graph()
                .editor
                .onAction(EditorAction.SelectMode(mode))
        }
        rule.waitForIdle()
    }

    private fun webViewIn(view: View): WebView? =
        when (view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { webViewIn(view.getChildAt(it)) }
            else -> null
        }

    private fun currentWebView(): WebView? {
        var found: WebView? = null
        instrumentation.runOnMainSync { found = webViewIn(rule.activity.previewView) }
        return found
    }

    private fun codeView(): MarkdownCodeView? {
        var found: MarkdownCodeView? = null
        instrumentation.runOnMainSync { found = codeViewIn(rule.activity.window.decorView) }
        return found
    }

    private fun codeViewIn(view: View): MarkdownCodeView? =
        when (view) {
            is MarkdownCodeView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { codeViewIn(view.getChildAt(it)) }
            else -> null
        }

    /**
     * "WebView renderer killed or crashed": `chrome://crash` kills the
     * renderer process. The app survives, the Preview comes back in a new
     * WebView, and the text is untouched.
     */
    @Test
    fun aRendererCrashRestartsThePreviewAndKeepsTheText() {
        awaitEditor()
        typeIntoActive("Survives a renderer crash\n")
        selectMode(ViewMode.Preview)
        rule.waitUntil(TIMEOUT_MILLIS) { currentWebView() != null }
        // Let the page load before killing its renderer.
        Thread.sleep(PAGE_LOAD_MILLIS)
        val crashed = currentWebView() ?: error("no WebView in Preview")
        instrumentation.runOnMainSync { crashed.loadUrl("chrome://crash") }
        rule.waitUntil(RECOVERY_TIMEOUT_MILLIS) {
            val now = currentWebView()
            now != null && now !== crashed
        }
        assertTrue(activeText().endsWith("Survives a renderer crash\n"))
        selectMode(ViewMode.Code)
        assertTrue(activeText().endsWith("Survives a renderer crash\n"))
    }

    /**
     * "Low memory / onTrimMemory": the platform's own trim signal
     * (`am send-trim-memory ... RUNNING_CRITICAL`) sheds caches, never
     * unsaved work, and the editor keeps working afterwards.
     */
    @Test
    fun aCriticalTrimKeepsUnsavedWorkAndTheEditorKeepsWorking() {
        awaitEditor()
        typeIntoActive("Unsaved before the trim\n")
        // API 34 no longer sends RUNNING_* on its own, so send the strongest level too.
        listOf("RUNNING_CRITICAL", "COMPLETE").forEach { level ->
            instrumentation.uiAutomation
                .executeShellCommand("am send-trim-memory ${rule.activity.packageName} $level")
                .close()
        }
        // The shell command returns before the app has handled the signal.
        Thread.sleep(TRIM_SETTLE_MILLIS)
        rule.waitForIdle()
        assertTrue(activeText().endsWith("Unsaved before the trim\n"))
        assertTrue(
            rule.activity
                .graph()
                .editor.active
                ?.open
                ?.isDirty == true,
        )
        typeIntoActive("and after it\n")
        assertTrue(activeText().endsWith("Unsaved before the trim\nand after it\n"))
    }

    /**
     * "Hardware keyboard or Chromebook": real key events through the
     * platform's input pipeline type into the Code view, and Ctrl+Z undoes.
     */
    @Test
    fun aHardwareKeyboardTypesAndCtrlZUndoes() {
        awaitEditor()
        selectMode(ViewMode.Code)
        rule.waitUntil(TIMEOUT_MILLIS) { codeView() != null }
        val view = codeView() ?: error("no Code view")
        instrumentation.runOnMainSync { view.requestFocus() }
        rule.waitForIdle()
        val before = activeText()
        listOf(KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_I).forEach(instrumentation::sendKeyDownUpSync)
        rule.waitForIdle()
        rule.waitUntil(TIMEOUT_MILLIS) { activeText().length == before.length + 2 }
        assertTrue(activeText().contains("hi"))
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
        val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
        // Typing may undo as one step or one per key; either way Ctrl+Z gets back to the start.
        repeat(MAX_UNDOS) {
            if (activeText() != before) {
                instrumentation.sendKeySync(down)
                instrumentation.sendKeySync(up)
                rule.waitForIdle()
            }
        }
        assertEquals(before, activeText())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val RECOVERY_TIMEOUT_MILLIS = 15_000L
        const val PAGE_LOAD_MILLIS = 2_000L
        const val TRIM_SETTLE_MILLIS = 1_000L
        const val MAX_UNDOS = 3
    }
}
