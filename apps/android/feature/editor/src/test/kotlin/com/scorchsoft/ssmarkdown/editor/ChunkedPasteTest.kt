package com.scorchsoft.ssmarkdown.editor

import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.EditOp
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

    /**
     * edge-cases "Undo or a command during a large paste": the strip's Undo
     * and the text toolbar are not held by the paste's gate, and Undo
     * inside its open edit group threw ("Cannot undo inside an open edit
     * group"). They now finish the paste first.
     */
    @Test
    fun `undo from the toolbar mid-paste finishes the paste, then undoes it whole`() {
        view.document = TextDocument(PieceTree.of("head\ntail"))
        view.setCaret(5)
        val pasted = "z".repeat(ChunkedPaste.CHUNK_CHARS * 3)
        view.pasteText(pasted)
        frames.removeFirst().run()
        assertTrue(view.isPasting)

        assertTrue(view.commands.run(EditorCommand.UNDO))
        assertFalse(view.isPasting)
        assertEquals("head\ntail", text)
        pumpFrames()
        assertEquals("head\ntail", text)
        assertTrue(view.session.redo())
        assertEquals("head\n" + pasted + "tail", text)
    }

    /**
     * A host edit (a Preview checkbox in Split, a strip command) whose ops
     * were taken from the half-pasted text: the paste goes in first and the
     * ops follow their text past it; one the paste landed inside is refused.
     */
    @Test
    fun `a host edit mid-paste lands where its text went`() {
        view.document = TextDocument(PieceTree.of("head\ntail"))
        view.setCaret(5)
        val pasted = "v".repeat(ChunkedPaste.CHUNK_CHARS * 3)
        view.pasteText(pasted)
        frames.removeFirst().run()
        val tail = text.indexOf("tail")
        view.applyEdits(listOf(EditOp(tail + 4, "", "!"), EditOp(0, "h", "H")), 0, 0)
        assertFalse(view.isPasting)
        assertEquals("Head\n" + pasted + "tail!", text)

        // The first paste's next slice is still queued; it finds its paste over and does nothing.
        pumpFrames()
        view.document = TextDocument(PieceTree.of("head\ntail"))
        view.setCaret(5)
        view.pasteText(pasted)
        frames.removeFirst().run()
        val half = text
        val at = half.indexOf("tail")
        view.applyEdits(listOf(EditOp(at - 1, half.substring(at - 1, at + 1), "")), 0, 0)
        assertEquals("head\n" + pasted + "tail", text)
    }

    @Test
    fun `a host flush puts the rest of a paste in at once`() {
        view.document = TextDocument(PieceTree.of("head\ntail"))
        view.setCaret(5)
        val pasted = "w".repeat(ChunkedPaste.CHUNK_CHARS * 3)
        view.pasteText(pasted)
        frames.removeFirst().run()
        view.requestFlushComposing()
        assertFalse(view.isPasting)
        assertEquals("head\n" + pasted + "tail", text)
        assertTrue(progress.last().done)
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

    /**
     * edge-cases "Typing held during a paste": a host edit that did not
     * finish the paste first (Find's Replace) finished it itself, and the
     * typing the paste held went in with it; the op followed the paste but
     * not that typing, so it landed as many chars early as were typed.
     */
    @Test
    fun `a host edit mid-paste follows the typing the paste held`() {
        view.document = TextDocument(PieceTree.of("head\ntail"))
        view.setCaret(5)
        val ic = view.onCreateInputConnection(EditorInfo())
        val pasted = "p".repeat(ChunkedPaste.CHUNK_CHARS * 3)
        view.pasteText(pasted)
        frames.removeFirst().run()
        ic.commitText("abc", 1)
        val at = text.indexOf("tail")
        view.applyEdits(listOf(EditOp(at, "tail", "TAIL")), at + 4, at + 4)
        assertEquals("head\n" + pasted + "abcTAIL", text)
    }

    @Test
    fun `an op whose deleted text is no longer at its offset is refused`() {
        view.document = TextDocument(PieceTree.of("head\ntail"))
        view.applyEdits(listOf(EditOp(0, "nope", "X")), 0, 0)
        assertEquals("head\ntail", text)
        view.applyEdits(listOf(EditOp(5, "tail", "T"), EditOp(0, "head", "H")), 0, 0)
        assertEquals("H\nT", text)
    }

    /** edge-cases "Undo during a paste with typing held": Undo removed the typing, and the paste stayed. */
    @Test
    fun `undo mid-paste undoes the paste, not the typing it held`() {
        view.document = TextDocument(PieceTree.of("head\ntail"))
        view.setCaret(5)
        val ic = view.onCreateInputConnection(EditorInfo())
        view.pasteText("q".repeat(ChunkedPaste.CHUNK_CHARS * 3))
        frames.removeFirst().run()
        ic.commitText("abc", 1)

        assertTrue(view.commands.run(EditorCommand.UNDO))
        assertEquals("head\nabctail", text)
        pumpFrames()
        assertEquals("head\nabctail", text)
    }

    /**
     * edge-cases "An edit while a paste's clip becomes text": the selection
     * to paste over was kept as offsets, and a host edit before the first
     * slice moved the text under them, so the paste deleted the wrong chars.
     */
    @Test
    fun `a host edit before the first slice moves the range the paste replaces`() {
        val prepare = ArrayDeque<Runnable>()
        view.paste.background = Executor { prepare.addLast(it) }
        view.document = TextDocument(PieceTree.of("keep REPLACE keep"))
        view.session.setSelection(5, 12)
        val pasted = "n".repeat(ChunkedPaste.CHUNK_CHARS * 2)
        view.pasteText(pasted)
        assertTrue(view.isPasting)

        view.applyEdits(listOf(EditOp(0, "", "XX")), 0, 0)
        prepare.removeFirst().run()
        pumpFrames()
        assertEquals("XXkeep $pasted keep", text)
    }
}
