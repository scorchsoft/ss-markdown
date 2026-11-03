package com.scorchsoft.ssmarkdown.editor

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * edge-cases "No way out of a list" (Code half): Enter in the Code view,
 * as a keyboard's committed line break or as a key, runs the same
 * `Newline` command Preview does, so a list continues and an empty item
 * ends it. Code used to type a bare line break, which left what came next
 * inside the item above.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EnterInCodeTest {
    @get:Rule val temp = TemporaryFolder()

    private fun open(text: String): Pair<ActiveDocument, MarkdownCodeView> {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("list.md", text))
        val doc = f.vm.active ?: error("nothing open")
        val view =
            MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
                document = doc.text
                listener = doc
            }
        doc.attach(view)
        return doc to view
    }

    private fun MarkdownCodeView.text() = session.document.text.toString()

    @Test
    fun `a keyboard's line break in a list starts the next item`() {
        val (_, view) = open("- one")
        view.session.setSelection(5, 5)
        view.onCreateInputConnection(EditorInfo()).commitText("\n", 1)
        assertEquals("- one\n- ", view.text())
        assertEquals(8, view.session.caret)
    }

    @Test
    fun `Enter as a key on an empty item ends the list`() {
        val (_, view) = open("- one\n- ")
        view.session.setSelection(8, 8)
        view.onCreateInputConnection(EditorInfo()).sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("- one\n\n", view.text())
    }

    @Test
    fun `Enter in a list is one undo step`() {
        val (_, view) = open("1. one")
        view.session.setSelection(6, 6)
        view.onCreateInputConnection(EditorInfo()).commitText("\n", 1)
        assertEquals("1. one\n2. ", view.text())
        view.session.undo()
        assertEquals("1. one", view.text())
    }

    @Test
    fun `a line break outside a list is a plain line break`() {
        val (_, view) = open("plain")
        view.session.setSelection(5, 5)
        view.onCreateInputConnection(EditorInfo()).commitText("\n", 1)
        assertEquals("plain\n", view.text())
        assertEquals(6, view.session.caret)
    }

    @Test
    fun `a composing word then a line break keeps the word`() {
        val (_, view) = open("- ")
        view.session.setSelection(2, 2)
        val ime = view.onCreateInputConnection(EditorInfo())
        ime.setComposingText("two", 1)
        ime.finishComposingText()
        ime.commitText("\n", 1)
        assertEquals("- two\n- ", view.text())
    }

    @Test
    fun `a keyboard committing the word and the line break together still continues the list`() {
        // Andrew's report (0.2.2): some keyboards send the composing word with its line break.
        val (_, view) = open("- one\n- ")
        view.session.setSelection(8, 8)
        val ime = view.onCreateInputConnection(EditorInfo())
        ime.setComposingText("two", 1)
        ime.commitText("two\n", 1)
        assertEquals("- one\n- two\n- ", view.text())
        assertEquals(14, view.session.caret)
    }

    @Test
    fun `Enter on item 3 of six numbered items adds item 4 inside the list`() {
        val (_, view) = open("1. a\n2. b\n3. c\n4. d\n5. e\n6. f")
        view.session.setSelection(14, 14)
        view.onCreateInputConnection(EditorInfo()).commitText("\n", 1)
        assertEquals("1. a\n2. b\n3. c\n4. \n5. d\n6. e\n7. f", view.text())
    }

    @Test
    fun `a keyboard's Backspace at an item's start takes the marker off, the next one joins`() {
        val (_, view) = open("- a\n- b")
        view.session.setSelection(6, 6)
        val ime = view.onCreateInputConnection(EditorInfo())
        ime.deleteSurroundingText(1, 0)
        assertEquals("- a\n\nb", view.text())
        assertEquals(5, view.session.caret)
        ime.deleteSurroundingText(1, 0)
        assertEquals("- a\nb", view.text())
    }

    @Test
    fun `Backspace as a key at an item's start takes the marker off`() {
        val (_, view) = open("1. a\n2. b\n3. c")
        view.session.setSelection(8, 8)
        view.onCreateInputConnection(EditorInfo()).sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals("1. a\n\nb\n\n2. c", view.text())
    }

    @Test
    fun `Shift+Tab on a top-level item makes that item a paragraph`() {
        val (_, view) = open("- a\n- b\n- c")
        view.session.setSelection(7, 7)
        view
            .onCreateInputConnection(EditorInfo())
            .sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON))
        assertEquals("- a\n\nb\n- c", view.text())
    }

    @Test
    fun `Tab nests an item, and types a tab in a paragraph`() {
        val (_, view) = open("- a\n- b\n\npara")
        view.session.setSelection(7, 7)
        val ime = view.onCreateInputConnection(EditorInfo())
        ime.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        assertEquals("- a\n  - b\n\npara", view.text())
        view.session.setSelection(view.text().length, view.text().length)
        ime.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        assertEquals("- a\n  - b\n\npara\t", view.text())
    }

    @Test
    fun `Tab indents a code line by the block's unit, and a typed brace takes it back`() {
        val (_, view) = open("```js\nf() {\n  x\n\n```")
        view.session.setSelection(16, 16)
        val ime = view.onCreateInputConnection(EditorInfo())
        ime.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        assertEquals("```js\nf() {\n  x\n  \n```", view.text())
        ime.commitText("}", 1)
        assertEquals("```js\nf() {\n  x\n}\n```", view.text())
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
