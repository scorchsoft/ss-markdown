package com.scorchsoft.ssmarkdown

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.settings.Appearance
import com.scorchsoft.ssmarkdown.text.EditOp
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * "Text vanishes after scrolling back" (docs/edge-cases.md), on a real
 * hardware-accelerated renderer. Each line's text is a cached RenderNode,
 * and the platform frees a node's display list once no frame draws it.
 * Scrolled down and back, the old code replayed those emptied nodes: the
 * gutter showed its numbers and the text column showed nothing. Host tests
 * draw on a software canvas or never render a frame, so only a device sees it.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBackTextTest {
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun onEditor(action: EditorAction) {
        instrumentation.runOnMainSync {
            rule.activity
                .graph()
                .editor
                .onAction(action)
        }
        rule.waitForIdle()
    }

    @Before
    fun openALongWrappedTranscriptInDarkCode() {
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.activity
                .graph()
                .editor.active != null
        }
        onEditor(EditorAction.SetAppearance(Appearance.Dark))
        onEditor(EditorAction.SetCodeWrap(true))
        instrumentation.runOnMainSync {
            val doc =
                rule.activity
                    .graph()
                    .editor.active ?: error("nothing open")
            val old = doc.text.text.toString()
            doc.surface.apply(listOf(EditOp(0, old, TRANSCRIPT)), TextSelection.caret(0))
        }
        onEditor(EditorAction.SelectMode(ViewMode.Code))
        rule.waitUntil(TIMEOUT_MILLIS) { codeView() != null }
    }

    @After
    fun backToSystemAppearance() {
        onEditor(EditorAction.SetAppearance(Appearance.System))
    }

    @Test
    fun theTopLinesStillHaveTextAfterScrollingDownAndBack() {
        val view = codeView() ?: error("no Code view")
        assertTrue("dark theme", luminance(view.theme.background) < DARK_LUMINANCE)
        settle()
        assertTrue("text before scrolling", inkInTopLines(view) > MIN_INK_PIXELS)

        // Far enough that the top lines leave the screen for many frames, near
        // enough that they stay in the line cache (its capacity is 256 lines).
        repeat(SCREENS_DOWN) { screen ->
            instrumentation.runOnMainSync { view.scrollTo(0, view.height * (screen + 1)) }
            settle()
        }
        instrumentation.runOnMainSync { view.scrollTo(0, 0) }
        settle()

        val ink = inkInTopLines(view)
        assertTrue("the first lines' text is drawn after scrolling back ($ink ink pixels)", ink > MIN_INK_PIXELS)
        assertFalse("no handle without a tap or a selection", view.isSelectionHandleShown)
    }

    @Test
    fun aTapThatStopsAFlingLeavesTheCaretAndShowsNoHandle() {
        val view = codeView() ?: error("no Code view")
        repeat(SCREENS_DOWN) { screen ->
            instrumentation.runOnMainSync { view.scrollTo(0, view.height * (screen + 1)) }
            settle()
        }
        val caret = caretOf(view)
        instrumentation.runOnMainSync { view.fling(-FLING_VELOCITY) }
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
        val x = location[0] + view.textLeft + view.width / 3f
        val y = location[1] + view.height / 2f
        val time = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time + TAP_MILLIS, MotionEvent.ACTION_UP, x, y, 0))
        rule.waitForIdle()

        assertEquals("the tap only stopped the fling", caret, caretOf(view))
        assertFalse("and raised no handle", view.isSelectionHandleShown)
        assertTrue("it stopped part way", view.scrollY > 0)
    }

    private fun caretOf(view: MarkdownCodeView): Int {
        var caret = -1
        instrumentation.runOnMainSync { caret = view.session.caret }
        return caret
    }

    /** Lets the frames for the current scroll be drawn and handed to the render thread. */
    private fun settle() {
        rule.waitForIdle()
        Thread.sleep(FRAME_SETTLE_MILLIS)
    }

    /**
     * Pixels in the text column of the first three rows that are clearly
     * not the page: glyphs. The first few dp are skipped so a caret at the
     * start of line 1 never counts as text.
     */
    private fun inkInTopLines(view: MarkdownCodeView): Int {
        val location = IntArray(2)
        var page = 0
        instrumentation.runOnMainSync {
            view.getLocationOnScreen(location)
            page = view.theme.background
        }
        val shot =
            instrumentation.uiAutomation.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("no screenshot")
        val density = view.resources.displayMetrics.density
        val left = location[0] + view.textLeft + (CARET_SKIP_DP * density).toInt()
        val right = minOf(location[0] + view.width, shot.width)
        val top = location[1]
        val bottom = minOf(location[1] + view.rowHeight * TOP_ROWS, shot.height)
        var ink = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if (distance(shot.getPixel(x, y), page) > INK_DISTANCE) ink++
            }
        }
        shot.recycle()
        return ink
    }

    private fun distance(
        a: Int,
        b: Int,
    ): Int =
        maxOf(
            abs(Color.red(a) - Color.red(b)),
            abs(Color.green(a) - Color.green(b)),
            abs(Color.blue(a) - Color.blue(b)),
        )

    private fun luminance(color: Int): Float = Color.luminance(color)

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

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val FRAME_SETTLE_MILLIS = 150L
        const val SCREENS_DOWN = 8
        const val TOP_ROWS = 3
        const val CARET_SKIP_DP = 8f
        const val INK_DISTANCE = 60
        const val MIN_INK_PIXELS = 200
        const val DARK_LUMINANCE = 0.2f
        const val FLING_VELOCITY = 4_000
        const val TAP_MILLIS = 40L

        /** Shaped like the transcript that showed the bug: a heading, then long wrapped paragraphs. */
        val TRANSCRIPT: String =
            buildString {
                append("# Problem First — transcript\n\n")
                append("*Scorchsoft internal training: how we position, price and package what we sell.*\n\n")
                for (n in 1..PARAGRAPHS) {
                    append("**$n:00** ")
                    append("Every offer has a job, and the job is the problem the client already feels. ".repeat(4))
                    append("\n\n")
                }
            }
        const val PARAGRAPHS = 120
    }
}
