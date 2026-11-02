package com.scorchsoft.ssmarkdown

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.preview.PrintOutcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * edge-cases "Export PDF from Code did nothing, then printed the next file",
 * on a real WebView: a print being prepared ends the moment the page is given
 * a document, and the real page never answers it afterwards, so no print
 * dialog opens over whatever it shows next.
 */
@OptIn(DelicateCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PrintDeviceTest {
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aPrintPreparedBeforeALoadNeverOpensAfterIt() {
        val editor = rule.activity.graph().editor
        rule.waitUntil(TIMEOUT_MILLIS) { editor.active != null }
        instrumentation.runOnMainSync { editor.onAction(EditorAction.SelectMode(ViewMode.Preview)) }
        rule.waitUntil(TIMEOUT_MILLIS) {
            var ready = false
            instrumentation.runOnMainSync { ready = rule.activity.previewView.isOnScreen }
            ready
        }
        // Let the page load and render before asking it to print.
        Thread.sleep(PAGE_LOAD_MILLIS)

        var outcome: PrintOutcome? = null
        instrumentation.runOnMainSync {
            val first = editor.active ?: error("nothing open")
            GlobalScope.launch(Dispatchers.Main.immediate, CoroutineStart.UNDISPATCHED) {
                outcome = editor.preview.print(first, "first")
            }
            // The page is given a document before it can answer (here the same one afresh, which
            // is what any document switch sends: unbind, then bind and load). Same main-thread turn,
            // so the page cannot have answered yet.
            editor.preview.bind(null)
            editor.preview.bind(first)
        }
        rule.waitUntil(TIMEOUT_MILLIS) { outcome != null }
        assertEquals(PrintOutcome.DocumentChanged, outcome)

        // Give an abandoned preparation every chance to answer: no dialog takes the window.
        Thread.sleep(SETTLE_MILLIS)
        var focused = false
        instrumentation.runOnMainSync { focused = rule.activity.hasWindowFocus() }
        assertTrue("no print dialog opened over the next document", focused)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val PAGE_LOAD_MILLIS = 2_000L
        const val SETTLE_MILLIS = 3_000L
    }
}
