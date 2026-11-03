package com.scorchsoft.ssmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Settings → Show line numbers, through the real view: off, the gutter is
 * 0 px and the text column starts the usual small inset from the edge;
 * caret, tap and wrapping follow; switching with a document open keeps the
 * caret's row (or the top line) where it was on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineNumbersToggleTest {
    private val view =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }

    private fun show(text: String) {
        view.document = TextDocument(PieceTree.of(text))
    }

    private fun draw() {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }

    @Test
    fun `line numbers are on by default, and off the gutter is 0 wide with the text inset kept`() {
        show("one\ntwo")
        assertTrue(view.showLineNumbers)
        val on = view.textLeft
        assertTrue(view.frame.gutterWidth > 0)
        view.showLineNumbers = false
        assertEquals(0, view.frame.gutterWidth)
        assertEquals(view.frame.gap, view.textLeft)
        assertTrue("the gap is a real inset", view.textLeft > 0)
        assertTrue("the text column widens", view.textLeft < on)
        assertEquals(WIDTH - 2 * view.frame.gap, view.frame.textColumnWidth())
        draw()
        view.showLineNumbers = true
        assertEquals(on, view.textLeft)
    }

    @Test
    fun `a line count that needs more digits leaves the gutter at 0 when off`() {
        view.showLineNumbers = false
        show((1..12_000).joinToString("\n") { "l$it" })
        assertEquals(0, view.frame.gutterWidth)
        view.applyEdit(0, 0, "x\n")
        assertEquals(0, view.frame.gutterWidth)
    }

    @Test
    fun `tap mapping and caret x are exact with the gutter on and off`() {
        val text = "first line\nsecond line with more text\n\nfourth"
        show(text)
        for (numbers in listOf(true, false, true)) {
            view.showLineNumbers = numbers
            assertEquals(view.textLeft + 3 * view.charWidth, view.xForOffset(3), 0.5f)
            for (offset in listOf(0, 5, 10, 11, 20, 37, 38, 39, 44)) {
                val x = view.xForOffset(offset)
                val y = view.yForOffset(offset) + view.lineHeight / 2f
                assertEquals("numbers=$numbers offset $offset", offset, view.offsetAt(x, y))
            }
            // A tap left of the text column (in the gutter, or in the inset) lands at the row's start.
            assertEquals(11, view.offsetAt(1f, view.yForOffset(11) + 1f))
        }
    }

    @Test
    fun `right-to-left text keeps its positions relative to the text column`() {
        show("abc שלום def")
        val on = (0..12).map { view.xForOffset(it) - view.textLeft }
        view.showLineNumbers = false
        val off = (0..12).map { view.xForOffset(it) - view.textLeft }
        on.zip(off).forEachIndexed { i, (a, b) -> assertEquals("offset $i", a, b, 0.01f) }
        for (offset in 0..12) {
            val x = view.xForOffset(offset)
            assertTrue("x at $offset inside the text column", x >= view.textLeft)
        }
        draw()
    }

    @Test
    fun `wrapping recomputes when line numbers toggle`() {
        view.wrap = true
        show("word ".repeat(400))
        val rowsOn = view.geometry.layoutFor(0).rowCount
        view.showLineNumbers = false
        assertEquals(view.frame.textColumnWidth(), view.geometry.textWidth)
        val rowsOff = view.geometry.layoutFor(0).rowCount
        assertTrue("a wider column needs fewer rows: on=$rowsOn off=$rowsOff", rowsOff < rowsOn)
        view.showLineNumbers = true
        assertEquals(rowsOn, view.geometry.layoutFor(0).rowCount)
        draw()
    }

    @Test
    fun `the caret mid-document keeps its place on screen through a toggle, with wrapped lines`() {
        view.wrap = true
        show((1..300).joinToString("\n") { "paragraph $it " + "word ".repeat(it % 40) })
        val caret =
            view.session.document.text
                .lineStartOffset(150) + 60
        view.scrollToLine(148)
        view.setCaret(caret)
        draw()
        val before = view.yForOffset(caret)
        assertTrue("the caret starts on screen at $before", before >= 0f && before < HEIGHT)
        for (numbers in listOf(false, true, false)) {
            view.showLineNumbers = numbers
            draw()
            val after = view.yForOffset(caret)
            assertTrue("numbers=$numbers: caret y $before -> $after", abs(after - before) <= 0.5f)
            val x = view.xForOffset(caret)
            assertEquals(caret, view.offsetAt(x, after + view.lineHeight / 2f))
        }
        assertEquals("the caret itself never moves", caret, view.session.caret)
    }

    @Test
    fun `with the caret off screen the top line keeps its place`() {
        view.wrap = true
        show((1..300).joinToString("\n") { "paragraph $it " + "word ".repeat(it % 40) })
        view.setCaret(0)
        view.scrollToLine(200)
        draw()
        assertEquals(200, view.anchorLine)
        val top =
            view.yForOffset(
                view.session.document.text
                    .lineStartOffset(200),
            )
        view.showLineNumbers = false
        draw()
        assertEquals(200, view.anchorLine)
        assertEquals(
            top,
            view.yForOffset(
                view.session.document.text
                    .lineStartOffset(200),
            ),
            0.5f,
        )
        assertFalse("the caret was not dragged into view", view.yForOffset(0) >= 0f)
    }

    @Test
    fun `find highlights and handles follow the text column`() {
        show("alpha beta gamma")
        view.setFindMatches(intArrayOf(6), intArrayOf(10), 0)
        view.session.setSelection(6, 10)
        val startOn = view.xForOffset(6) - view.textLeft
        view.showLineNumbers = false
        assertEquals(startOn, view.xForOffset(6) - view.textLeft, 0.01f)
        val handle = view.handlePoint(HandleKind.START)
        if (handle != null) assertEquals(view.xForOffset(6), handle.x, 0.5f)
        assertNotEquals(0, view.textLeft)
        draw()
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
    }
}
