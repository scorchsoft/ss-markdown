package com.scorchsoft.ssmarkdown.editor

import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/** A paste too big for one frame: sliced, off the main thread, one undo step, typing held until it ends. */
@RunWith(RobolectricTestRunner::class)
class ChunkedPasteTest {
    private val view = MarkdownCodeView(ApplicationProvider.getApplicationContext())
    private val frames = ArrayDeque<Runnable>()
    private val progress = mutableListOf<PasteProgress>()
    private var preparedOn: Thread? = null
    private var admits = true
    private val asked = mutableListOf<Int>()

    init {
        view.paste.background =
            Executor {
                preparedOn = Thread.currentThread()
                it.run()
            }
        view.paste.main = Executor { frames.addLast(it) }
        view.listener =
            object : EditorListener {
                override fun onPasteProgress(progress: PasteProgress) {
                    this@ChunkedPasteTest.progress += progress
                }

                override fun admitPaste(chars: Int): Boolean {
                    asked += chars
                    return admits
                }
            }
    }

    private val text: String get() = view.document.text.toString()

    /** Runs queued frames until the paste ends; returns how many ran. */
    private fun pumpFrames(): Int {
        var count = 0
        while (frames.isNotEmpty()) {
            frames.removeFirst().run()
            count++
        }
        return count
    }

    @Test
    fun `a large paste goes in by slices, reports progress, and undoes in one step`() {
        view.document = TextDocument(PieceTree.of("head\ntail"))
        view.setCaret(5)
        val pasted = "line of pasted text\r\n".repeat(40_000)
        view.pasteText(pasted)

        assertTrue(view.isPasting)
        val slices = pumpFrames()
        assertFalse(view.isPasting)
        val expected = "head\n" + pasted.replace("\r\n", "\n") + "tail"
        assertEquals(expected, text)
        assertTrue("$slices slices", slices >= expected.length / ChunkedPaste.CHUNK_CHARS)
        assertTrue(progress.last().done)
        assertEquals(progress.last().total, progress.last().inserted)
        assertEquals(progress.map { it.inserted }, progress.map { it.inserted }.sorted())
        assertEquals(expected.length - "head\ntail".length + 5, view.session.caret)

        assertTrue(view.session.undo())
        assertEquals("head\ntail", text)
        assertFalse(view.session.undo())
    }

    @Test
    fun `typing during a paste lands after it, as its own undo step`() {
        view.document = TextDocument(PieceTree.of("start "))
        view.setCaret(6)
        val ic = view.onCreateInputConnection(EditorInfo())
        view.pasteText("x".repeat(ChunkedPaste.CHUNK_CHARS * 3))
        frames.removeFirst().run()
        ic.commitText("!", 1)
        assertFalse("held, not typed mid-paste", text.endsWith("!"))
        pumpFrames()
        assertTrue(text.endsWith("x!"))
        assertEquals("start ".length + ChunkedPaste.CHUNK_CHARS * 3 + 1, text.length)

        view.session.undo()
        assertTrue(text.endsWith("x"))
        view.session.undo()
        assertEquals("start ", text)
    }

    @Test
    fun `a paste replaces the selection and never splits a surrogate pair`() {
        view.document = TextDocument(PieceTree.of("keep REPLACE keep"))
        view.session.setSelection(5, 12)
        val emoji = "😀".repeat(ChunkedPaste.CHUNK_CHARS)
        view.pasteText(emoji)
        pumpFrames()
        assertEquals("keep $emoji keep", text)
    }

    @Test
    fun `short pastes apply at once and replacing the document cancels a long one`() {
        view.document = TextDocument(PieceTree.of("ab"))
        view.setCaret(1)
        view.pasteText("-")
        assertEquals("a-b", text)
        assertTrue(frames.isEmpty())

        view.pasteText("y".repeat(ChunkedPaste.CHUNK_CHARS * 2))
        frames.removeFirst().run()
        view.document = TextDocument(PieceTree.of("fresh"))
        pumpFrames()
        assertFalse(view.isPasting)
        assertEquals("fresh", text)
        assertTrue(progress.last().done)
    }

    @Test
    fun `line breaks are normalised before the main thread sees them`() {
        assertEquals("a\nb\nc", ChunkedPaste.normalise("a\r\nb\rc"))
        view.document = TextDocument(PieceTree.of(""))
        view.pasteText("z".repeat(ChunkedPaste.THRESHOLD_CHARS + 1))
        assertEquals(Thread.currentThread(), preparedOn)
        pumpFrames()
    }

    @Test
    fun `a paste the host will not admit changes nothing and releases typing (PERF-G)`() {
        view.document = TextDocument(PieceTree.of("head\ntail"))
        view.setCaret(5)
        admits = false
        val pasted = "x".repeat(ChunkedPaste.THRESHOLD_CHARS + 1)
        view.pasteText(pasted)
        pumpFrames()
        // Asked once, up front, with the clip's length; refused, so nothing was prepared or inserted.
        assertEquals(listOf(pasted.length), asked)
        assertEquals("head\ntail", text)
        assertFalse(view.isPasting)
        assertEquals(null, preparedOn)
    }

    @Test
    fun `a clip of unknown length is admitted once it has become text (PERF-G)`() {
        view.document = TextDocument(PieceTree.of("head\ntail"))
        view.setCaret(5)
        admits = false
        val pasted = "y".repeat(ChunkedPaste.THRESHOLD_CHARS + 1)
        view.pasteText(-1) { pasted }
        pumpFrames()
        assertEquals(listOf(pasted.length), asked)
        assertEquals("head\ntail", text)
        assertFalse(view.isPasting)
        assertTrue("the end was reported", progress.last().done)
    }
}
