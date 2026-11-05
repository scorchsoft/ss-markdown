package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.HistoryListing
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.markdown.tidy.TidyRule
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ⋮ → Tidy (Andrew, 2026-11-04): it explains itself and asks first, applies
 * as one undo step after a History checkpoint, and does nothing to a file
 * that changed under the prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TidyControllerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixture(text: String): EditorFixture {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("t.md", text))
        return f
    }

    private fun EditorFixture.text() =
        vm.active
            ?.text
            ?.text
            ?.toString()

    private fun EditorFixture.prompt() = vm.uiState.value.overlay as? EditorOverlay.TidyPrompt

    @Test
    fun `Tidy asks first, saying what it would fix, and changes nothing until confirmed`() {
        val f = fixture("# Title\n\n\n\nText.\n")
        f.vm.onAction(EditorAction.OpenMenu)
        f.vm.onAction(EditorAction.TidyTapped)
        val prompt = f.prompt() ?: error("no prompt")
        assertEquals("one run of extra blank lines", 1, prompt.counts[TidyRule.ExtraBlankLines])
        assertEquals("# Title\n\n\n\nText.\n", f.text())
        f.vm.onAction(EditorAction.DismissOverlay)
        assertNull(f.vm.uiState.value.overlay)
        assertEquals("# Title\n\n\n\nText.\n", f.text())
    }

    @Test
    fun `Tidy is one undo step with a checkpoint first`() {
        val f = fixture("Title\n\n\n\nText.  \n")
        // Typed since opening, so the text before Tidy is in no version yet: the checkpoint holds it.
        val doc = f.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(0, "", "# ")), TextSelection.caret(2))
        f.vm.onAction(EditorAction.TidyTapped)
        f.vm.onAction(EditorAction.TidyConfirmed)
        assertNull(f.vm.uiState.value.overlay)
        assertEquals("# Title\n\nText.\n", f.text())
        assertEquals(
            MessageKind.Tidied,
            f.vm.uiState.value.message
                ?.kind,
        )
        val listing = f.versions.list(DocumentKey(EditorFixture.SCHEME + "t.md")) as HistoryListing.Listed
        assertTrue(listing.versions.any { it.kind == VersionKind.Checkpoint && it.label == "Before tidy" })
        f.vm.active
            ?.surface
            ?.undo()
        assertEquals("# Title\n\n\n\nText.  \n", f.text())
    }

    @Test
    fun `a tidy file says so and Tidy changes nothing`() {
        val f = fixture("# Title\n\nText.\n")
        f.vm.onAction(EditorAction.TidyTapped)
        assertEquals(
            true,
            f
                .prompt()
                ?.counts
                ?.values
                ?.all { it == 0 },
        )
        f.vm.onAction(EditorAction.TidyConfirmed)
        assertEquals("# Title\n\nText.\n", f.text())
    }

    @Test
    fun `text changed under the prompt is never tidied with the old plan`() {
        val f = fixture("a\n\n\n\nb\n")
        f.vm.onAction(EditorAction.TidyTapped)
        val doc = f.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(0, "", "x")), TextSelection.caret(1))
        f.vm.onAction(EditorAction.TidyConfirmed)
        assertEquals("xa\n\n\n\nb\n", f.text())
    }

    @Test
    fun `Settings - Tidy's choices are followed`() {
        val f = fixture("1. a\n1. b\n\n\n\nText.\n")
        f.settings.update { it.copy(tidyCountUpLists = false, tidyCollapseBlankLines = false) }
        f.vm.onAction(EditorAction.TidyTapped)
        assertEquals(
            true,
            f
                .prompt()
                ?.counts
                ?.values
                ?.all { it == 0 },
        )
        f.settings.update { it.copy(tidyCountUpLists = true, tidyCollapseBlankLines = true) }
        f.vm.onAction(EditorAction.TidyTapped)
        f.vm.onAction(EditorAction.TidyConfirmed)
        assertEquals("1. a\n2. b\n\nText.\n", f.text())
    }
}
