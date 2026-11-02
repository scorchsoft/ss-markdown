package com.scorchsoft.ssmarkdown.editor

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Visual caret order on mixed-direction lines. */
@RunWith(RobolectricTestRunner::class)
class BidiCaretTest {
    private val hebrew = "שלום"

    @Test
    fun `only lines with right-to-left text need bidi`() {
        assertFalse(BidiCaret.needsBidi("plain ascii, emoji 👋 and 日本語"))
        assertTrue(BidiCaret.needsBidi("a $hebrew b"))
        assertFalse(BidiCaret.needsBidi(hebrew + "x".repeat(BidiCaret.MAX_ANALYSED_CHARS)))
    }

    @Test
    fun `left-to-right text keeps logical order`() {
        assertEquals(listOf(0, 1, 2, 3), BidiCaret.visualOrder("abc"))
    }

    @Test
    fun `a right-to-left line is walked backwards on screen`() {
        // Drawn right to left: the start of the word is at the right edge.
        assertEquals(listOf(4, 3, 2, 1, 0), BidiCaret.visualOrder(hebrew))
        // From the start, Left moves on into the word, Right is the visual edge.
        assertEquals(1, BidiCaret.move(hebrew, 0, right = false))
        assertEquals(0, BidiCaret.move(hebrew, 0, right = true))
        assertEquals(3, BidiCaret.move(hebrew, 4, right = true))
    }

    @Test
    fun `a mixed line keeps each run in its own direction`() {
        val line = "ab $hebrew cd"
        val order = BidiCaret.visualOrder(line)
        assertEquals(line.length + 1, order.size)
        assertEquals(listOf(0, 1, 2), order.take(3))
        assertEquals(line.length, order.last())
        // Inside the Hebrew run, Right moves logically backwards.
        val inside = 5
        val right = BidiCaret.move(line, inside, right = true)
        assertTrue("from $inside right went to $right", right < inside)
    }

    @Test
    fun `surrogate pairs are never split`() {
        val line = "$hebrew 😀"
        assertFalse(BidiCaret.visualOrder(line).contains(line.indexOf("😀") + 1))
    }

    @Test
    fun `the view moves visually unless turned off`() {
        val view = MarkdownCodeView(ApplicationProvider.getApplicationContext())
        view.document = TextDocument(PieceTree.of("$hebrew\nnext"))
        view.setCaret(0)
        view.bidiCaretMovement = false
        val left = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
        view.onKeyDown(left.keyCode, left)
        assertEquals("logical when off: already at the start", 0, view.session.caret)

        view.bidiCaretMovement = true
        view.onKeyDown(left.keyCode, left)
        assertEquals(1, view.session.caret)
        val right = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        view.onKeyDown(right.keyCode, right)
        assertEquals(0, view.session.caret)
        view.onKeyDown(right.keyCode, right)
        assertEquals("past the visual edge, Right goes to the next line", hebrew.length + 1, view.session.caret)
    }
}
