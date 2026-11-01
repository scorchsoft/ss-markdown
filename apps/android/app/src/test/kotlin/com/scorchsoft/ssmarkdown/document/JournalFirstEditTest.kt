package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.EditorBanner
import com.scorchsoft.ssmarkdown.editor.LineChange
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression: the Code view applies a keystroke before it reports it, and
 * the journal used to start only then. When an earlier session's journal
 * was still on disk (kept aside because it could not be read), the new
 * journal snapshotted the text *with* the keystroke and then appended the
 * keystroke too, so a crash replayed it twice. The first edit of a run
 * must reach the journal exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalFirstEditTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun fixture() = EditorFixture(temp.root, dispatcher)

    /** What MarkdownCodeView does: edit the buffer itself, then tell the listener which lines changed. */
    private fun ActiveDocument.typeLikeTheCodeView(
        at: Int,
        typed: String,
    ) {
        val line = text.text.positionAt(at).line
        text.replace(at, 0, typed)
        onTextEdited(LineChange(line, 0, typed.count { it == '\n' }), text.version)
    }

    private fun EditorFixture.reopenedText(uri: String): String {
        vm.openDocument(uri)
        return vm.active
            ?.text
            ?.text
            ?.toString()
            .orEmpty()
    }

    @Test
    fun `a first keystroke from the Code view is replayed once after a crash`() {
        val first = fixture()
        val uri = first.file("a.md", "one\n")
        first.vm.openDocument(uri)
        first.vm.active?.typeLikeTheCodeView(0, "Z")
        first.store.flushJournals()

        assertEquals("Zone\n", fixture().reopenedText(uri))
    }

    @Test
    fun `a first keystroke after an unreadable journal was kept aside is replayed once`() {
        val first = fixture()
        val uri = first.file("a.md", "one\n")
        first.vm.openDocument(uri)
        val doc = first.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(4, "", "lost\n")), TextSelection.caret(9))
        first.store.flushJournals()
        // The journal is damaged beyond reading: the next open leaves it where it is, untouched.
        File(temp.root, "journal")
            .listFiles { f -> f.name.endsWith(".ssmj") }
            ?.forEach { it.writeBytes(ByteArray(JUNK_BYTES) { JUNK }) }

        val second = fixture()
        second.vm.openDocument(uri)
        // Enter at the end of the first line: replaying it twice would leave two blank lines.
        second.vm.active?.typeLikeTheCodeView(3, "\n")
        second.store.flushJournals()

        val reopened = fixture()
        assertEquals("one\n\n", reopened.reopenedText(uri))
        assertEquals(EditorBanner.Recovered(0, closedBySwipe = false), reopened.vm.uiState.value.banner)
    }

    private companion object {
        const val JUNK_BYTES = 64
        const val JUNK: Byte = 0x5A
    }
}
