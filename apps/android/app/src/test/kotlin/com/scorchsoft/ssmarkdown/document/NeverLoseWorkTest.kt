package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorBanner
import com.scorchsoft.ssmarkdown.editor.LineChange
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.editor.PendingLeave
import com.scorchsoft.ssmarkdown.files.document.CloseOutcome
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.fixtures.InterleavingDispatcher
import com.scorchsoft.ssmarkdown.history.versions.HistoryPolicy
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction
import com.scorchsoft.ssmarkdown.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The editor's never-lose-work paths end to end, with keystrokes arriving
 * the way `MarkdownCodeView` delivers them: applied to the buffer first,
 * reported after, from the main thread, while the store may be suspended
 * on disk (data-safety review DS-1, DS-2, DS-9, DS-10, DS-11, DS-16; CC-12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NeverLoseWorkTest {
    @get:Rule val temp = TemporaryFolder()

    /** What MarkdownCodeView does: edit the buffer itself, then tell the listener which lines changed. */
    private fun ActiveDocument.typeLikeTheCodeView(
        at: Int,
        typed: String,
    ) {
        val line = text.text.positionAt(at).line
        text.replace(at, 0, typed)
        onTextEdited(LineChange(line, 0, typed.count { it == '\n' }), text.version)
    }

    private fun ActiveDocument.typeAtEnd(typed: String) = typeLikeTheCodeView(text.text.length, typed)

    /** A process restart over the same directories: what does [uri] open as? */
    private fun reopened(uri: String): String {
        val after = EditorFixture(temp.root, UnconfinedTestDispatcher())
        after.vm.openDocument(uri)
        return after.vm.active
            ?.text
            ?.text
            ?.toString()
            .orEmpty()
    }

    @Test
    fun `DS-1 DS-2 keystrokes from the Code view during and after a save all survive a crash`() {
        val scheduler = TestCoroutineScheduler()
        val io = InterleavingDispatcher()
        val f = EditorFixture(temp.root, StandardTestDispatcher(scheduler), io = io)
        val uri = f.file("a.md", "one\n")
        f.vm.openDocument(uri)
        scheduler.advanceUntilIdle()
        val doc = f.vm.active ?: error("nothing open")
        doc.typeAtEnd("two ")

        io.afterEveryHop = { doc.typeAtEnd("+") }
        f.vm.onAction(EditorAction.SaveTapped)
        scheduler.advanceUntilIdle()
        io.afterEveryHop = {}
        doc.typeAtEnd("after")
        f.vm.onPause()

        val live = doc.text.text.toString()
        assertTrue(live, live.startsWith("one\ntwo +") && live.endsWith("after"))
        assertEquals(live, reopened(uri))
    }

    @Test
    fun `DS-1 the first keystroke after a quiet save is journalled on the new base`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val uri = f.file("a.md", "one\n")
        f.vm.openDocument(uri)
        val doc = f.vm.active ?: error("nothing open")
        doc.typeAtEnd("two\n")
        f.vm.onAction(EditorAction.SaveTapped)
        assertEquals("one\ntwo\n", f.disk("a.md"))

        doc.typeAtEnd("three\n")
        f.vm.onPause()

        assertEquals("one\ntwo\nthree\n", reopened(uri))
    }

    @Test
    fun `DS-11 a keystroke on a long line journals the keystroke, not the line`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val uri = f.file("long.md", "x".repeat(LONG_LINE) + "\n")
        f.vm.openDocument(uri)
        val doc = f.vm.active ?: error("nothing open")
        doc.typeLikeTheCodeView(LONG_LINE / 2, "k")
        f.vm.onPause()
        val journal = File(temp.root, "journal").listFiles { file -> file.isFile }?.single() ?: error("no journal")
        val first = journal.length()

        repeat(KEYS) { doc.typeLikeTheCodeView(LONG_LINE / 2 + 1 + it, "k") }
        f.vm.onPause()

        val perKey = (journal.length() - first) / KEYS
        assertTrue("bytes per keystroke: $perKey", perKey < MAX_BYTES_PER_KEY)
        assertEquals(doc.text.text.toString(), reopened(uri))
    }

    @Test
    fun `DS-10 coming back after Don't save and Exit never shows the discarded buffer`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.start()
        val uri = f.file("b.md", "base\n")
        f.vm.openDocument(uri)
        val discarded = f.vm.active?.open ?: error("nothing open")
        f.vm.active?.typeAtEnd("lost on purpose")
        f.vm.saves.leave(PendingLeave.Exit, discard = true)

        f.vm.start() // The launcher brings the activity back; the process lived on.

        val open = f.vm.active?.open ?: error("nothing open")
        assertNotSame(discarded, open)
        assertSame(open, f.store[open.uri])
        assertEquals(
            "base\n",
            f.vm.active
                ?.text
                ?.text
                ?.toString(),
        )
        assertFalse(open.isDirty)
    }

    @Test
    fun `CC-12 re-opening the file on screen does not leak a window`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val a = f.file("a.md", "a\n")
        val b = f.file("b.md", "b\n")
        f.vm.openDocument(a)
        f.vm.openDocument(a)

        f.vm.openDocument(b)

        assertNull(f.store[DocumentUri(a)])
        assertEquals(CloseOutcome.NotOpen, kotlinx.coroutines.runBlocking { f.store.close(DocumentUri(a)) })
    }

    @Test
    fun `DS-16 deleting a draft from the Navigator can be undone`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.start()
        val other = f.drafts.create() ?: error("no draft")
        f.drafts.resolve(other)?.writeText("# Only copy\n")
        f.vm.navigator.open()

        f.vm.navigator.onAction(NavigatorAction.DeleteDraft(other.value))

        assertFalse(f.drafts.list().any { it.uri == other })
        val message = f.vm.uiState.value.message ?: error("no message")
        assertEquals(MessageKind.DraftDeleted, message.kind)
        assertTrue(message.undo)

        f.vm.onAction(EditorAction.MessageUndo)

        assertEquals("# Only copy\n", f.drafts.resolve(other)?.readText())
    }

    @Test
    fun `DS-9 with History off the UI never claims a copy is kept`() {
        val off = AppSettings(history = HistoryPolicy(recording = false))
        val first = EditorFixture(temp.root, UnconfinedTestDispatcher(), settings = off)
        val uri = first.file("k.md", "kept\n")
        first.vm.openDocument(uri)
        first.vm.active?.typeLikeTheCodeView(0, "typed ")
        first.vm.onPause()
        assertFalse(first.vm.uiState.value.historyOn)

        val second = EditorFixture(temp.root, UnconfinedTestDispatcher(), settings = off)
        second.vm.start()
        assertTrue(second.vm.uiState.value.banner is EditorBanner.Recovered)
        second.vm.onAction(EditorAction.RecoveryDiscard)

        assertEquals(
            MessageKind.EditsDiscardedNoHistory,
            second.vm.uiState.value.message
                ?.kind,
        )
    }

    private companion object {
        const val LONG_LINE = 200_000
        const val KEYS = 10
        const val MAX_BYTES_PER_KEY = 64
    }
}
