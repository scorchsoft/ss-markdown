package com.scorchsoft.ssmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Wrap mode, anchoring, zoom and hardware keys through the real view, with real text measurement. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewBehaviourTest {
    private val view =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }

    private val edits = mutableListOf<LineChange>()
    private var lastZoom = -1

    init {
        view.listener =
            object : EditorListener {
                override fun onTextEdited(
                    change: LineChange,
                    version: Long,
                ) {
                    edits += change
                }

                override fun onZoomChanged(
                    percent: Int,
                    pinching: Boolean,
                ) {
                    lastZoom = percent
                }
            }
    }

    private fun show(text: String) {
        view.setDocument(TextDocument(PieceTree.of(text)))
    }

    private fun draw() {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }

    private fun press(
        keyCode: Int,
        meta: Int = 0,
    ) {
        view.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        view.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    @Test
    fun `a long line wraps into rows and taps map back exactly`() {
        val sentence = "The quick brown fox jumps over the lazy dog. ".repeat(20)
        show(sentence + "\nsecond")
        view.wrap = true
        draw()
        val second = view.document.text.lineStartOffset(1)
        assertTrue("line 1 is pushed down by the wrapped rows", view.yForOffset(second) > view.lineHeight * 3)
        for (offset in listOf(0, 10, 100, 400, 899, second, second + 3)) {
            val x = view.xForOffset(offset)
            val y = view.yForOffset(offset) + view.lineHeight / 2f
            assertTrue("offset $offset is inside the view horizontally", x <= WIDTH)
            assertEquals("offset $offset", offset, view.offsetAt(x, y))
        }
    }

    @Test
    fun `switching wrap keeps the top line`() {
        show(("A line long enough to wrap at least once on a phone screen, surely. ".repeat(3) + "\n").repeat(400))
        view.scrollToLine(150)
        draw()
        assertEquals(150, view.anchorLine)
        view.wrap = true
        draw()
        assertEquals(150, view.anchorLine)
        view.wrap = false
        draw()
        assertEquals(150, view.anchorLine)
    }

    @Test
    fun `measuring lines above the viewport never moves what is on screen`() {
        // Long lines above, short ones below: the estimate learnt from the short lines is wrong for the long ones.
        show(("word ".repeat(80) + "\n").repeat(200) + "short\n".repeat(200))
        view.wrap = true
        view.scrollToLine(205)
        draw()
        val before = view.yForOffset(view.document.text.lineStartOffset(210))
        // Scrolling up a little lays out the (taller than estimated) lines above.
        repeat(5) {
            view.scrollBy(0, -view.lineHeight)
            draw()
        }
        val after = view.yForOffset(view.document.text.lineStartOffset(210))
        assertEquals(before + 5 * view.lineHeight, after, 0.5f)
    }

    @Test
    fun `zoom changes the font size and keeps the anchor line`() {
        show("line\n".repeat(1_000))
        view.scrollToLine(400)
        draw()
        val size = view.textSizePx
        view.zoomPercent = 200
        draw()
        assertEquals(size * 2, view.textSizePx, 0.01f)
        assertEquals(400, view.anchorLine)
        view.zoomBy(1.5f, focusY = 0f)
        assertEquals(300, view.zoomPercent)
        assertEquals(300, lastZoom)
        assertEquals(400, view.anchorLine)
    }

    @Test
    fun `ctrl shortcuts select, undo and redo`() {
        show("hello")
        view.setCaret(5)
        view.session.commitText(" world", 1)
        press(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
        assertEquals(0, view.session.selectionStart)
        assertEquals(11, view.session.selectionEnd)
        press(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        assertEquals("hello", view.document.text.toString())
        press(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertEquals("hello world", view.document.text.toString())
    }

    @Test
    fun `shift arrows select and typing replaces the selection`() {
        show("abcdef")
        view.setCaret(1)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON)
        assertEquals(1, view.session.selectionStart)
        assertEquals(3, view.session.selectionEnd)
        press(KeyEvent.KEYCODE_X)
        assertEquals("axdef", view.document.text.toString())
    }

    @Test
    fun `down arrow keeps its goal column across a short line`() {
        show("abcdefgh\nab\nabcdefgh")
        view.setCaret(6)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(11, view.session.caret) // End of "ab".
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(18, view.session.caret) // Back to column 6.
    }

    @Test
    fun `copy and paste go through the clipboard and paste is one undo step`() {
        show("copy me")
        view.session.setSelection(0, 4)
        press(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
        view.setCaret(7)
        press(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
        assertEquals("copy mecopy", view.document.text.toString())
        press(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        assertEquals("copy me", view.document.text.toString())
    }

    @Test
    fun `an unclaimed tab types a tab`() {
        show("")
        press(KeyEvent.KEYCODE_TAB)
        assertEquals("\t", view.document.text.toString())
    }

    @Test
    fun `edits reach the host with their lines`() {
        show("a\nb")
        edits.clear()
        view.setCaret(1)
        view.session.pressEnter()
        assertEquals(listOf(LineChange(0, 0, 1)), edits)
    }

    @Test
    fun `an outside edit flushes the keyboard's word and is one undo step`() {
        show("text")
        view.setCaret(4)
        view.session.setComposingText(" wor", 1)
        view.applyEdit(0, 0, "# ")
        assertFalse(view.session.isComposing)
        assertEquals("# text wor", view.document.text.toString())
        view.session.undo()
        assertEquals("text wor", view.document.text.toString())
    }

    @Test
    fun `scroll to line puts it at the top`() {
        show("x\n".repeat(5_000))
        view.scrollToLine(2_500)
        draw()
        assertEquals(2_500, view.anchorLine)
        assertEquals(0f, view.yForOffset(view.document.text.lineStartOffset(2_500)), 0.5f)
    }

    private companion object {
        const val WIDTH = 1_080
        const val HEIGHT = 1_920
    }
}
