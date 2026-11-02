package com.scorchsoft.ssmarkdown.editor

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The keyboard's verbs, through the real `InputConnection` the view hands out, applied to the document. */
@RunWith(RobolectricTestRunner::class)
class CodeInputConnectionTest {
    private val view = MarkdownCodeView(ApplicationProvider.getApplicationContext())

    private fun connect(
        text: String,
        caret: Int = text.length,
    ): InputConnection {
        view.document = TextDocument(PieceTree.of(text))
        view.setCaret(caret)
        return view.onCreateInputConnection(EditorInfo())
    }

    private val text: String get() = view.document.text.toString()

    @Test
    fun `typing a word the way Gboard does lands in the document`() {
        val ic = connect("# Title\n", caret = 8)
        ic.beginBatchEdit()
        ic.setComposingText("H", 1)
        ic.endBatchEdit()
        ic.setComposingText("He", 1)
        ic.setComposingText("Hel", 1)
        ic.setComposingText("Hello", 1)
        ic.commitText("Hello", 1)
        ic.commitText(" ", 1)
        assertEquals("# Title\nHello ", text)
        assertEquals(14, view.session.caret)
    }

    @Test
    fun `autocorrect replaces the composing word`() {
        val ic = connect("I ")
        ic.setComposingText("teh", 1)
        ic.commitText("the", 1)
        assertEquals("I the", text)
    }

    @Test
    fun `backspace from the keyboard deletes through deleteSurroundingText`() {
        val ic = connect("abc")
        ic.deleteSurroundingText(1, 0)
        assertEquals("ab", text)
    }

    @Test
    fun `code point deletion removes a whole emoji`() {
        val ic = connect("a😀")
        ic.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals("a", text)
    }

    @Test
    fun `enter and backspace arrive as key events`() {
        val ic = connect("ab", caret = 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertEquals("a\nb", text)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        assertEquals("ab", text)
        assertEquals(1, view.session.caret)
    }

    @Test
    fun `text around the cursor is capped at 32K on a large document`() {
        val big = "line of text\n".repeat(20_000)
        val ic = connect(big, caret = big.length / 2)
        assertEquals(EditingSession.MAX_IME_WINDOW, ic.getTextBeforeCursor(Int.MAX_VALUE, 0)!!.length)
        assertEquals(EditingSession.MAX_IME_WINDOW, ic.getTextAfterCursor(Int.MAX_VALUE, 0)!!.length)
        assertEquals(big.substring(big.length / 2 - 5, big.length / 2), ic.getTextBeforeCursor(5, 0).toString())
    }

    @Test
    fun `extracted text is a window around the selection, never the document`() {
        val big = "x".repeat(200_000)
        val ic = connect(big, caret = 100_000)
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)!!
        assertEquals(EditingSession.MAX_IME_WINDOW, extracted.text.length)
        assertNotEquals(0, extracted.startOffset)
        assertEquals(100_000, extracted.startOffset + extracted.selectionStart)
        val hinted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 100 }, 0)!!
        assertEquals(100, hinted.text.length)
    }

    @Test
    fun `selection set by the keyboard is honoured and replaced on commit`() {
        val ic = connect("hello world")
        ic.setSelection(0, 5)
        assertEquals("hello", ic.getSelectedText(0).toString())
        ic.commitText("HELLO", 1)
        assertEquals("HELLO world", text)
    }

    @Test
    fun `caps mode follows the sentence before the cursor`() {
        val ic = connect("Done. ")
        val caps = ic.getCursorCapsMode(android.text.TextUtils.CAP_MODE_SENTENCES)
        assertTrue(caps and android.text.TextUtils.CAP_MODE_SENTENCES != 0)
    }

    @Test
    fun `editor info asks for a multi-line editor without extract UI`() {
        view.document = TextDocument(PieceTree.of("abc"))
        val info = EditorInfo()
        view.onCreateInputConnection(info)
        assertTrue(info.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
        assertEquals(0, info.initialSelStart)
    }
}
