package com.scorchsoft.ssmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The Code view draws, places carets on and hit-tests right-to-left text in visual order (M1). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BidiDrawingTest {
    private val hebrew = "שלום"
    private val arabic = "مرحبا"

    private fun view(
        text: String,
        wrap: Boolean = false,
    ): MarkdownCodeView =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            this.wrap = wrap
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
            document = TextDocument(PieceTree.of(text))
        }

    private fun MarkdownCodeView.drawSoftware() {
        draw(Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)))
    }

    @Test
    fun `left-to-right lines take the fast path, with no bidi layout and no bidi drawing`() {
        val view = view("# Plain\nascii, emoji 👋, 日本語 and accents é\n\tTabbed **bold** `code`")
        view.drawSoftware()
        for (line in 0 until 3) assertNull("line $line", view.geometry.layoutFor(line).bidi)
        assertEquals(0, view.renderer.bidiRowsDrawn)
        view.wrap = true
        view.drawSoftware()
        for (line in 0 until 3) assertNull("wrapped line $line", view.geometry.layoutFor(line).bidi)
        assertEquals(0, view.renderer.bidiRowsDrawn)
    }

    @Test
    fun `lines with Hebrew or Arabic are drawn reordered`() {
        val view = view("plain\nab $hebrew cd\n**$arabic** text")
        view.drawSoftware()
        assertNull(view.geometry.layoutFor(0).bidi)
        assertNotNull(view.geometry.layoutFor(1).bidi)
        assertNotNull(view.geometry.layoutFor(2).bidi)
        assertEquals(2, view.renderer.bidiRowsDrawn)
    }

    @Test
    fun `the caret is drawn where the text is and a tap there finds it again`() {
        val text = "ab $hebrew cd"
        val view = view(text)
        val layout = view.geometry.layoutFor(0)
        val bidi = checkNotNull(layout.bidi)
        // The first Hebrew letter is drawn at the right end of its word.
        assertTrue(bidi.charLeft(3) > bidi.charLeft(6))
        for (column in 0..text.length) {
            val point = view.pointInView(column)
            val back = view.offsetAtView(point.x, point.y + 1f)
            assertEquals("column $column", point.x, view.pointInView(back).x, 0.5f)
        }
    }

    @Test
    fun `wrapped mixed rows are each drawn and hit-tested in visual order`() {
        val text = List(12) { "word $hebrew $arabic" }.joinToString(" ")
        val view = view(text, wrap = true)
        view.drawSoftware()
        val layout = view.geometry.layoutFor(0)
        assertTrue("wraps", layout.rowCount > 1)
        assertEquals(layout.rowCount, view.renderer.bidiRowsDrawn)
        for (column in 0..text.length step 7) {
            val point = view.pointInView(column)
            val back = view.offsetAtView(point.x, point.y + 1f)
            assertEquals("column $column", point.x, view.pointInView(back).x, 0.5f)
        }
    }

    @Test
    fun `arrow keys follow visual order by default`() {
        val view = view("$hebrew\nnext")
        view.setCaret(0)
        val left = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
        view.onKeyDown(left.keyCode, left)
        assertEquals("Left from the word's start moves into it, as drawn", 1, view.session.caret)
    }

    @Test
    fun `a selection inside a right-to-left run is drawn without error in both modes`() {
        for (wrap in listOf(false, true)) {
            val view = view("ab $hebrew cd\n$arabic", wrap)
            view.session.setSelection(1, 5)
            view.drawSoftware()
            view.session.setSelection(4, 12)
            view.drawSoftware()
        }
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 400
    }
}
