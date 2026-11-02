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

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
