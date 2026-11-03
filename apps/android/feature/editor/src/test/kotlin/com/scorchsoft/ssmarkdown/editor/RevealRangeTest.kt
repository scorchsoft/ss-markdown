package com.scorchsoft.ssmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
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
 * [MarkdownCodeView.revealRange], which find & replace uses to show the
 * current match (edge-cases "Find next does not move"): through the real
 * view, with real text measurement.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RevealRangeTest {
    private val view =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            size(HEIGHT)
        }

    private fun MarkdownCodeView.size(height: Int) {
        measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, WIDTH, height)
    }

    private fun draw() {
        val bitmap = Bitmap.createBitmap(WIDTH, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }

    private fun shown(offset: Int): Boolean {
        val y = view.yForOffset(offset)
        return y >= 0f && y + view.rowHeight <= view.height
    }

    @Test
    fun `a range many rows down a wrapped paragraph is scrolled into view, with context`() {
        val text = "# Title\n\n" + "The quick brown fox jumps over the lazy dog. ".repeat(200) + "needle\nafter\n"
        view.setDocument(TextDocument(PieceTree.of(text)))
        view.wrap = true
        draw()
        val start = text.indexOf("needle")
        assertTrue("starts off screen", !shown(start))

        view.revealRange(start, start + 6)
        draw()

        assertTrue("needle on screen at y=${view.yForOffset(start)}", shown(start))
        assertTrue("a row of context below", view.yForOffset(start) + 2 * view.rowHeight <= view.height)
    }

    @Test
    fun `a range already in view does not move the page`() {
        view.setDocument(TextDocument(PieceTree.of("line\n".repeat(500))))
        view.scrollToLine(100)
        draw()
        val before = view.scrollY
        val offset = view.document.text.lineStartOffset(105)

        view.revealRange(offset, offset + 4)
        draw()

        assertEquals(before, view.scrollY)
    }

    @Test
    fun `a range above the pane comes in at the top, below it at the bottom`() {
        view.setDocument(TextDocument(PieceTree.of("line\n".repeat(500))))
        view.scrollToLine(200)
        draw()
        val above = view.document.text.lineStartOffset(50)
        view.revealRange(above, above + 4)
        draw()
        assertTrue(shown(above))
        assertTrue("near the top", view.yForOffset(above) < view.height / 4)

        val below = view.document.text.lineStartOffset(400)
        view.revealRange(below, below + 4)
        draw()
        assertTrue(shown(below))
        assertTrue("near the bottom", view.yForOffset(below) > view.height / 2)
    }

    @Test
    fun `a caret on screen stays on screen when the keyboard shrinks the pane`() {
        view.setDocument(TextDocument(PieceTree.of("line\n".repeat(500))))
        draw()
        // A caret near the bottom of the full-height pane.
        val offset = view.offsetAt(view.textLeft.toFloat(), HEIGHT - view.rowHeight * 1.5f)
        view.session.setSelection(offset, offset + 2)
        draw()
        assertTrue(shown(offset))

        view.size(HEIGHT / 2)
        draw()

        assertTrue("caret still on screen at y=${view.yForOffset(offset)} of ${view.height}", shown(offset))
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1200
    }
}
