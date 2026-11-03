package com.scorchsoft.ssmarkdown.editor

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Where a tap puts the caret in Code, through real touch events on the view (Andrew's 0.2.2
 * report was about Preview; Code is held to the same rule): a tap in a line's row lands on that
 * row, right of its end at the end, and a tap below the last line at the end of the document.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TapCaretTest {
    private val view =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }
    private var clock = SystemClock.uptimeMillis()

    private fun show(text: String) {
        view.document = TextDocument(PieceTree.of(text))
    }

    /** A real tap: down and up at ([x], [y]), far enough apart in time from the last one. */
    private fun tap(
        x: Float,
        y: Float,
    ): Int {
        clock += TAP_GAP_MS
        view.dispatchTouchEvent(MotionEvent.obtain(clock, clock, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(clock, clock + TAP_MS, MotionEvent.ACTION_UP, x, y, 0))
        return view.session.caret
    }

    private fun rowMid(offset: Int): Float = view.yForOffset(offset) + view.lineHeight / 2f

    private val farRight: Float get() = WIDTH - 4f

    @Test
    fun `right of every line's end, the last line included, puts the caret at that end`() {
        val text = "def f(x):\n    return (\n        x\n\n    )"
        show(text)
        var start = 0
        for (line in text.split("\n")) {
            val end = start + line.length
            assertEquals("right of \"$line\"", end, tap(farRight, rowMid(start)))
            // Just right of the last character, where Andrew had to aim.
            assertEquals("just right of \"$line\"", end, tap(view.xForOffset(end) + view.charWidth, rowMid(start)))
            start = end + 1
        }
    }

    @Test
    fun `left of a line's start, in the gutter, puts the caret at that start`() {
        val text = "first\n    indented\nlast"
        show(text)
        // Bottom up: the insertion handle hangs below the caret, and a touch on it drags it instead.
        for (start in listOf(19, 6, 0)) assertEquals(start, tap(view.textLeft / 2f, rowMid(start)))
    }

    @Test
    fun `an empty line takes the caret`() {
        show("above\n\nbelow")
        assertEquals(6, tap(farRight, rowMid(6)))
        assertEquals(6, tap(view.textLeft + view.charWidth * 3, rowMid(6)))
    }

    @Test
    fun `below the last line, the caret goes to the end of the document`() {
        val text = "one\ntwo\nthe last line is the longest"
        show(text)
        val below = view.yForOffset(text.length) + view.lineHeight * 2.5f
        assertEquals(text.length, tap(view.textLeft + view.charWidth * 2, below))
        assertEquals(text.length, tap(farRight, below))
    }

    @Test
    fun `right of a wrapped row's end, the caret stays at that row's end`() {
        view.wrap = true
        val text = "word ".repeat(80).trim() + "\nnext"
        show(text)
        val rowTop = view.yForOffset(0)
        val caret = tap(farRight, rowTop + view.lineHeight / 2f)
        assertEquals("on the tapped row", rowTop, view.yForOffset(caret), 0.5f)
        assertTrue("at the row's end", view.yForOffset(caret + 2) > rowTop)
        // Its last row, too, and below it on the next line's row.
        val lastRow = view.yForOffset(text.indexOf('\n'))
        assertTrue("the line wraps", lastRow > rowTop)
        assertEquals(text.indexOf('\n'), tap(farRight, lastRow + view.lineHeight / 2f))
    }

    private companion object {
        const val WIDTH = 1_080
        const val HEIGHT = 1_920
        const val TAP_GAP_MS = 1_000L
        const val TAP_MS = 50L
    }
}
