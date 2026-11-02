package com.scorchsoft.ssmarkdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** On a real device: the launcher Activity starts straight into an editor with the two bars. */
@RunWith(AndroidJUnit4::class)
class LaunchTest {
    // Outermost, so it checks for leaks after the Activity has been closed.
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun launchesToAnEditor() {
        awaitEditor()
        rule.onNodeWithText("Code").assertIsDisplayed()
        rule.onNodeWithText("Preview").assertIsDisplayed()
    }

    /**
     * The app's Code pane colours Markdown on a device: headings bold,
     * inline markers in the muted ink. The app screenshots once showed it
     * plain because they drew a stand-in pane; this reads the runs the real
     * view draws in the real app, with the app's theme and code font.
     */
    @Test
    fun theCodePaneColoursMarkdown() {
        awaitEditor()
        instrumentation.runOnMainSync {
            val editor = rule.activity.graph().editor
            editor.onAction(EditorAction.SelectMode(ViewMode.Code))
            val doc = editor.active ?: error("nothing open")
            // Replace the whole buffer and start at the top: the launch reopens
            // whatever an earlier test left open, scrolled where it left it, so
            // prepending would leave this sample off screen (FindNextDeviceTest).
            doc.surface.apply(listOf(EditOp(0, doc.text.text.toString(), SAMPLE)), TextSelection.caret(0))
            codeViewIn(rule.activity.window.decorView)?.scrollTo(0, 0)
        }
        rule.waitForIdle()
        var runs: List<Run> = emptyList()
        instrumentation.runOnMainSync {
            val view = codeViewIn(rule.activity.window.decorView) ?: error("no Code view on screen")
            runs = RunRecorder.of(view)
        }
        val heading = runs.firstOrNull { it.text.trim() == "Release notes" }
        assertTrue("heading not drawn bold: $runs", heading?.bold == true)
        val marker = runs.firstOrNull { it.text == "**" } ?: error("no ** run drawn: $runs")
        val word = runs.firstOrNull { it.text == "calm" } ?: error("no bold word drawn: $runs")
        assertTrue("strong text not bold", word.bold)
        assertNotEquals("the ** marker is drawn in the text colour, not muted", word.color, marker.color)
    }

    private fun awaitEditor() =
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.activity
                .graph()
                .editor.active != null
        }

    private fun codeViewIn(view: View): MarkdownCodeView? =
        when (view) {
            is MarkdownCodeView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { codeViewIn(view.getChildAt(it)) }
            else -> null
        }

    /** One text run the Code view drew, with its paint. */
    private data class Run(
        val text: String,
        val color: Int,
        val bold: Boolean,
    )

    /** A software canvas that keeps every text run drawn on it: the view draws a software canvas directly. */
    private class RunRecorder(
        bitmap: Bitmap,
    ) : Canvas(bitmap) {
        val runs = ArrayList<Run>()

        override fun drawTextRun(
            text: CharArray,
            index: Int,
            count: Int,
            contextIndex: Int,
            contextCount: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint,
        ) {
            runs += Run(String(text, index, count), paint.color, paint.isFakeBoldText)
            super.drawTextRun(text, index, count, contextIndex, contextCount, x, y, isRtl, paint)
        }

        companion object {
            fun of(view: View): List<Run> =
                RunRecorder(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))
                    .also(view::draw)
                    .runs
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val SAMPLE = "# Release notes\n\nA **calm** editor.\n\n"
    }
}

/** The process's graph, as the Activity sees it. */
fun MainActivity.graph(): AppGraph = (application as SSMarkdownApplication).graph
