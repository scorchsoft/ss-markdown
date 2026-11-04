package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.FormattedCopy
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Copying a selection as Markdown or formatted text (Andrew, 2026-11-04):
 * Preview's menu answers, and Code's "Copy formatted", which renders the
 * lines the selection touches through the page (as the header's Copy does;
 * its fallback to Markdown is CopyController's, tested with the header's).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SelectionCopyTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixture() = EditorFixture(temp.root, UnconfinedTestDispatcher()).also { it.vm.start() }

    @Test
    fun `Preview's Copy Markdown copies the selection's source exactly`() {
        val f = fixture()
        f.vm.copies.copyPreviewSelection(formatted = false, markdown = "- **a**\n- b", copy = null)
        assertEquals(EditorEvent.CopyText("- **a**\n- b"), f.events.last())
        assertEquals(
            MessageKind.Copied,
            f.vm.uiState.value.message
                ?.kind,
        )
    }

    @Test
    fun `Preview's Copy formatted copies the page's rendering`() {
        val f = fixture()
        val copy = FormattedCopy("<ul><li><b>a</b></li></ul>", "a", truncated = false)
        f.vm.copies.copyPreviewSelection(formatted = true, markdown = "- **a**", copy = copy)
        assertEquals(EditorEvent.CopyFormatted(copy), f.events.last())
        assertEquals(
            MessageKind.CopiedFormatted,
            f.vm.uiState.value.message
                ?.kind,
        )
    }

    @Test
    fun `a formatted copy too big for the clipboard goes as Markdown and says so`() {
        val f = fixture()
        f.vm.copies.copyPreviewSelection(formatted = true, markdown = "- a", copy = null)
        assertEquals(EditorEvent.CopyText("- a"), f.events.last())
        assertEquals(
            MessageKind.FormattedFailed,
            f.vm.uiState.value.message
                ?.kind,
        )
    }

    @Test
    fun `Code's Copy formatted takes the selection, and without a page copies its Markdown`() {
        val f = fixture()
        val doc = f.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(0, "", "one\ntwo\nthree\n")), TextSelection.caret(0))
        assertTrue("a selection is copied", f.vm.copies.copySelection(doc, 4, 7))
        assertFalse("an empty selection is not", f.vm.copies.copySelection(doc, 5, 5))
    }
}
